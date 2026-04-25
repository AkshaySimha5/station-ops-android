package com.example.stationops.ui.station_detail

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.stationops.data.model.Upload
import com.example.stationops.data.repository.AuthRepository
import com.example.stationops.data.repository.StationRepository
import com.example.stationops.data.worker.FileUploadWorker
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class StationDetailViewModel : ViewModel() {
    private val stationRepo = StationRepository()
    private val authRepo = AuthRepository()

    var groupedUploads = mutableStateOf<Map<String, List<Upload>>>(emptyMap())
    var isLoading = mutableStateOf(false)
    var isUploading = mutableStateOf(false)
    var uploadProgress = mutableStateOf(0f)
    var uploadStatusText = mutableStateOf("")

    fun loadUploads(stationId: String) {
        val userId = authRepo.getCurrentUserId() ?: return
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

    fun uploadFiles(context: Context, uris: List<Uri>, stationId: String, isAdmin: Boolean) {
        val userId = authRepo.getCurrentUserId() ?: return

        viewModelScope.launch {
            isUploading.value = true
            isLoading.value = false
            try {
                uris.forEachIndexed { index, uri ->
                    uploadStatusText.value = "Uploading ${index + 1} of ${uris.size}..."
                    uploadProgress.value = 0f

                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

                    stationRepo.uploadFile(
                        uri = uri,
                        mimeType = mimeType,
                        stationId = stationId,
                        userId = userId,
                        isAdmin = isAdmin,
                        onProgress = { percent ->
                            uploadProgress.value = percent / 100f
                        }
                    )
                }

                Toast.makeText(context, "All files uploaded!", Toast.LENGTH_SHORT).show()
                loadUploads(stationId)
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isUploading.value = false
                uploadProgress.value = 0f
                uploadStatusText.value = ""
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
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
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
                    .build()

                val workManager = WorkManager.getInstance(context)
                workManager.enqueue(uploadRequest)

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

    fun deleteFile(upload: Upload, stationId: String, isAdmin: Boolean) {
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