package com.example.stationops.ui.station_detail

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stationops.data.model.Upload
import com.example.stationops.data.repository.AuthRepository
import com.example.stationops.data.repository.StationRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class StationDetailViewModel : ViewModel() {
    private val stationRepo = StationRepository()
    private val authRepo = AuthRepository()

    var groupedUploads = mutableStateOf<Map<String, List<Upload>>>(emptyMap())
    var isLoading = mutableStateOf(false)
    var isUploading = mutableStateOf(false)
    var uploadProgress = mutableStateOf(0f)
    var uploadStatusText = mutableStateOf("")

    fun loadUploads(stationId: String, isAdmin: Boolean) {
        val userId = authRepo.getCurrentUserId() ?: return
        viewModelScope.launch {
            isLoading.value = true
            try {
                val rawList = stationRepo.getUploads(stationId, isAdmin, userId)
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
                loadUploads(stationId, isAdmin)
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isUploading.value = false
                uploadProgress.value = 0f
                uploadStatusText.value = ""
            }
        }
    }

    fun deleteFile(upload: Upload, stationId: String, isAdmin: Boolean) {
        viewModelScope.launch {
            isLoading.value = true
            try {
                stationRepo.deleteFile(upload)
                loadUploads(stationId, isAdmin)
            } catch (e: Exception) {
                Log.e("StationDetailVM", "Delete failed: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }
    }
}