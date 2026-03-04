package com.example.stationops.data.model

data class StationGroup(
    val id: String = "",
    val name: String = "",
    val stationIds: List<String> = emptyList(),
    val createdBy: String = ""
)
