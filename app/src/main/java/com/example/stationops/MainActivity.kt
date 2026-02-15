package com.example.stationops

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.stationops.ui.Routes
import com.example.stationops.ui.dashboard.DashboardScreen
import com.example.stationops.ui.dashboard.DashboardViewModel
import com.example.stationops.ui.login.LoginScreen
import com.example.stationops.ui.login.LoginViewModel
import com.example.stationops.ui.station_detail.StationDetailScreen
import com.example.stationops.ui.station_detail.StationDetailViewModel
import com.example.stationops.ui.theme.StationOpsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StationOpsTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = Routes.LOGIN) {

                    composable(Routes.LOGIN) {
                        val vm = viewModel<LoginViewModel>()
                        LoginScreen(viewModel = vm) { role ->
                            navController.navigate(Routes.dashboard(role)) {
                                popUpTo(Routes.LOGIN) { inclusive = true }
                            }
                        }
                    }

                    composable(Routes.DASHBOARD) { backStackEntry ->
                        val role = backStackEntry.arguments?.getString("role") ?: "employee"
                        val isAdmin = (role == "admin")
                        val vm = viewModel<DashboardViewModel>()

                        DashboardScreen(
                            viewModel = vm,
                            isAdmin = isAdmin,
                            onStationClick = { stationId, stationName ->
                                navController.navigate(Routes.details(stationId, role, stationName))
                            },
                            onLogout = {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(0)
                                }
                            }
                        )
                    }

                    composable(Routes.DETAILS) { backStackEntry ->
                        val stationId = backStackEntry.arguments?.getString("stationId") ?: ""
                        val role = backStackEntry.arguments?.getString("role") ?: "employee"
                        val stationName = backStackEntry.arguments?.getString("stationName") ?: "Station"
                        val vm = viewModel<StationDetailViewModel>()

                        StationDetailScreen(
                            viewModel = vm,
                            stationId = stationId,
                            isAdmin = (role == "admin"),
                            stationName = stationName
                        )
                    }
                }
            }
        }
    }
}