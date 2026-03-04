package com.example.stationops.ui.dashboard

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stationops.data.model.Station
import com.example.stationops.data.repository.AuthRepository
import com.example.stationops.data.repository.GroupRepository
import com.example.stationops.data.repository.StationRepository
import kotlinx.coroutines.launch

enum class StationFilter(val label: String) {
    ALL("All Stations"),
    ACTIVE("Active Only"),
    LOCKED("Locked Only")
}

class DashboardViewModel : ViewModel() {
    private val stationRepo = StationRepository()
    private val authRepo = AuthRepository()
    private val groupRepo = GroupRepository()

    var stations = mutableStateOf<List<Station>>(emptyList())
    var isLoading = mutableStateOf(false)

    var errorMessage = mutableStateOf<String?>(null)
    var isRefreshing = mutableStateOf(false)

    var stationFilter = mutableStateOf(StationFilter.ALL)

    val filteredStations: List<Station>
        get() = when (stationFilter.value) {
            StationFilter.ALL -> stations.value
            StationFilter.ACTIVE -> stations.value.filter { it.isUploadEnabled }
            StationFilter.LOCKED -> stations.value.filter { !it.isUploadEnabled }
        }.sortedBy { it.name.lowercase() }

    fun setFilter(filter: StationFilter) {
        stationFilter.value = filter
    }

    fun loadStations(isAdmin: Boolean) {
        viewModelScope.launch {
            if (!isRefreshing.value) {
                isLoading.value = true
            }
            try {
                stations.value = stationRepo.getStations(isAdmin)
            } catch (e: Exception) {
                errorMessage.value = "Error loading: ${e.message}"
                Log.e("DashboardVM", "Load Error", e)
            } finally {
                isLoading.value = false
                isRefreshing.value = false
            }
        }
    }

    fun refreshStations(isAdmin: Boolean) {
        isRefreshing.value = true
        loadStations(isAdmin)
    }

    fun addStation(name: String) {
        val adminId = authRepo.getCurrentUserId() ?: return
        viewModelScope.launch {
            try {
                stationRepo.addStation(name, adminId)
                loadStations(true)
            } catch (e: Exception) {
                errorMessage.value = "Could not add station: ${e.message}"
                Log.e("DashboardVM", "Add Error", e)
            }
        }
    }

    fun toggleStation(station: Station) {
        viewModelScope.launch {
            try {
                val updatedList = stations.value.map {
                    if (it.id == station.id) it.copy(isUploadEnabled = !it.isUploadEnabled) else it
                }
                stations.value = updatedList
                stationRepo.toggleStationStatus(station.id, !station.isUploadEnabled)
            } catch (e: Exception) {
                loadStations(true)
                errorMessage.value = "Update failed: ${e.message}"
                Log.e("DashboardVM", "Toggle Error", e)
            }
        }
    }

    fun deleteStation(stationId: String) {
        viewModelScope.launch {
            try {
                stationRepo.deleteStation(stationId)
                groupRepo.removeStationFromAllGroups(stationId)
                loadStations(true)
            } catch (e: Exception) {
                errorMessage.value = "Delete failed: ${e.message}"
                Log.e("DashboardVM", "Delete Error", e)
            }
        }
    }

    fun logoutUser() {
        authRepo.logout()
    }
}