package com.example.stationops.ui.dashboard

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stationops.data.model.Station
import com.example.stationops.data.repository.AuthRepository
import com.example.stationops.data.repository.StationRepository
import kotlinx.coroutines.launch

class DashboardViewModel : ViewModel() {
    private val stationRepo = StationRepository()
    private val authRepo = AuthRepository()

    var stations = mutableStateOf<List<Station>>(emptyList())
    var isLoading = mutableStateOf(false)

    var errorMessage = mutableStateOf<String?>(null)
    var isRefreshing = mutableStateOf(false)

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