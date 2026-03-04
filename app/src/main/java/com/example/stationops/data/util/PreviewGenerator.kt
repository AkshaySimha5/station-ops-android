package com.example.stationops.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Generates lightweight JPEG preview thumbnails for images and videos.
 * All work is CPU-bound and must be called off the UI thread.
 */
object PreviewGenerator {

    private const val TAG = "PreviewGenerator"
    private const val TARGET_WIDTH = 480       // px – middle of the 300-600 range
    private const val JPEG_QUALITY = 70        // percent

    /**
     * Creates a JPEG thumbnail for the given source file.
     *
     * @param context     Application context (needed for nothing right now but kept for future use)
     * @param sourceFile  The original image or video file on disk
     * @param mimeType    MIME type of the source (e.g. "image/jpeg", "video/mp4")
     * @return A temp [File] containing the JPEG preview, or `null` if generation failed.
     */
    fun generatePreview(context: Context, sourceFile: File, mimeType: String): File? {
        return try {
            val bitmap = when {
                mimeType.startsWith("image") -> generateImageThumbnail(sourceFile)
                mimeType.startsWith("video") -> generateVideoThumbnail(sourceFile)
                else -> null
            } ?: return null

            val previewFile = File(context.cacheDir, "preview_${System.nanoTime()}.jpg")
            FileOutputStream(previewFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
            previewFile
        } catch (e: Exception) {
            Log.e(TAG, "Preview generation failed: ${e.message}", e)
            null
        }
    }

    // ── Image thumbnail ─────────────────────────────────────────────────────────

    private fun generateImageThumbnail(sourceFile: File): Bitmap? {
        // First pass: decode bounds only
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, options)

        val origWidth = options.outWidth
        val origHeight = options.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null

        // Calculate inSampleSize for rough down-sampling
        var sampleSize = 1
        while (origWidth / sampleSize > TARGET_WIDTH * 2) {
            sampleSize *= 2
        }

        // Second pass: decode with sample size
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions) ?: return null

        // Fine-scale to exact target width
        val scale = TARGET_WIDTH.toFloat() / sampled.width
        val targetHeight = (sampled.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(sampled, TARGET_WIDTH, targetHeight, true)
        if (scaled !== sampled) sampled.recycle()

        return scaled
    }

    // ── Video thumbnail ─────────────────────────────────────────────────────────

    private fun generateVideoThumbnail(sourceFile: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(sourceFile.absolutePath)
            // Grab frame at ~1 second (1_000_000 µs)
            val frame = retriever.getFrameAtTime(
                1_000_000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0) // fallback to first frame
            frame?.let { scaleBitmap(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Video thumbnail extraction failed: ${e.message}", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= TARGET_WIDTH) return bitmap
        val scale = TARGET_WIDTH.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, targetHeight, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
