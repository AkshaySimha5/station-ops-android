package com.example.stationops.data.repository

import android.net.Uri
import android.util.Log
import com.example.stationops.data.model.Station
import com.example.stationops.data.model.Upload
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date
import java.util.UUID

class StationRepository {
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    suspend fun getStations(isAdmin: Boolean): List<Station> {
        val collection = db.collection("stations")
        val snapshot = if (isAdmin) {
            collection.get().await()
        } else {
            collection.whereEqualTo("isUploadEnabled", true).get().await()
        }
        return snapshot.toObjects(Station::class.java).sortedBy { it.name.lowercase() }
    }

    suspend fun addStation(name: String, adminId: String) {
        val ref = db.collection("stations").document()
        val station = Station(id = ref.id, name = name, isUploadEnabled = true, createdBy = adminId)
        ref.set(station).await()
    }

    suspend fun toggleStationStatus(stationId: String, isEnabled: Boolean) {
        db.collection("stations").document(stationId)
            .update("isUploadEnabled", isEnabled)
            .await()
    }

    suspend fun deleteStation(stationId: String) {
        db.collection("stations").document(stationId).delete().await()
    }

    suspend fun uploadFile(
        uri: Uri,
        mimeType: String,
        stationId: String,
        userId: String,
        isAdmin: Boolean,
        onProgress: (Float) -> Unit
    ) {
        val fileName = "${UUID.randomUUID()}"
        val storagePath = if (isAdmin) {
            "stations/$stationId/admin_docs/$fileName"
        } else {
            "stations/$stationId/employee_uploads/$userId/$fileName"
        }

        val storageRef = storage.reference.child(storagePath)
        val uploadTask = storageRef.putFile(uri)
        uploadTask.addOnProgressListener { snapshot ->
            if (snapshot.totalByteCount > 0) {
                val progress = (100.0 * snapshot.bytesTransferred) / snapshot.totalByteCount
                onProgress(progress.toFloat())
            }
        }
        uploadTask.await()

        val downloadUrl = storageRef.downloadUrl.await().toString()
        val upload = Upload(
            id = "",
            url = downloadUrl,
            type = mimeType,
            uploaderId = userId,
            stationId = stationId,
            timestamp = Timestamp.now()
        )
        db.collection("uploads").add(upload).await()
    }

    suspend fun getUploads(stationId: String): List<Upload> {
        var snapshot = db.collection("uploads")
            .whereEqualTo("stationId", stationId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val upload = doc.toObject(Upload::class.java)
            upload?.copy(id = doc.id)
        }
    }

    suspend fun getStationName(stationId: String): String {
        val doc = db.collection("stations").document(stationId).get().await()
        return doc.getString("name") ?: "Station_Unknown"
    }

    suspend fun deleteFile(upload: Upload) {
        // Delete full media from Storage
        try {
            if (upload.url.isNotEmpty()) {
                val storageRef = storage.getReferenceFromUrl(upload.url)
                storageRef.delete().await()
            }
        } catch (e: Exception) {
            Log.w("StationRepo", "Storage delete failed: ${e.message}")
        }

        // Delete preview from Storage
        try {
            if (upload.previewUrl.isNotEmpty()) {
                val previewRef = storage.getReferenceFromUrl(upload.previewUrl)
                previewRef.delete().await()
            }
        } catch (e: Exception) {
            Log.w("StationRepo", "Preview delete failed: ${e.message}")
        }

        if (upload.id.isNotEmpty()) {
            db.collection("uploads").document(upload.id).delete().await()
        } else {
            throw Exception("Cannot delete: File ID is missing")
        }
    }
}