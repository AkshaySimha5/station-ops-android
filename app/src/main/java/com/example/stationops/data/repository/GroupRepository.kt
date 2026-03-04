package com.example.stationops.data.repository

import android.util.Log
import com.example.stationops.data.model.StationGroup
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class GroupRepository {
    private val db = FirebaseFirestore.getInstance()
    private val collection = db.collection("station_groups")

    suspend fun getGroups(): List<StationGroup> {
        val snapshot = collection.get().await()
        return snapshot.toObjects(StationGroup::class.java).sortedBy { it.name.lowercase() }
    }

    suspend fun addGroup(name: String, adminId: String): StationGroup {
        val ref = collection.document()
        val group = StationGroup(id = ref.id, name = name, stationIds = emptyList(), createdBy = adminId)
        ref.set(group).await()
        return group
    }

    suspend fun deleteGroup(groupId: String) {
        collection.document(groupId).delete().await()
    }

    suspend fun addStationToGroup(groupId: String, stationId: String) {
        collection.document(groupId)
            .update("stationIds", FieldValue.arrayUnion(stationId))
            .await()
    }

    suspend fun removeStationFromGroup(groupId: String, stationId: String) {
        collection.document(groupId)
            .update("stationIds", FieldValue.arrayRemove(stationId))
            .await()
    }

    suspend fun getGroup(groupId: String): StationGroup? {
        val doc = collection.document(groupId).get().await()
        return doc.toObject(StationGroup::class.java)
    }

    /**
     * Removes a station ID from all groups that contain it.
     * Called when a station is permanently deleted to keep groups consistent.
     */
    suspend fun removeStationFromAllGroups(stationId: String) {
        try {
            val snapshot = collection
                .whereArrayContains("stationIds", stationId)
                .get()
                .await()
            for (doc in snapshot.documents) {
                doc.reference.update("stationIds", FieldValue.arrayRemove(stationId)).await()
            }
        } catch (e: Exception) {
            Log.w("GroupRepo", "Failed to clean station from groups: ${e.message}")
        }
    }
}
