package com.example.stationops.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stationops.data.model.Station

/**
 * Full-screen detail view for a single group.
 * Shows the stations that belong to the group with controls to
 * add/remove stations, create new stations, toggle, and delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    viewModel: GroupViewModel,
    groupId: String,
    groupName: String,
    onStationClick: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val stations by viewModel.groupStations
    val isRefreshing by viewModel.isRefreshing

    var showAddExistingDialog by remember { mutableStateOf(false) }
    var showCreateStationDialog by remember { mutableStateOf(false) }
    var stationToDelete by remember { mutableStateOf<Station?>(null) }
    var stationToRemove by remember { mutableStateOf<Station?>(null) }

    val pullState = rememberPullToRefreshState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(groupId) {
        viewModel.loadGroupDetail(groupId)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = groupName,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateStationDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create Station")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        PullToRefreshBox(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshGroupDetail(groupId) },
            state = pullState
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Button to add an existing station
                item {
                    Button(
                        onClick = { showAddExistingDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("+ Add Existing Station")
                    }
                }

                items(stations, key = { it.id }) { station ->
                    Card(
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .fillMaxWidth()
                            .clickable { onStationClick(station.id, station.name) },
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Business,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = if (station.isUploadEnabled)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outline
                            )

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = station.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (station.isUploadEnabled)
                                            Icons.Default.CheckCircle else Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (station.isUploadEnabled)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (station.isUploadEnabled) "Active" else "Locked",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (station.isUploadEnabled)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            // Remove from group
                            IconButton(onClick = { stationToRemove = station }) {
                                Icon(
                                    Icons.Default.RemoveCircleOutline,
                                    contentDescription = "Remove from group",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Delete station permanently
                            IconButton(onClick = { stationToDelete = station }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete Station",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }

                            Switch(
                                checked = station.isUploadEnabled,
                                onCheckedChange = {
                                    viewModel.toggleStation(station, groupId)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Add existing station dialog — shows stations not yet in this group
        if (showAddExistingDialog) {
            val available = viewModel.availableStations
            AlertDialog(
                onDismissRequest = { showAddExistingDialog = false },
                title = { Text("Add Station to Group") },
                text = {
                    if (available.isEmpty()) {
                        Text("All stations are already in this group.")
                    } else {
                        LazyColumn {
                            items(available, key = { it.id }) { station ->
                                Card(
                                    modifier = Modifier
                                        .padding(vertical = 4.dp)
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.addStationToGroup(groupId, station.id)
                                            showAddExistingDialog = false
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Outlined.Business,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = station.name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showAddExistingDialog = false }) { Text("Close") }
                }
            )
        }

        // Create new station dialog
        if (showCreateStationDialog) {
            var newName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreateStationDialog = false },
                title = { Text("Create Station in Group") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Station Name") },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                viewModel.createStationInGroup(newName.trim(), groupId)
                                showCreateStationDialog = false
                            }
                        },
                        enabled = newName.isNotBlank()
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateStationDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Remove station from group confirmation
        if (stationToRemove != null) {
            AlertDialog(
                onDismissRequest = { stationToRemove = null },
                title = { Text("Remove from Group?") },
                text = {
                    Text(
                        "'${stationToRemove?.name}' will be removed from this group but will " +
                                "remain available in the main station list."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        stationToRemove?.let {
                            viewModel.removeStationFromGroup(groupId, it.id)
                        }
                        stationToRemove = null
                    }) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { stationToRemove = null }) { Text("Cancel") }
                }
            )
        }

        // Delete station permanently confirmation
        if (stationToDelete != null) {
            AlertDialog(
                onDismissRequest = { stationToDelete = null },
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                title = { Text("Delete Station?") },
                text = {
                    Text(
                        "Warning: This will permanently remove '${stationToDelete?.name}' " +
                                "from the online database and from all groups.\n\n" +
                                "This action cannot be undone."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            stationToDelete?.let { viewModel.deleteStation(it.id, groupId) }
                            stationToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Delete Permanently") }
                },
                dismissButton = {
                    TextButton(onClick = { stationToDelete = null }) { Text("Cancel") }
                }
            )
        }
    }
}
