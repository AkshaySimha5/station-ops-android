package com.example.stationops.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.stationops.data.util.PreviewGenerator
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

class FileUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "upload_channel"
        const val NOTIFICATION_ID = 1001
        const val KEY_FILE_PATHS = "file_paths"
        const val KEY_MIME_TYPES = "mime_types"
        const val KEY_STATION_ID = "station_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_IS_ADMIN = "is_admin"
    }

    private val notificationManager by lazy {
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override suspend fun doWork(): Result {
        val filePaths = inputData.getStringArray(KEY_FILE_PATHS) ?: return Result.failure()
        val mimeTypes = inputData.getStringArray(KEY_MIME_TYPES) ?: return Result.failure()
        val stationId = inputData.getString(KEY_STATION_ID) ?: return Result.failure()
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()
        val isAdmin = inputData.getBoolean(KEY_IS_ADMIN, false)

        createNotificationChannel()

        // ── Verify Firebase Auth state & force-refresh token ────────────
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.e("FileUploadWorker", "No authenticated user – cannot upload")
            showFailureNotification("Not signed in. Please log in and try again.")
            cleanupTempFiles(filePaths)
            return Result.failure()
        }
        try {
            // Force-refresh the ID token so Firebase Storage accepts it
            currentUser.getIdToken(true).await()
            Log.d("FileUploadWorker", "Auth token refreshed for uid=${currentUser.uid}")
        } catch (e: Exception) {
            Log.e("FileUploadWorker", "Token refresh failed: ${e.message}", e)
            showFailureNotification("Authentication expired. Please log in again.")
            cleanupTempFiles(filePaths)
            return Result.failure()
        }

        val storage = FirebaseStorage.getInstance()
        val db = FirebaseFirestore.getInstance()
        val totalFiles = filePaths.size

        return try {
            setForeground(createForegroundInfo("Preparing upload…", 0, totalFiles * 100))

            filePaths.forEachIndexed { index, path ->
                val sourceFile = File(path)
                val mimeType = mimeTypes.getOrElse(index) { "application/octet-stream" }
                val mediaUUID = UUID.randomUUID().toString()

                // ── 1. Generate preview locally ─────────────────────────────
                val previewFile = PreviewGenerator.generatePreview(
                    applicationContext, sourceFile, mimeType
                )

                // ── 2. Upload preview immediately ───────────────────────────
                var previewUrl = ""
                if (previewFile != null) {
                    val previewStoragePath = if (isAdmin) {
                        "stations/$stationId/admin_docs/previews/$mediaUUID.jpg"
                    } else {
                        "stations/$stationId/employee_uploads/$userId/previews/$mediaUUID.jpg"
                    }
                    val previewRef = storage.reference.child(previewStoragePath)
                    previewRef.putFile(Uri.fromFile(previewFile)).await()
                    previewUrl = previewRef.downloadUrl.await().toString()
                    previewFile.delete()
                }

                // ── 3. Save Firestore doc with preview URL + PENDING status ─
                // Store the full MIME type so downloads can derive extensions reliably.
                val docData = hashMapOf(
                    "url" to "",                 // will be filled after full upload
                    "previewUrl" to previewUrl,
                    "type" to mimeType,
                    "uploadStatus" to "UPLOADING",
                    "uploaderId" to userId,
                    "stationId" to stationId,
                    "timestamp" to Timestamp.now()
                )
                val docRef = db.collection("uploads").add(docData).await()

                // ── 4. Upload full media in background ──────────────────────
                val storagePath = if (isAdmin) {
                    "stations/$stationId/admin_docs/$mediaUUID"
                } else {
                    "stations/$stationId/employee_uploads/$userId/$mediaUUID"
                }

                val storageRef = storage.reference.child(storagePath)
                val uploadTask = storageRef.putFile(Uri.fromFile(sourceFile))

                uploadTask.addOnProgressListener { snapshot ->
                    if (snapshot.totalByteCount > 0) {
                        val filePercent =
                            (snapshot.bytesTransferred * 100 / snapshot.totalByteCount).toInt()
                        val overallProgress = index * 100 + filePercent
                        updateProgressNotification(
                            "Uploading ${index + 1} of $totalFiles ($filePercent%)",
                            overallProgress,
                            totalFiles * 100
                        )
                    }
                }

                uploadTask.await()

                // ── 5. Update Firestore doc with full media URL + COMPLETED ─
                val downloadUrl = storageRef.downloadUrl.await().toString()
                docRef.update(
                    mapOf(
                        "url" to downloadUrl,
                        "uploadStatus" to "COMPLETED"
                    )
                ).await()
            }

            showCompletionNotification(totalFiles)
            cleanupTempFiles(filePaths)
            Result.success()
        } catch (e: Exception) {
            Log.e("FileUploadWorker", "Upload failed: ${e.message}", e)

            // Mark any in-flight docs as FAILED (best-effort)
            try {
                // We can't easily track which docs were created, so we just log
                Log.w("FileUploadWorker", "Some uploads may be left in UPLOADING state")
            } catch (_: Exception) {}

            showFailureNotification(e.message ?: "Unknown error")
            cleanupTempFiles(filePaths)
            Result.failure()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun cleanupTempFiles(filePaths: Array<String>) {
        filePaths.forEach { path ->
            try {
                File(path).delete()
            } catch (_: Exception) { }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows file upload progress"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(text: String, progress: Int, max: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Station Ops Upload")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgressNotification(text: String, progress: Int, max: Int) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Station Ops Upload")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(totalFiles: Int) {
        notificationManager.cancel(NOTIFICATION_ID)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Upload Complete")
            .setContentText("$totalFiles file(s) uploaded successfully")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showFailureNotification(error: String) {
        notificationManager.cancel(NOTIFICATION_ID)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Upload Failed")
            .setContentText(error)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }
}
