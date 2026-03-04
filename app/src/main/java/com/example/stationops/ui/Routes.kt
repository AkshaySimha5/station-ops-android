package com.example.stationops.ui

/**
 * Centralized navigation route definitions.
 * Avoids scattered hardcoded route strings throughout the codebase.
 */
object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard/{role}"
    const val DETAILS = "details/{stationId}/{role}/{stationName}"
    const val GROUP_DETAIL = "group/{groupId}/{groupName}"

    fun dashboard(role: String) = "dashboard/$role"

    fun details(stationId: String, role: String, stationName: String): String {
        val safeName = android.net.Uri.encode(stationName)
        return "details/$stationId/$role/$safeName"
    }

    fun groupDetail(groupId: String, groupName: String): String {
        val safeName = android.net.Uri.encode(groupName)
        return "group/$groupId/$safeName"
    }
}
