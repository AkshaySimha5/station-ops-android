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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.example.stationops.data.model.Upload
import com.example.stationops.data.repository.AuthRepository
import com.example.stationops.data.repository.StationRepository
import com.example.stationops.data.worker.FileUploadWorker
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

                    val tempFile = File(context.cacheDir, "upload_${UUID.randomUUID()}$extension")

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
                }

                val inputData = workDataOf(
                    FileUploadWorker.KEY_FILE_PATHS to tempFiles.toTypedArray(),
                    FileUploadWorker.KEY_MIME_TYPES to mimeTypes.toTypedArray(),
                    FileUploadWorker.KEY_STATION_ID to stationId,
                    FileUploadWorker.KEY_USER_ID to userId,
                    FileUploadWorker.KEY_IS_ADMIN to isAdmin
                )

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val uploadRequest = OneTimeWorkRequestBuilder<FileUploadWorker>()
                    .setInputData(inputData)
                    .setConstraints(constraints)
                    // Exponential backoff so Result.retry() in the worker backs off
                    // gracefully instead of hammering Firebase immediately.
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS
                    )
                    .build()

                val workManager = WorkManager.getInstance(context)

                // Per-station unique work name prevents concurrent overlap:
                //   - Double-taps / rapid re-selections for the same station are serialised.
                //   - Different stations can still upload concurrently.
                //   - APPEND_OR_REPLACE: chains behind any running worker for this station;
                //     replaces it if it's blocked or cancelled.
                workManager.enqueueUniqueWork(
                    "upload_$stationId",
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    uploadRequest
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