package com.example.stationops.data.model

import com.google.firebase.Timestamp

data class Upload(
    val id: String = "",
    val url: String = "",
    val previewUrl: String = "",
    val type: String = "image",
    val uploadStatus: String = "COMPLETED",
    val uploaderId: String = "",
    val stationId: String = "",
    val timestamp: Timestamp = Timestamp.now(),
    val failedAt: Timestamp? = null  
)