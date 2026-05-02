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
        val snapshot = db.collection("uploads")
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

    /**
     * Finds any upload docs belonging to [userId] that have been stuck in UPLOADING
     * for more than 20 minutes and marks them as FAILED.
     *
     * This covers the case where the app process was killed (OEM battery manager,
     * user swipe, OS reclaim) after the UPLOADING doc was written but before the
     * worker could set it to COMPLETED, leaving it orphaned forever.
     *
     * Call this once on app start from the ViewModel's init block so stale records
     * are cleaned up the next time the user opens the app.
     */
    suspend fun reconcileStaleUploads(userId: String) {
        val twentyMinutesAgoSeconds = System.currentTimeMillis() / 1000 - (20 * 60)
        val cutoff = Timestamp(twentyMinutesAgoSeconds, 0)

        try {
            val staleSnapshot = db.collection("uploads")
                .whereEqualTo("uploaderId", userId)
                .whereEqualTo("uploadStatus", "UPLOADING")
                .whereLessThan("timestamp", cutoff)
                .get()
                .await()

            if (staleSnapshot.isEmpty) {
                Log.d("StationRepo", "reconcileStaleUploads: no stale records found for userId=$userId")
                return
            }

            Log.w(
                "StationRepo",
                "reconcileStaleUploads: found ${staleSnapshot.size()} stale UPLOADING doc(s) for userId=$userId — marking as FAILED"
            )

            staleSnapshot.documents.forEach { doc ->
                try {
                    doc.reference.update(
                        mapOf(
                            "uploadStatus" to "FAILED",
                            "failedAt" to Timestamp.now()
                        )
                    ).await()
                    Log.i("StationRepo", "reconcileStaleUploads: marked doc ${doc.id} as FAILED")
                } catch (e: Exception) {
                    // Log and continue — a failure on one doc should not block the rest
                    Log.e(
                        "StationRepo",
                        "reconcileStaleUploads: failed to update doc ${doc.id}: ${e.message}",
                        e
                    )
                }
            }
        } catch (e: Exception) {
            // Non-fatal — if the query itself fails (e.g. offline), log and move on.
            // The next app launch will retry.
            Log.e("StationRepo", "reconcileStaleUploads: query failed: ${e.message}", e)
        }
    }
}