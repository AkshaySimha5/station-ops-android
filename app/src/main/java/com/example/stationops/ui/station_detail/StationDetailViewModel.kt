package com.example.stationops.ui.station_detail

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.example.stationops.data.model.Upload
import com.example.stationops.data.repository.AuthRepository
import com.example.stationops.data.repository.StationRepository
import com.example.stationops.data.util.UploadRecoveryManager
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class StationDetailViewModel : ViewModel() {
    private val stationRepo = StationRepository()
    private val authRepo = AuthRepository()

    var groupedUploads = mutableStateOf<Map<String, List<Upload>>>(emptyMap())
    var isLoading = mutableStateOf(false)

    fun runStartupRecovery(context: Context) {
        UploadRecoveryManager.triggerRecovery(context)
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
                val tempDir = File(context.filesDir, "pending_uploads").also { it.mkdirs() }
                val workManager = WorkManager.getInstance(context)
                val requests = mutableListOf<OneTimeWorkRequest>()

                uris.forEach { uri ->
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val uploadId = UUID.randomUUID().toString()

                    val extension = when {
                        mimeType.startsWith("image") -> ".jpg"
                        mimeType.startsWith("video") -> ".mp4"
                        else -> ".bin"
                    }

                    val tempFile = File(tempDir, "upload_$uploadId$extension")
                    val metaFile = File(tempDir, "upload_$uploadId.meta")

                    try {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw IOException("Could not open stream for URI: $uri — permission may have been revoked")

                        stream.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        if (tempFile.length() == 0L) {
                            throw IOException("Copied file is 0 bytes for URI: $uri — source media may be empty or unreadable")
                        }

                        val meta = JSONObject().apply {
                            put("uploadId", uploadId)
                            put("stationId", stationId)
                            put("userId", userId)
                            put("mimeType", mimeType)
                            put("isAdmin", isAdmin)
                            put("filePath", tempFile.absolutePath)
                            put("enqueuedAt", System.currentTimeMillis())
                        }
                        metaFile.writeText(meta.toString())

                        val request = UploadRecoveryManager.enqueueUploadRequest(
                            context = context,
                            uploadId = uploadId,
                            filePath = tempFile.absolutePath,
                            mimeType = mimeType,
                            stationId = stationId,
                            userId = userId,
                            isAdmin = isAdmin
                        )
                        requests.add(request)
                    } catch (e: Exception) {
                        tempFile.delete()
                        metaFile.delete()
                        throw e
                    }
                }

                Toast.makeText(context, "${requests.size} upload(s) queued in background", Toast.LENGTH_SHORT).show()

                // Reload uploads upon completion of any request
                requests.forEach { req ->
                    viewModelScope.launch {
                        workManager.getWorkInfoByIdFlow(req.id)
                            .filter { it?.state?.isFinished == true }
                            .first()
                        loadUploads(stationId)
                    }
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
