package com.example.stationops.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    isAdmin: Boolean,
    onStationClick: (String, String) -> Unit,
    onLogout: () -> Unit
) {
    val stations by viewModel.stations
    val isRefreshing by viewModel.isRefreshing

    var showAddDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var stationToDelete by remember { mutableStateOf<Station?>(null) }

    val pullState = rememberPullToRefreshState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) {
        viewModel.loadStations(isAdmin)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = if (isAdmin) "Admin Dashboard" else "Stations",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    if (isAdmin) {
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Station")
                        }
                    }
                    IconButton(onClick = { showLogoutDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Logout")
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
            onRefresh = { viewModel.refreshStations(isAdmin) },
            state = pullState
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
            ) {
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
                            // Station icon
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
                                if (isAdmin) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (station.isUploadEnabled) Icons.Default.CheckCircle else Icons.Default.Lock,
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
                            }

                            if (isAdmin) {
                                IconButton(onClick = { stationToDelete = station }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete Station",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }

                                Switch(
                                    checked = station.isUploadEnabled,
                                    onCheckedChange = { viewModel.toggleStation(station) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add Station Dialog
        if (showAddDialog) {
            var newName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add Station") },
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
                                viewModel.addStation(newName.trim())
                                showAddDialog = false
                            }
                        },
                        enabled = newName.isNotBlank()
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Logout Dialog
        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text("Logout") },
                text = { Text("Are you sure you want to log out?") },
                confirmButton = {
                    Button(onClick = {
                        showLogoutDialog = false
                        viewModel.logoutUser()
                        onLogout()
                    }) { Text("Logout") }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Delete Confirmation Dialog
        if (stationToDelete != null) {
            AlertDialog(
                onDismissRequest = { stationToDelete = null },
                icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                title = { Text("Delete Station?") },
                text = {
                    Text(
                        "Warning: This will permanently remove '${stationToDelete?.name}' from the online database.\n\n" +
                                "This action cannot be undone. Please verify that you have downloaded any necessary files locally before proceeding."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            stationToDelete?.let { viewModel.deleteStation(it.id) }
                            stationToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete Permanently")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { stationToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}