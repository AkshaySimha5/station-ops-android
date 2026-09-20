package com.example.stationops.data.worker

import android.R
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.abedelazizshe.lightcompressorlibrary.config.AppSpecificStorageConfiguration
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.example.stationops.data.util.ImageCompressor
import com.example.stationops.data.util.PreviewGenerator
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class FileUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "upload_channel"
        const val KEY_UPLOAD_ID = "upload_id"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_STATION_ID = "station_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_IS_ADMIN = "is_admin"

        // Legacy compatibility keys
        const val KEY_FILE_PATHS = "file_paths"
        const val KEY_MIME_TYPES = "mime_types"
    }

    private val notificationId: Int by lazy {
        val stationId = inputData.getString(KEY_STATION_ID) ?: "default"
        val uploadId = inputData.getString(KEY_UPLOAD_ID) ?: "single"
        "$stationId-$uploadId".hashCode().and(0x7FFFFFFF)
    }

    private val notificationManager by lazy {
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override suspend fun doWork(): Result {
        val stationId = inputData.getString(KEY_STATION_ID) ?: return Result.failure()
        val userId = inputData.getString(KEY_USER_ID) ?: return Result.failure()
        val isAdmin = inputData.getBoolean(KEY_IS_ADMIN, false)

        // Read single file parameters or fallback to legacy array
        val uploadId = inputData.getString(KEY_UPLOAD_ID)
            ?: UUID.randomUUID().toString()
        val filePath = inputData.getString(KEY_FILE_PATH)
            ?: inputData.getStringArray(KEY_FILE_PATHS)?.firstOrNull()
            ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE)
            ?: inputData.getStringArray(KEY_MIME_TYPES)?.firstOrNull()
            ?: "application/octet-stream"

        createNotificationChannel()

        // ── Step 1. Verify Firebase Auth & refresh ID token ──────────────────
        // Note: Auth failures intentionally leave local temp media and .meta sidecar
        // files intact so UploadRecoveryManager can re-enqueue the upload when the
        // user re-authenticates or logs back in.
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.e("FileUploadWorker", "No authenticated user – cannot upload")
            showFailureNotification("Not signed in. Please log in and try again.")
            return Result.failure()
        }

        try {
            currentUser.getIdToken(true).await()
            Log.d("FileUploadWorker", "Auth token refreshed for uid=${currentUser.uid}")
        } catch (e: Exception) {
            Log.e("FileUploadWorker", "Token refresh failed: ${e.message}", e)
            if (e is FirebaseNetworkException) {
                Log.w("FileUploadWorker", "Token refresh failed due to network — scheduling retry")
                return Result.retry()
            }
            showFailureNotification("Authentication expired. Please log in again.")
            return Result.failure()
        }

        val storage = FirebaseStorage.getInstance()
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("uploads").document(uploadId)

        return try {
            // Step 2. Validate temp file existence and size
            val sourceFile = File(filePath)
            if (!sourceFile.exists()) {
                Log.e("FileUploadWorker", "Temp file missing for upload $uploadId: $filePath")
                cleanupTempFile(filePath, uploadId)
                return Result.failure()
            }
            if (sourceFile.length() == 0L) {
                Log.e("FileUploadWorker", "Temp file zero bytes for upload $uploadId: $filePath")
                cleanupTempFile(filePath, uploadId)
                return Result.failure()
            }

            // Step 3. Check Firestore for existing completed status (Idempotency)
            var existingPreviewUrl = ""
            try {
                val snapshot = docRef.get().await()
                if (snapshot.exists()) {
                    val status = snapshot.getString("uploadStatus")
                    if (status == "COMPLETED") {
                        Log.i("FileUploadWorker", "Upload $uploadId is already COMPLETED. Cleaning up.")
                        cleanupTempFile(filePath, uploadId)
                        showCompletionNotification(1)
                        return Result.success()
                    }
                    existingPreviewUrl = snapshot.getString("previewUrl") ?: ""
                }
            } catch (e: Exception) {
                if (!isTransientError(e)) {
                    Log.e("FileUploadWorker", "Non-transient error checking existing doc $uploadId: ${e.message}")
                    return Result.failure()
                }
                Log.w("FileUploadWorker", "Transient error checking existing doc $uploadId: ${e.message} — continuing")
            }

            // Step 4. Safely set foreground service (with Android 12+ guard)
            try {
                setForeground(createForegroundInfo("Preparing upload…", 0, 100))
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    Log.w("FileUploadWorker", "Foreground service start not allowed in background. Proceeding as background task.")
                } else {
                    Log.w("FileUploadWorker", "Could not set foreground info: ${e.message}")
                }
            }

            // Step 5. Compression Phase (Image or Video)
            val isVideo = isVideoFile(sourceFile, mimeType)
            val uploadFile = if (isVideo) {
                compressVideoIfNeeded(sourceFile, uploadId) ?: sourceFile
            } else {
                ImageCompressor.compressImageIfNeeded(
                    context = applicationContext,
                    sourceFile = sourceFile,
                    mimeType = mimeType,
                    uploadId = uploadId
                )
            }

            val uploadMimeType = if (isVideo && uploadFile.absolutePath != sourceFile.absolutePath) {
                "video/mp4"
            } else if (isVideo) {
                normalizeVideoMimeType(mimeType)
            } else {
                mimeType
            }

            // Step 6. Generate and upload preview thumbnail if needed
            var previewUrl = existingPreviewUrl
            if (previewUrl.isEmpty()) {
                val previewFile = PreviewGenerator.generatePreview(
                    applicationContext, uploadFile, uploadMimeType
                )
                if (previewFile != null) {
                    val previewStoragePath = if (isAdmin) {
                        "stations/$stationId/admin_docs/previews/$uploadId.jpg"
                    } else {
                        "stations/$stationId/employee_uploads/$userId/previews/$uploadId.jpg"
                    }
                    val previewRef = storage.reference.child(previewStoragePath)
                    previewRef.putFile(Uri.fromFile(previewFile)).await()
                    previewUrl = previewRef.downloadUrl.await().toString()
                    previewFile.delete()
                }
            }

            // Step 7. Write/Update Firestore document with UPLOADING status
            val docData = hashMapOf(
                "url" to "",
                "previewUrl" to previewUrl,
                "type" to uploadMimeType,
                "uploadStatus" to "UPLOADING",
                "uploaderId" to userId,
                "stationId" to stationId,
                "timestamp" to Timestamp.now()
            )
            docRef.set(docData, SetOptions.merge()).await()

            // Step 8. Upload full media to Firebase Storage
            val storagePath = if (isAdmin) {
                "stations/$stationId/admin_docs/$uploadId"
            } else {
                "stations/$stationId/employee_uploads/$userId/$uploadId"
            }

            val storageRef = storage.reference.child(storagePath)
            val metadata = StorageMetadata.Builder()
                .setContentType(uploadMimeType)
                .build()

            val uploadTask = storageRef.putFile(Uri.fromFile(uploadFile), metadata)

            uploadTask.addOnProgressListener { snapshot ->
                if (snapshot.totalByteCount > 0) {
                    val percent = (snapshot.bytesTransferred * 100 / snapshot.totalByteCount).toInt()
                    updateProgressNotification("Uploading ($percent%)", percent, 100)
                }
            }

            uploadTask.await()

            // Step 9. Mark Firestore document as COMPLETED
            val downloadUrl = storageRef.downloadUrl.await().toString()
            docRef.update(
                mapOf(
                    "url" to downloadUrl,
                    "uploadStatus" to "COMPLETED"
                )
            ).await()

            Log.i("FileUploadWorker", "Upload $uploadId successfully completed!")
            showCompletionNotification(1)
            cleanupTempFile(filePath, uploadId)
            Result.success()

        } catch (e: Exception) {
            Log.e("FileUploadWorker", "Upload failed for $uploadId: ${e.message}", e)

            if (isTransientError(e)) {
                Log.w("FileUploadWorker", "Transient failure on upload $uploadId (attempt $runAttemptCount) – scheduling retry.")
                showRetryingNotification()
                Result.retry()
            } else {
                Log.e("FileUploadWorker", "Permanent failure on upload $uploadId – giving up.")
                try {
                    docRef.update("internalState", "BLOCKED").await()
                } catch (_: Exception) { }
                showFailureNotification("Upload could not be completed.")
                cleanupTempFile(filePath, uploadId)
                Result.failure()
            }
        }
    }

    private fun isTransientError(e: Exception): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            e is ForegroundServiceStartNotAllowedException -> true

        e is IOException -> {
            val message = e.message ?: ""
            !message.startsWith("Temp file missing") &&
                !message.startsWith("Temp file is zero bytes")
        }
        e is StorageException -> {
            e.errorCode != StorageException.ERROR_NOT_AUTHENTICATED &&
                e.errorCode != StorageException.ERROR_NOT_AUTHORIZED
        }
        e is FirebaseFirestoreException -> {
            when (e.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> true
                else -> false
            }
        }
        else -> false
    }

    private suspend fun compressVideoIfNeeded(
        sourceFile: File,
        uploadId: String
    ): File? {
        val sourceSizeMb = sourceFile.length() / (1024f * 1024f)
        if (sourceSizeMb <= 30f) {
            return sourceFile
        }

        val outputName = "compressed_$uploadId.mp4"
        val compressionOutputDir = File(applicationContext.filesDir, "compressed_uploads")
        if (!compressionOutputDir.exists()) {
            compressionOutputDir.mkdirs()
        }

        val quality = when {
            sourceSizeMb >= 100f -> VideoQuality.LOW
            else -> VideoQuality.MEDIUM
        }

        return compressVideoOnce(sourceFile, outputName, quality) ?: sourceFile
    }

    private suspend fun compressVideoOnce(
        sourceFile: File,
        outputName: String,
        quality: VideoQuality
    ): File? {
        val expectedOutputFile = File(applicationContext.filesDir, "compressed_uploads/$outputName")

        return suspendCancellableCoroutine { continuation ->
            var completed = false

            fun completeOnce(file: File?) {
                if (completed) return
                completed = true
                continuation.resume(file)
            }

            VideoCompressor.start(
                context = applicationContext,
                uris = listOf(Uri.fromFile(sourceFile)),
                isStreamable = true,
                storageConfiguration = AppSpecificStorageConfiguration("compressed_uploads"),
                configureWith = Configuration(
                    quality = quality,
                    isMinBitrateCheckEnabled = false,
                    disableAudio = false,
                    resizer = null,
                    videoNames = listOf(outputName)
                ),
                listener = object : CompressionListener {
                    override fun onStart(index: Int) {
                        updateProgressNotification("Compressing video...", 0, 100)
                    }

                    override fun onProgress(index: Int, percent: Float) {
                        updateProgressNotification("Compressing video (${percent.toInt()}%)", percent.roundToInt(), 100)
                    }

                    override fun onSuccess(index: Int, size: Long, path: String?) {
                        val callbackFile = path?.let(::File)
                        val compressedFile = when {
                            callbackFile != null && callbackFile.exists() && callbackFile.length() > 0L -> callbackFile
                            expectedOutputFile.exists() && expectedOutputFile.length() > 0L -> expectedOutputFile
                            else -> null
                        }
                        completeOnce(compressedFile)
                    }

                    override fun onFailure(index: Int, failureMessage: String) {
                        Log.w("FileUploadWorker", "Video compression failed: $failureMessage.")
                        completeOnce(null)
                    }

                    override fun onCancelled(index: Int) {
                        Log.w("FileUploadWorker", "Video compression cancelled.")
                        completeOnce(null)
                    }
                }
            )

            continuation.invokeOnCancellation {
                try {
                    VideoCompressor.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun isVideoFile(file: File, mimeType: String): Boolean {
        if (mimeType.startsWith("video")) return true
        val extension = file.extension.lowercase()
        if (extension in setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")) return true

        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            retriever.release()
            hasVideo == "yes"
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeVideoMimeType(mimeType: String): String {
        return if (mimeType.startsWith("video")) mimeType else "video/mp4"
    }

    private fun cleanupTempFile(filePath: String, uploadId: String) {
        try {
            File(filePath).delete()
            File(applicationContext.filesDir, "pending_uploads/upload_$uploadId.meta").delete()
            File(applicationContext.filesDir, "compressed_uploads/compressed_$uploadId.jpg").delete()
            File(applicationContext.filesDir, "compressed_uploads/compressed_$uploadId.webp").delete()
            File(applicationContext.filesDir, "compressed_uploads/compressed_$uploadId.png").delete()
            File(applicationContext.filesDir, "compressed_uploads/compressed_$uploadId.mp4").delete()
        } catch (_: Exception) { }
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
            .setSmallIcon(R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun updateProgressNotification(text: String, progress: Int, max: Int) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Station Ops Upload")
            .setContentText(text)
            .setSmallIcon(R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun showRetryingNotification() {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Upload paused")
            .setContentText("Waiting to reconnect and retry…")
            .setSmallIcon(R.drawable.stat_sys_upload)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun showCompletionNotification(totalFiles: Int) {
        notificationManager.cancel(notificationId)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Upload Complete")
            .setContentText("File uploaded successfully")
            .setSmallIcon(R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId + 1, notification)
    }

    private fun showFailureNotification(error: String) {
        notificationManager.cancel(notificationId)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Upload Notice")
            .setContentText(error)
            .setSmallIcon(R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId + 2, notification)
    }
}