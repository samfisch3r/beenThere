package com.beenthere.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.beenthere.android.ui.screens.CitiesScreen
import com.beenthere.android.ui.screens.CountriesScreen
import com.beenthere.android.ui.screens.MapScreen
import kotlinx.serialization.Serializable

@Serializable sealed class Screen
@Serializable object MapScreenRoute : Screen()
@Serializable object CitiesScreenRoute : Screen()
@Serializable object CountriesScreenRoute : Screen()

data class NavigationItem<T : Screen>(
    val route: T,
    val label: String,
    val icon: ImageVector,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }

        setContent {
            BeenThereApp()
        }
    }
}

@Composable
fun BeenThereApp() {
    val navController = rememberNavController()
    val viewModel: PlaceViewModel = viewModel()
    
    val navItems = listOf(
        NavigationItem(MapScreenRoute, "Map", Icons.Default.Map),
        NavigationItem(CitiesScreenRoute, "Cities", Icons.Default.LocationCity),
        NavigationItem(CountriesScreenRoute, "Countries", Icons.Default.Public)
    )

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                
                navItems.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any { it.hasRoute(item.route::class) } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = MapScreenRoute) {
            composable<MapScreenRoute> {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    MapScreen(viewModel)
                }
            }
            composable<CitiesScreenRoute> {
                Box(modifier = Modifier.padding(innerPadding)) {
                    CitiesScreen(viewModel)
                }
            }
            composable<CountriesScreenRoute> {
                Box(modifier = Modifier.padding(innerPadding)) {
                    CountriesScreen(viewModel)
                }
            }
        }
    }
}
