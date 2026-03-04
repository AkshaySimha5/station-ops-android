package com.example.stationops.ui.groups

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stationops.data.model.Station
import com.example.stationops.data.model.StationGroup
import com.example.stationops.data.repository.AuthRepository
import com.example.stationops.data.repository.GroupRepository
import com.example.stationops.data.repository.StationRepository
import kotlinx.coroutines.launch

class GroupViewModel : ViewModel() {
    private val groupRepo = GroupRepository()
    private val stationRepo = StationRepository()
    private val authRepo = AuthRepository()

    var groups = mutableStateOf<List<StationGroup>>(emptyList())
    var isLoading = mutableStateOf(false)
    var isRefreshing = mutableStateOf(false)
    var errorMessage = mutableStateOf<String?>(null)

    // Group detail state
    var selectedGroup = mutableStateOf<StationGroup?>(null)
    var groupStations = mutableStateOf<List<Station>>(emptyList())
    var allStations = mutableStateOf<List<Station>>(emptyList())

    fun loadGroups() {
        viewModelScope.launch {
            if (!isRefreshing.value) {
                isLoading.value = true
            }
            try {
                groups.value = groupRepo.getGroups()
            } catch (e: Exception) {
                errorMessage.value = "Error loading groups: ${e.message}"
                Log.e("GroupVM", "Load groups error", e)
            } finally {
                isLoading.value = false
                isRefreshing.value = false
            }
        }
    }

    fun refreshGroups() {
        isRefreshing.value = true
        loadGroups()
    }

    fun addGroup(name: String) {
        val adminId = authRepo.getCurrentUserId() ?: return
        viewModelScope.launch {
            try {
                groupRepo.addGroup(name, adminId)
                loadGroups()
            } catch (e: Exception) {
                errorMessage.value = "Could not create group: ${e.message}"
                Log.e("GroupVM", "Add group error", e)
            }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            try {
                groupRepo.deleteGroup(groupId)
                loadGroups()
            } catch (e: Exception) {
                errorMessage.value = "Delete group failed: ${e.message}"
                Log.e("GroupVM", "Delete group error", e)
            }
        }
    }

    fun loadGroupDetail(groupId: String) {
        viewModelScope.launch {
            if (!isRefreshing.value) {
                isLoading.value = true
            }
            try {
                val group = groupRepo.getGroup(groupId)
                selectedGroup.value = group
                // Load all stations to support adding
                allStations.value = stationRepo.getStations(true)
                // Filter stations belonging to this group
                if (group != null) {
                    groupStations.value = allStations.value
                        .filter { it.id in group.stationIds }
                        .sortedBy { it.name.lowercase() }
                }
            } catch (e: Exception) {
                errorMessage.value = "Error loading group: ${e.message}"
                Log.e("GroupVM", "Load detail error", e)
            } finally {
                isLoading.value = false
                isRefreshing.value = false
            }
        }
    }

    fun refreshGroupDetail(groupId: String) {
        isRefreshing.value = true
        loadGroupDetail(groupId)
    }

    fun addStationToGroup(groupId: String, stationId: String) {
        viewModelScope.launch {
            try {
                groupRepo.addStationToGroup(groupId, stationId)
                loadGroupDetail(groupId)
            } catch (e: Exception) {
                errorMessage.value = "Could not add station to group: ${e.message}"
                Log.e("GroupVM", "Add station to group error", e)
            }
        }
    }

    fun removeStationFromGroup(groupId: String, stationId: String) {
        viewModelScope.launch {
            try {
                groupRepo.removeStationFromGroup(groupId, stationId)
                loadGroupDetail(groupId)
            } catch (e: Exception) {
                errorMessage.value = "Could not remove station from group: ${e.message}"
                Log.e("GroupVM", "Remove station from group error", e)
            }
        }
    }

    fun toggleStation(station: Station, groupId: String) {
        viewModelScope.launch {
            try {
                // Optimistic update
                groupStations.value = groupStations.value.map {
                    if (it.id == station.id) it.copy(isUploadEnabled = !it.isUploadEnabled) else it
                }
                stationRepo.toggleStationStatus(station.id, !station.isUploadEnabled)
            } catch (e: Exception) {
                loadGroupDetail(groupId)
                errorMessage.value = "Update failed: ${e.message}"
                Log.e("GroupVM", "Toggle error", e)
            }
        }
    }

    fun createStationInGroup(name: String, groupId: String) {
        val adminId = authRepo.getCurrentUserId() ?: return
        viewModelScope.launch {
            try {
                stationRepo.addStation(name, adminId)
                // Get the newly created station (last added, by name match)
                val updated = stationRepo.getStations(true)
                val newStation = updated.find { it.name == name }
                if (newStation != null) {
                    groupRepo.addStationToGroup(groupId, newStation.id)
                }
                loadGroupDetail(groupId)
            } catch (e: Exception) {
                errorMessage.value = "Could not create station: ${e.message}"
                Log.e("GroupVM", "Create station in group error", e)
            }
        }
    }

    fun deleteStation(stationId: String, groupId: String) {
        viewModelScope.launch {
            try {
                stationRepo.deleteStation(stationId)
                groupRepo.removeStationFromAllGroups(stationId)
                loadGroupDetail(groupId)
            } catch (e: Exception) {
                errorMessage.value = "Delete station failed: ${e.message}"
                Log.e("GroupVM", "Delete station error", e)
            }
        }
    }

    /** Stations that exist but are NOT yet in the current group */
    val availableStations: List<Station>
        get() {
            val groupIds = selectedGroup.value?.stationIds ?: emptyList()
            return allStations.value
                .filter { it.id !in groupIds }
                .sortedBy { it.name.lowercase() }
        }
}
