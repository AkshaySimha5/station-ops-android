package com.example.stationops.ui.station_detail

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.example.stationops.data.model.Upload
import com.example.stationops.data.repository.AuthRepository
import com.example.stationops.data.repository.StationRepository
import com.example.stationops.data.worker.FileUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class StationDetailViewModel : ViewModel() {
    private val stationRepo = StationRepository()
    private val authRepo = AuthRepository()

    var groupedUploads = mutableStateOf<Map<String, List<Upload>>>(emptyMap())
    var isLoading = mutableStateOf(false)
    var isUploading = mutableStateOf(false)
    var uploadProgress = mutableStateOf(0f)
    var uploadStatusText = mutableStateOf("")

    init {
        // On every ViewModel creation (i.e. app launch / screen entry), sweep any
        // upload docs that were orphaned by a previous process death and are still
        // stuck in UPLOADING after 20 minutes. Fire-and-forget — UI is unaffected
        // whether this succeeds or fails.
        val userId = authRepo.getCurrentUserId()
        if (userId != null) {
            viewModelScope.launch {
                stationRepo.reconcileStaleUploads(userId)
            }
        }
    }

    /**
     * Call this once from the composable / fragment after the ViewModel is created,
     * passing a Context. It runs the orphaned-file recovery sweep which needs Context
     * to access filesDir and WorkManager. Safe to call on every recomposition — it
     * guards internally against running more than once per ViewModel instance.
     */
    private var recoveryRan = false
    fun runStartupRecovery(context: Context) {
        if (recoveryRan) return
        recoveryRan = true
        val userId = authRepo.getCurrentUserId() ?: return
        viewModelScope.launch {
            recoverOrphanedUploads(context, userId)
        }
    }



    fun loadUploads(stationId: String) {
        authRepo.getCurrentUserId() ?: return
        viewModelScope.launch {
            isLoading.value = true
            try {
                val rawList = stationRepo.getUploads(stationId)
                val grouped = rawList.groupBy { upload ->
                    val date = upload.timestamp.toDate()
                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
                }
                groupedUploads.value = grouped
            } catch (e: Exception) {
                Log.e("StationDetailVM", "Load error: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }
    }

    fun uploadFilesInBackground(context: Context, uris: List<Uri>, stationId: String, isAdmin: Boolean) {
        val userId = authRepo.getCurrentUserId() ?: return

        viewModelScope.launch {
            try {
                // Copy content URIs to temp files so they survive past the picker lifecycle
                val tempFiles = mutableListOf<String>()
                val mimeTypes = mutableListOf<String>()

                uris.forEach { uri ->
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    mimeTypes.add(mimeType)

                    val extension = when {
                        mimeType.startsWith("image") -> ".jpg"
                        mimeType.startsWith("video") -> ".mp4"
                        else -> ".bin"
                    }

                    // Use filesDir instead of cacheDir.
                    // cacheDir is expendable — the OS can and does clear it under storage
                    // pressure or on screen lock on some OEMs, which caused "Temp file missing"
                    // permanent failures in production. filesDir is persistent app storage
                    // that is never automatically cleared by the OS (only on app uninstall
                    // or manual "Clear Data"). This makes temp files survive indefinitely
                    // until the worker completes and explicitly deletes them.
                    val tempDir = File(context.filesDir, "pending_uploads").also { it.mkdirs() }
                    val tempFile = File(tempDir, "upload_${UUID.randomUUID()}$extension")


                    // BUG 2 FIX: openInputStream returns null for revoked URI permissions,
                    // deleted media, or certain OEM content providers. Previously this
                    // silently created a 0-byte file that was passed to the worker, causing
                    // a mysterious Storage upload failure with no Firestore doc to clean up.
                    // Now we throw immediately with a clear message so the user gets a
                    // meaningful Toast and no worker is enqueued with a broken file.
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open stream for URI: $uri — permission may have been revoked")

                    stream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Guard against a successful stream that produced 0 bytes (empty media,
                    // virtual file provider edge case, etc.)
                    if (tempFile.length() == 0L) {
                        tempFile.delete()
                        throw IOException("Copied file is 0 bytes for URI: $uri — source media may be empty or unreadable")
                    }

                    tempFiles.add(tempFile.absolutePath)
                    
                    // Write a sidecar metadata file alongside the media file.
                    // If WorkManager loses its job database (app reinstall, aggressive OEM
                    // app-update behaviour), the worker record is gone but the media file
                    // survives in filesDir. recoverOrphanedUploads() reads these sidecars
                    // on app launch and re-enqueues any file that has no active worker.
                    val meta = JSONObject().apply {
                        put("stationId", stationId)
                        put("userId", userId)
                        put("mimeType", mimeType)
                        put("isAdmin", isAdmin)
                        put("enqueuedAt", System.currentTimeMillis())
                    }
                    File("${tempFile.absolutePath}.meta").writeText(meta.toString())

                }

                val workManager = WorkManager.getInstance(context)

                val uploadRequest = enqueueUploadRequest(
                    context = context,
                    filePaths = tempFiles.toTypedArray(),
                    mimeTypes = mimeTypes.toTypedArray(),
                    stationId = stationId,
                    userId = userId,
                    isAdmin = isAdmin
                )

                Toast.makeText(context, "Upload started in background", Toast.LENGTH_SHORT).show()

                // Observe completion to auto-refresh the uploads list
                viewModelScope.launch {
                    workManager.getWorkInfoByIdFlow(uploadRequest.id)
                        .filter { it?.state?.isFinished == true }
                        .first()
                    loadUploads(stationId)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error starting upload: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

        /**
     * On app launch, scans filesDir/pending_uploads/ for any media files whose
     * corresponding WorkManager job no longer exists (e.g. after app reinstall or
     * OEM WorkManager database wipe) and re-enqueues them so the upload is retried.
     *
     * Each media file has a sidecar .meta JSON file written at enqueue time containing
     * the stationId, userId, mimeType, isAdmin, and enqueuedAt fields needed to
     * reconstruct the WorkRequest without any user interaction.
     *
     * Files whose worker is still active (ENQUEUED or RUNNING) are left untouched.
     */
    private suspend fun recoverOrphanedUploads(context: Context, userId: String) {
        withContext(Dispatchers.IO) {
            val pendingDir = File(context.filesDir, "pending_uploads")
            if (!pendingDir.exists()) return@withContext

            val metaFiles = pendingDir.listFiles { f -> f.name.endsWith(".meta") }
                ?: return@withContext
            if (metaFiles.isEmpty()) return@withContext

            Log.d("StationDetailVM", "recoverOrphanedUploads: scanning ${metaFiles.size} sidecar(s)")

            val workManager = WorkManager.getInstance(context)

            metaFiles.forEach { metaFile ->
                val mediaFile = File(metaFile.absolutePath.removeSuffix(".meta"))

                // Sidecar exists but media file is gone — stale sidecar, clean up
                if (!mediaFile.exists()) {
                    metaFile.delete()
                    Log.w("StationDetailVM", "recoverOrphanedUploads: removed stale sidecar ${metaFile.name}")
                    return@forEach
                }

                // Parse sidecar
                val meta = try {
                    JSONObject(metaFile.readText())
                } catch (e: Exception) {
                    Log.e("StationDetailVM", "recoverOrphanedUploads: corrupt sidecar ${metaFile.name}, skipping")
                    return@forEach
                }

                val stationId = meta.optString("stationId").ifEmpty {
                    Log.e("StationDetailVM", "recoverOrphanedUploads: missing stationId in ${metaFile.name}, skipping")
                    return@forEach
                }

                // Check if an active worker already exists for this station.
                // enqueueUniqueWork guarantees at most one active worker per station name,
                // so if one is running it will process this file as part of its batch.
                val activeWork = workManager
                    .getWorkInfosForUniqueWork("upload_$stationId")
                    .get()
                    .filter { !it.state.isFinished }

                if (activeWork.isNotEmpty()) {
                    Log.d(
                        "StationDetailVM",
                        "recoverOrphanedUploads: active worker exists for station $stationId, skipping ${mediaFile.name}"
                    )
                    return@forEach
                }

                // No active worker — re-enqueue this file
                Log.w(
                    "StationDetailVM",
                    "recoverOrphanedUploads: re-enqueuing orphaned file ${mediaFile.name} for station $stationId"
                )

                enqueueUploadRequest(
                    context = context,
                    filePaths = arrayOf(mediaFile.absolutePath),
                    mimeTypes = arrayOf(meta.optString("mimeType", "application/octet-stream")),
                    stationId = stationId,
                    userId = meta.optString("userId", userId),
                    isAdmin = meta.optBoolean("isAdmin", false)
                )
            }
        }
    }

    /**
     * Shared helper that builds and enqueues a FileUploadWorker WorkRequest.
     * Used by both uploadFilesInBackground (new uploads) and recoverOrphanedUploads
     * (re-enqueued orphans) to ensure both paths use identical WorkRequest configuration.
     */
    private fun enqueueUploadRequest(
        context: Context,
        filePaths: Array<String>,
        mimeTypes: Array<String>,
        stationId: String,
        userId: String,
        isAdmin: Boolean
    ): OneTimeWorkRequest {
        val inputData = workDataOf(
            FileUploadWorker.KEY_FILE_PATHS to filePaths,
            FileUploadWorker.KEY_MIME_TYPES to mimeTypes,
            FileUploadWorker.KEY_STATION_ID to stationId,
            FileUploadWorker.KEY_USER_ID to userId,
            FileUploadWorker.KEY_IS_ADMIN to isAdmin
        )

        val request = OneTimeWorkRequestBuilder<FileUploadWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag("station_upload")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "upload_$stationId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )

        return request
    }

    fun deleteFile(upload: Upload, stationId: String) {
        viewModelScope.launch {
            isLoading.value = true
            try {
                stationRepo.deleteFile(upload)
                loadUploads(stationId)
            } catch (e: Exception) {
                Log.e("StationDetailVM", "Delete failed: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }
    }
}