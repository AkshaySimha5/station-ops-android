package com.example.stationops.data.model

import com.google.firebase.firestore.PropertyName

data class Station(
    val id: String = "",
    val name: String = "",
    @get:PropertyName("isUploadEnabled")
    val isUploadEnabled: Boolean = true,
    val createdBy: String = ""
)