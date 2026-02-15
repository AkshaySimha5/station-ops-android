package com.example.stationops.data.model

import com.google.firebase.Timestamp

data class Upload(
    val id: String = "",
    val url: String = "",
    val type: String = "image",
    val uploaderId: String = "",
    val stationId: String = "",
    val timestamp: Timestamp = Timestamp.now()
)