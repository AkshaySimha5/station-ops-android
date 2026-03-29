package com.example.stationops.ui.station_detail

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.stationops.data.model.Upload
import com.google.firebase.Timestamp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import com.google.firebase.storage.FirebaseStorage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StationDetailScreen(
    viewModel: StationDetailViewModel,
    stationId: String,
    isAdmin: Boolean,
    stationName: String
) {
    val groupedUploads by viewModel.groupedUploads
    val context = LocalContext.current
    var fileToDelete by remember { mutableStateOf<Upload?>(null) }
    var selectedImageUrl by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<Upload?>(null) }

    val employeeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFilesInBackground(context, uris, stationId, isAdmin)
        }
    }

    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            viewModel.uploadFilesInBackground(context, listOf(tempCameraUri!!), stationId, isAdmin)
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success && tempCameraUri != null) {
            viewModel.uploadFilesInBackground(context, listOf(tempCameraUri!!), stationId, isAdmin)
        }
    }

    var showCaptureDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = createImageUri(context)
            tempCameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // Request notification permission on Android 13+ so upload progress shows
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* upload works regardless; notification just won't appear if denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotifPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        viewModel.loadUploads(stationId, isAdmin)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stationName,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        },
        floatingActionButton = {
            if (!isAdmin) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FloatingActionButton(
                        onClick = { showCaptureDialog = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.PhotoCamera, "Take Photo or Video")
                    }

                    FloatingActionButton(
                        onClick = { employeeLauncher.launch(arrayOf("image/*", "video/*")) }
                    ) {
                        Icon(Icons.Default.Add, "Upload from Gallery")
                    }
                }
            }

            if (showCaptureDialog) {
                AlertDialog(
                    onDismissRequest = { showCaptureDialog = false },
                    title = { Text("Choose Mode") },
                    text = { Text("Do you want to take a photo or record a video?") },
                    confirmButton = {
                        TextButton(onClick = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                val uri = createImageUri(context)
                                tempCameraUri = uri
                                cameraLauncher.launch(uri)
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            showCaptureDialog = false
                        }) { Text("Photo") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                val uri = createVideoUri(context)
                                tempCameraUri = uri
                                videoLauncher.launch(uri)
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            showCaptureDialog = false
                        }) { Text("Video") }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .padding(padding)
            .fillMaxSize()
        ) {
            // File list
            if (groupedUploads.isNotEmpty()) {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
                ) {
                    groupedUploads.forEach { (date, uploads) ->
                        stickyHeader {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 2.dp
                            ) {
                                Text(
                                    text = date,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        item {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                uploads.chunked(3).forEach { rowItems ->
                                    Row(Modifier.fillMaxWidth()) {
                                        rowItems.forEach { file ->
                                            FileItemView(
                                                file = file,
                                                context = context,
                                                isAdmin = isAdmin,
                                                stationName = stationName,
                                                onImageClick = { url -> selectedImageUrl = url },
                                                onDeleteClick = { clickedFile -> fileToDelete = clickedFile },
                                                onDownloadClick = { clickedFile ->
                                                    if (isFileAlreadyDownloaded(clickedFile, stationName)) {
                                                        pendingDownload = clickedFile
                                                    } else {
                                                        downloadFile(context, clickedFile.url, stationName, clickedFile.timestamp, clickedFile.type)
                                                    }
                                                }
                                            )
                                        }
                                        if (rowItems.size < 3) {
                                            Spacer(Modifier.weight((3 - rowItems.size).toFloat()))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!viewModel.isLoading.value && !viewModel.isUploading.value) {
                EmptyStateView(
                    message = if (isAdmin) "No files uploaded for this station yet."
                    else "No uploads found for today.\nTap + to add one."
                )
            }

            // Loading overlay
            if (viewModel.isLoading.value) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Upload progress overlay
            if (viewModel.isUploading.value) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = viewModel.uploadStatusText.value,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { viewModel.uploadProgress.value },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${(viewModel.uploadProgress.value * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Full screen image viewer
            selectedImageUrl?.let { url ->
                Dialog(
                    onDismissRequest = { selectedImageUrl = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    FullScreenImageViewer(url = url, onDismiss = { selectedImageUrl = null })
                }
            }

            // Download exists confirmation dialog
            if (pendingDownload != null) {
                AlertDialog(
                    onDismissRequest = { pendingDownload = null },
                    title = { Text("File Already Downloaded") },
                    text = { Text("This file already exists locally. Download again?") },
                    confirmButton = {
                        Button(onClick = {
                            pendingDownload?.let { dl ->
                                downloadFile(context, dl.url, stationName, dl.timestamp, dl.type)
                            }
                            pendingDownload = null
                        }) { Text("Yes") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDownload = null }) { Text("No") }
                    }
                )
            }

            // Delete confirmation dialog
            if (fileToDelete != null) {
                AlertDialog(
                    onDismissRequest = { fileToDelete = null },
                    icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    title = { Text("Delete File?") },
                    text = { Text("This action cannot be undone.") },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteFile(fileToDelete!!, stationId, isAdmin)
                                fileToDelete = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

@Composable
fun RowScope.FileItemView(
    file: Upload,
    context: Context,
    isAdmin: Boolean,
    stationName: String,
    onImageClick: (String) -> Unit,
    onDeleteClick: (Upload) -> Unit,
    onDownloadClick: (Upload) -> Unit = {}
) {
    Card(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .padding(4.dp)
            .clickable {
                if (file.type.startsWith("image")) {
                    onImageClick(file.url)
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(file.url), file.type)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Resolve the thumbnail URL: prefer previewUrl, fall back to full url
            val thumbnailUrl = file.previewUrl.ifEmpty { file.url }
            val isImage = file.type.startsWith("image")
            val isVideo = file.type.startsWith("video")

            when {
                isImage -> {
                    AsyncImage(
                        model = if (isAdmin) {
                            // Admin: never load full media in grid – always preview
                            file.previewUrl.ifEmpty { file.url }
                        } else {
                            // Employee: use preview for fast load, full on tap is unchanged
                            thumbnailUrl
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                isVideo -> {
                    // Show JPEG preview frame for video (if available), with play overlay
                    if (thumbnailUrl.isNotEmpty() && thumbnailUrl != file.url) {
                        // We have a real preview thumbnail
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Play icon overlay
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(48.dp)
                                    .align(Alignment.Center)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    .padding(8.dp)
                            )
                        }
                    } else {
                        // Fallback: plain dark background with play icon (existing behavior)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.DarkGray),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = "Document",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isAdmin) {
                // Download button (top-right) – only if upload is complete
                if (file.uploadStatus == "COMPLETED" && file.url.isNotEmpty()) {
                    IconButton(
                        onClick = { onDownloadClick(file) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(Color.Black.copy(alpha = 0.4f), shape = CircleShape)
                            .size(32.dp)
                            .padding(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            tint = Color.White
                        )
                    }
                }
                // Delete button (bottom-right)
                IconButton(
                    onClick = { onDeleteClick(file) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.White.copy(alpha = 0.7f), CircleShape)
                        .size(32.dp)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.Red
                    )
                }
            }

            // Upload-in-progress indicator (shown on both sides)
            if (file.uploadStatus != "COMPLETED") {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (file.uploadStatus == "FAILED") "Failed" else "Uploading…",
                        color = if (file.uploadStatus == "FAILED") Color(0xFFFF6B6B) else Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun FullScreenImageViewer(url: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = url,
            contentDescription = "Full Screen Image",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit
        )

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }
    }
}

@Composable
fun EmptyStateView(
    message: String,
    icon: ImageVector = Icons.Default.FolderOff
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

private fun isFileAlreadyDownloaded(upload: Upload, stationName: String): Boolean {
    val date = upload.timestamp.toDate()
    val format = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
    val dateString = format.format(date)
    val safeStationName = stationName.replace(" ", "_")
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(upload.type) ?: "bin"
    val finalFileName = "${safeStationName}_${dateString}.$extension"
    val folderPath = "Work_Photos_Videos/$safeStationName"
    val file = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "$folderPath/$finalFileName"
    )
    return file.exists()
}

private fun downloadFile(
    context: Context,
    url: String,
    stationName: String,
    timestamp: Timestamp,
    mimeType: String
) {
    fun enqueueWithMime(resolvedMime: String) {
        try {
            val date = timestamp.toDate()
            val format = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
            val dateString = format.format(date)
            val safeStationName = stationName.replace(" ", "_")
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime) ?: "bin"
            val finalFileName = "${safeStationName}_${dateString}.$extension"
            val folderPath = "Work_Photos_Videos/$safeStationName"

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(finalFileName)
                .setDescription("Downloading file from Station Ops...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$folderPath/$finalFileName")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)

            Toast.makeText(context, "Downloading $finalFileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Download Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // If the stored mimeType looks like a full MIME (contains '/'), use it.
    if (mimeType.contains("/")) {
        enqueueWithMime(mimeType)
        return
    }

    // Otherwise try to fetch the content type from Firebase Storage metadata.
    try {
        val storage = FirebaseStorage.getInstance()
        val ref = storage.getReferenceFromUrl(url)
        ref.metadata.addOnSuccessListener { metadata ->
            val ct = metadata.contentType ?: mimeType
            enqueueWithMime(ct)
        }.addOnFailureListener {
            // Fallback to the provided mimeType (will likely become .bin)
            enqueueWithMime(mimeType)
        }
    } catch (e: Exception) {
        // If anything goes wrong, fall back to provided mimeType
        enqueueWithMime(mimeType)
    }
}

fun createImageUri(context: Context): Uri {
    val directory = File(context.cacheDir, "images")
    directory.mkdirs()
    val file = File.createTempFile("selected_image_", ".jpg", directory)
    val authority = "${context.packageName}.fileprovider"
    return FileProvider.getUriForFile(context, authority, file)
}

fun createVideoUri(context: Context): Uri {
    val directory = File(context.cacheDir, "videos")
    directory.mkdirs()
    val file = File.createTempFile("selected_video_", ".mp4", directory)
    val authority = "${context.packageName}.fileprovider"
    return FileProvider.getUriForFile(context, authority, file)
}