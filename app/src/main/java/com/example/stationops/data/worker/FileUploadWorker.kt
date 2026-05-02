package com.example.stationops.data.worker

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
import com.example.stationops.data.util.PreviewGenerator
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

class FileUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "upload_channel"
        const val KEY_FILE_PATHS = "file_paths"
        const val KEY_MIME_TYPES = "mime_types"
        const val KEY_STATION_ID = "station_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_IS_ADMIN = "is_admin"
        const val MAX_RETRY_ATTEMPTS = 3
    }

    // Derive a unique notification ID from the stationId so that concurrent workers
    // for different stations each own a separate notification slot and cannot stomp
    // on each other's progress, completion, or failure notifications.
    // .and(0x7FFFFFFF) strips the sign bit so the result is always a positive Int,
    // which is required by NotificationManager.
    // Offsets:  +0 = progress (ongoing), +1 = completion, +2 = failure
    private val notificationId: Int by lazy {
        (inputData.getString(KEY_STATION_ID) ?: "default")
            .hashCode()
            .and(0x7FFFFFFF)
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
            // Auth failures are permanent – do not retry
            return Result.failure()
        }

        val storage = FirebaseStorage.getInstance()
        val db = FirebaseFirestore.getInstance()
        val totalFiles = filePaths.size

        // FIX 1: Renamed from createdDocIds → pendingDocIds.
        // IDs are removed from this list as soon as each doc reaches COMPLETED,
        // so the failure handler never overwrites already-finished uploads.
        val pendingDocIds = mutableListOf<String>()
        val tempGeneratedFiles = mutableListOf<String>()

        return try {
            setForeground(createForegroundInfo("Preparing upload…", 0, totalFiles * 100))

            filePaths.forEachIndexed { index, path ->
                val sourceFile = File(path)
                val mimeType = mimeTypes.getOrElse(index) { "application/octet-stream" }
                val mediaUUID = UUID.randomUUID().toString()

                // BUG 2 FIX: Validate the temp file before touching anything else.
                // The file can be missing (OS evicted cache under storage pressure) or
                // zero-byte (openInputStream returned null during copy in the ViewModel).
                // Catching this here — before the Firestore doc is created — means there
                // is no orphaned UPLOADING record to clean up; the failure is clean.
                if (!sourceFile.exists()) {
                    throw IOException(
                        "Temp file missing for file ${index + 1}/$totalFiles — " +
                            "likely evicted from cache before worker ran. path=$path"
                    )
                }
                if (sourceFile.length() == 0L) {
                    throw IOException(
                        "Temp file is zero bytes for file ${index + 1}/$totalFiles — " +
                            "content URI stream was null or empty during copy. path=$path"
                    )
                }

                val isVideo = isVideoFile(sourceFile, mimeType)

                val uploadFile = if (isVideo) {
                    val result = compressVideoIfNeeded(sourceFile, mediaUUID, index, totalFiles)
                    if (result != null && result.absolutePath != sourceFile.absolutePath) {
                        tempGeneratedFiles.add(result.absolutePath)
                    }
                    result ?: sourceFile
                } else {
                    sourceFile
                }
                Log.i(
                    "FileUploadWorker",
                    "Upload candidate selected for file ${index + 1}/$totalFiles. " +
                        "source=${sourceFile.length()} bytes, upload=${uploadFile.length()} bytes, " +
                        "path=${uploadFile.absolutePath}"
                )

                val uploadMimeType = if (isVideo && uploadFile.absolutePath != sourceFile.absolutePath) {
                    "video/mp4"
                } else if (isVideo) {
                    normalizeVideoMimeType(mimeType)
                } else {
                    mimeType
                }

                // ── 1. Generate preview locally ─────────────────────────────
                val previewFile = PreviewGenerator.generatePreview(
                    applicationContext, uploadFile, uploadMimeType
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

                // ── 3. Save Firestore doc with preview URL + UPLOADING status ─
                val docData = hashMapOf(
                    "url" to "",                 // will be filled after full upload
                    "previewUrl" to previewUrl,
                    "type" to uploadMimeType,
                    "uploadStatus" to "UPLOADING",
                    "uploaderId" to userId,
                    "stationId" to stationId,
                    "timestamp" to Timestamp.now(),
                    "failedAt" to null
                )
                val docRef = db.collection("uploads").add(docData).await()
                // Track as pending until confirmed COMPLETED below
                pendingDocIds.add(docRef.id)

                // ── 4. Upload full media ────────────────────────────────────
                val storagePath = if (isAdmin) {
                    "stations/$stationId/admin_docs/$mediaUUID"
                } else {
                    "stations/$stationId/employee_uploads/$userId/$mediaUUID"
                }

                val storageRef = storage.reference.child(storagePath)
                val metadata = StorageMetadata.Builder()
                    .setContentType(uploadMimeType)
                    .build()
                val uploadTask = storageRef.putFile(Uri.fromFile(uploadFile), metadata)

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

                // FIX 1: Remove from pending list now that this doc is COMPLETED.
                // If an exception is thrown by any subsequent file, the failure
                // handler will not touch this doc.
                pendingDocIds.remove(docRef.id)
                Log.i("FileUploadWorker", "File ${index + 1}/$totalFiles completed. Doc ${docRef.id} removed from pending list.")
            }

            showCompletionNotification(totalFiles)
            cleanupTempFiles(tempGeneratedFiles.toTypedArray())
            cleanupTempFiles(filePaths)
            Result.success()

        } catch (e: Exception) {
            Log.e("FileUploadWorker", "Upload failed: ${e.message}", e)

            // FIX 1: Only marks genuinely incomplete docs (completed ones were
            // already removed from pendingDocIds above).
            Log.w("FileUploadWorker", "Marking ${pendingDocIds.size} pending docs as FAILED")
            markDocumentsAsFailed(db, pendingDocIds)

            showFailureNotification(e.message ?: "Unknown error")
            cleanupTempFiles(tempGeneratedFiles.toTypedArray())

            // FIX 2: Distinguish transient vs permanent failures.
            // On transient errors we return Result.retry() and keep the source
            // files on disk so the next attempt can re-read them.
            // On permanent errors we clean up and return Result.failure().
            return if (isTransientError(e) && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Log.w(
                    "FileUploadWorker",
                    "Transient failure on attempt $runAttemptCount – scheduling retry. error=${e.message}"
                )
                // Do NOT delete filePaths here; the retry attempt needs them.
                Result.retry()
            } else {
                Log.e(
                    "FileUploadWorker",
                    "Permanent failure or max retries ($MAX_RETRY_ATTEMPTS) reached – giving up."
                )
                cleanupTempFiles(filePaths)
                Result.failure()
            }
        }
    }

    // ── FIX 2: Classifies whether an exception is worth retrying ────────────────
    // Transient = network blips, server overload, temporary Firebase unavailability.
    // Permanent = auth errors, bad data, permission denied, etc.
    private fun isTransientError(e: Exception): Boolean = when (e) {
        is IOException -> {
            // Covers SocketTimeoutException, UnknownHostException, etc.
            true
        }
        is StorageException -> {
            // StorageException.ERROR_NOT_AUTHENTICATED is a permanent auth failure.
            // Everything else (server errors, network timeouts) is considered transient.
            e.errorCode != StorageException.ERROR_NOT_AUTHENTICATED
        }
        is FirebaseFirestoreException -> {
            when (e.code) {
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> true
                // PERMISSION_DENIED, NOT_FOUND, INVALID_ARGUMENT, etc. are permanent
                else -> false
            }
        }
        else -> false
    }

    private suspend fun compressVideoIfNeeded(
        sourceFile: File,
        mediaUUID: String,
        fileIndex: Int,
        totalFiles: Int
    ): File? {
        val sourceSizeMb = sourceFile.length() / (1024f * 1024f)
        if (sourceSizeMb <= 30f) {
            return sourceFile
        }

        val outputName = "compressed_$mediaUUID.mp4"
        val compressionOutputDir = File(applicationContext.filesDir, "compressed_uploads")
        if (!compressionOutputDir.exists()) {
            compressionOutputDir.mkdirs()
        }
        Log.i(
            "FileUploadWorker",
            "Compression output dir: ${compressionOutputDir.absolutePath}, writable=${compressionOutputDir.canWrite()}"
        )

        val firstQuality = when {
            sourceSizeMb >= 100f -> VideoQuality.LOW
            sourceSizeMb >= 70f -> VideoQuality.MEDIUM
            else -> VideoQuality.MEDIUM
        }

        val firstAttempt = compressVideoOnce(
            sourceFile = sourceFile,
            outputName = outputName,
            quality = firstQuality,
            fileIndex = fileIndex,
            totalFiles = totalFiles
        )

        if (firstAttempt != null && firstAttempt.exists() && firstAttempt.length() in 1 until sourceFile.length()) {
            return firstAttempt
        }

        Log.w(
            "FileUploadWorker",
            "First compression pass did not reduce size (source=${sourceFile.length()}, " +
                "compressed=${firstAttempt?.length() ?: -1}). Retrying with VERY_LOW."
        )

        val secondAttempt = compressVideoOnce(
            sourceFile = sourceFile,
            outputName = "compressed_retry_$mediaUUID.mp4",
            quality = VideoQuality.VERY_LOW,
            fileIndex = fileIndex,
            totalFiles = totalFiles
        )

        val bestCompressed = listOfNotNull(firstAttempt, secondAttempt)
            .filter { it.exists() && it.length() > 0L }
            .minByOrNull { it.length() }

        if (bestCompressed == null) {
            Log.w("FileUploadWorker", "Compression failed in all passes. Uploading original.")
            return sourceFile
        }

        if (bestCompressed.length() >= sourceFile.length()) {
            Log.w(
                "FileUploadWorker",
                "Compression completed but file is not smaller " +
                    "(source=${sourceFile.length()}, best=${bestCompressed.length()}). " +
                    "Uploading compressed anyway for verification."
            )
        }

        return bestCompressed
    }

    private suspend fun compressVideoOnce(
        sourceFile: File,
        outputName: String,
        quality: VideoQuality,
        fileIndex: Int,
        totalFiles: Int
    ): File? {
        val expectedOutputFile = File(applicationContext.filesDir, "compressed_uploads/$outputName")
        Log.i(
            "FileUploadWorker",
            "Compression expected output: ${expectedOutputFile.absolutePath}, " +
                "exists=${expectedOutputFile.exists()}, size=${expectedOutputFile.length()}"
        )

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
                        updateProgressNotification(
                            "Compressing video ${fileIndex + 1} of $totalFiles...",
                            fileIndex * 100,
                            totalFiles * 100
                        )
                    }

                    override fun onProgress(index: Int, percent: Float) {
                        val overallProgress = fileIndex * 100 + percent.roundToInt().coerceIn(0, 100)
                        updateProgressNotification(
                            "Compressing video ${fileIndex + 1} of $totalFiles (${percent.toInt()}%)",
                            overallProgress,
                            totalFiles * 100
                        )
                    }

                    override fun onSuccess(index: Int, size: Long, path: String?) {
                        val callbackFile = path?.let(::File)
                        val compressedFile = when {
                            callbackFile != null && callbackFile.exists() && callbackFile.length() > 0L -> callbackFile
                            expectedOutputFile.exists() && expectedOutputFile.length() > 0L -> expectedOutputFile
                            else -> null
                        }

                        if (compressedFile == null) {
                            Log.w(
                                "FileUploadWorker",
                                "Compression returned invalid path and expected output missing/empty. callbackPath=$path"
                            )
                            completeOnce(null)
                            return
                        }
                        if (compressedFile.absolutePath == sourceFile.absolutePath) {
                            Log.w(
                                "FileUploadWorker",
                                "Compressed output path is same as source path: ${compressedFile.absolutePath}"
                            )
                        }
                        if (compressedFile.length() >= sourceFile.length()) {
                            Log.w(
                                "FileUploadWorker",
                                "Compressed file is not smaller " +
                                    "(source=${sourceFile.length()}, compressed=${compressedFile.length()}). " +
                                    "Uploading compressed anyway for verification."
                            )
                        }
                        Log.i(
                            "FileUploadWorker",
                            "Compression ratio: ${compressedFile.length().toFloat() / sourceFile.length().coerceAtLeast(1L)}"
                        )
                        Log.i(
                            "FileUploadWorker",
                            "Video compressed from ${sourceFile.length()} to ${compressedFile.length()} bytes using quality=$quality"
                        )
                        completeOnce(compressedFile)
                    }

                    override fun onFailure(index: Int, failureMessage: String) {
                        Log.w("FileUploadWorker", "Compression failed: $failureMessage. Uploading original.")
                        completeOnce(null)
                    }

                    override fun onCancelled(index: Int) {
                        Log.w("FileUploadWorker", "Compression cancelled. Uploading original.")
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

    private suspend fun markDocumentsAsFailed(db: FirebaseFirestore, docIds: List<String>) {
        // BUG 1 FIX: Retry each doc update up to MAX_MARK_FAILED_ATTEMPTS times.
        // Previously a single Firestore failure here was silently swallowed, leaving
        // the doc stuck in UPLOADING. The reconciler is a backstop after 20 minutes,
        // but retrying immediately closes the gap for transient Firestore errors.
        val MAX_MARK_FAILED_ATTEMPTS = 3

        docIds.forEach { docId ->
            var attempt = 0
            var marked = false
            while (attempt < MAX_MARK_FAILED_ATTEMPTS && !marked) {
                attempt++
                try {
                    db.collection("uploads").document(docId).update(
                        mapOf(
                            "uploadStatus" to "FAILED",
                            "failedAt" to Timestamp.now()
                        )
                    ).await()
                    Log.i("FileUploadWorker", "Marked doc $docId as FAILED (attempt $attempt)")
                    marked = true
                } catch (e: Exception) {
                    if (attempt < MAX_MARK_FAILED_ATTEMPTS) {
                        Log.w(
                            "FileUploadWorker",
                            "Attempt $attempt to mark doc $docId as FAILED failed: ${e.message} — retrying"
                        )
                    } else {
                        // All attempts exhausted. The reconcileStaleUploads sweep in the
                        // repository will catch this doc on the next app launch.
                        Log.e(
                            "FileUploadWorker",
                            "All $MAX_MARK_FAILED_ATTEMPTS attempts to mark doc $docId as FAILED exhausted. " +
                                "Reconciler will clean it up on next launch. error=${e.message}",
                            e
                        )
                    }
                }
            }
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun showCompletionNotification(totalFiles: Int) {
        notificationManager.cancel(notificationId)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Upload Complete")
            .setContentText("$totalFiles file(s) uploaded successfully")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId + 1, notification)
    }

    private fun showFailureNotification(error: String) {
        notificationManager.cancel(notificationId)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Upload Failed")
            .setContentText(error)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId + 2, notification)
    }
}