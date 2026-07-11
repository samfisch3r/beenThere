package com.beenthere.android.ui.screens

import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beenthere.android.R
import com.beenthere.android.data.Place
import com.beenthere.android.ui.PlaceViewModel
import com.beenthere.android.ui.models.CountryBoundary
import com.beenthere.android.utils.LocationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: PlaceViewModel) {
    val context = LocalContext.current
    val places by viewModel.allPlaces.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    
    val countryBoundariesState = remember { mutableStateOf<Map<String, CountryBoundary>?>(null) }
    var showAddDialog by remember { mutableStateOf(value = false) }
    var addDialogData by remember { mutableStateOf<Triple<GeoPoint, String?, String?>?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var placeToDelete by remember { mutableStateOf<Place?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(false) }

    val visitedCountries = remember(places) {
        places.asSequence().map { it.countryName }.toSet()
    }

    LaunchedEffect(Unit) {
        org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName
        org.osmdroid.config.Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
        
        val window = (context as? android.app.Activity)?.window
        window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }

        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.assets.open("countries.json")
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                val features = JSONObject(jsonString).getJSONArray("features")
                
                val boundaryMap = mutableMapOf<String, CountryBoundary>()
                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    val props = feature.getJSONObject("properties")
                    val name = props.optString("name").takeIf { it.isNotBlank() } ?: 
                               props.optString("NAME").takeIf { it.isNotBlank() } ?: 
                               props.optString("admin").takeIf { it.isNotBlank() }

                    if (name != null) {
                        val geometry = feature.getJSONObject("geometry")
                        val type = geometry.getString("type")
                        val coordsJson = geometry.getJSONArray("coordinates")
                        val polygons = mutableListOf<List<GeoPoint>>()

                        if (type == "Polygon") {
                            polygons.add(parsePolygon(coordsJson.getJSONArray(0)))
                        } else if (type == "MultiPolygon") {
                            for (j in 0 until coordsJson.length()) {
                                polygons.add(parsePolygon(coordsJson.getJSONArray(j).getJSONArray(0)))
                            }
                        }
                        boundaryMap[name] = CountryBoundary(name, polygons)
                    }
                }
                countryBoundariesState.value = boundaryMap
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        var mapViewRef by remember { mutableStateOf<MapView?>(null) }

        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(5.0)
                    controller.setCenter(GeoPoint(48.8583, 2.2945))
                    
                    addEventsOverlay(
                        mapView = this,
                        onShowDialog = { point, city, country ->
                            addDialogData = Triple(point, city, country)
                            showAddDialog = true
                        },
                        countryBoundariesState = countryBoundariesState,
                    )
                    mapViewRef = this
                }
            },
            update = { view ->
                updateMap(
                    mapView = view, 
                    places = places, 
                    visitedCountries = visitedCountries, 
                    onDeletePlace = { place ->
                        placeToDelete = place
                        showDeleteDialog = true
                    }, 
                    countryBoundaries = countryBoundariesState.value,
                )
            },
            modifier = Modifier.fillMaxSize()
        )

        DockedSearchBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth(),
            expanded = active,
            onExpandedChange = { active = it },
            inputField = {
                SearchBarDefaults.InputField(
                    query = searchQuery,
                    onQueryChange = { 
                        searchQuery = it
                        viewModel.performSearch(it)
                    },
                    onSearch = { active = false },
                    expanded = active,
                    onExpandedChange = { active = it },
                    placeholder = { Text("Search for a place...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (active) {
                            IconButton(onClick = { 
                                searchQuery = ""
                                viewModel.clearSearch()
                            }) {
                                Text("Clear")
                            }
                        }
                    }
                )
            }
        ) {
            searchResults.forEach { feature ->
                ListItem(
                    headlineContent = { Text(feature.toString()) },
                    modifier = Modifier.clickable {
                        val point = GeoPoint(feature.geometry.coordinates[1], feature.geometry.coordinates[0])
                        mapViewRef?.controller?.animateTo(point, 12.0, 1000L)
                        
                        val matchingCountry = LocationUtils.getCountryNameAt(point, countryBoundariesState.value)
                        val finalCountry = matchingCountry ?: feature.properties.country
                        
                        addDialogData = Triple(point, feature.properties.name, finalCountry?.takeIf { it.isNotBlank() })
                        showAddDialog = true
                        
                        active = false
                        searchQuery = ""
                        viewModel.clearSearch()
                    }
                )
            }
        }

        if (showAddDialog && (addDialogData != null)) {
            val (point, city, country) = addDialogData!!
            AddPlaceDialog(
                cityName = city,
                countryName = country,
                onDismiss = { showAddDialog = false },
                onConfirm = { year ->
                    viewModel.insert(
                        Place(
                            cityName = city?.takeIf { it.isNotBlank() } ?: "Unknown",
                            countryName = country?.takeIf { it.isNotBlank() } ?: "Unknown",
                            year = year,
                            latitude = point.latitude,
                            longitude = point.longitude,
                        )
                    )
                    showAddDialog = false
                }
            )
        }

        if (showDeleteDialog && placeToDelete != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete Place") },
                text = { Text("Do you want to remove ${placeToDelete!!.cityName} from your visited places?") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.delete(placeToDelete!!)
                        showDeleteDialog = false
                    }) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun AddPlaceDialog(
    cityName: String?,
    countryName: String?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var year by remember { 
        mutableStateOf(java.util.Calendar.getInstance()[java.util.Calendar.YEAR].toString()) 
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Add Visited Place") },
        text = {
            Column {
                val flag = countryName?.let { LocationUtils.getFlagEmoji(it) } ?: ""
                val locationText = when {
                    !cityName.isNullOrBlank() && !countryName.isNullOrBlank() -> "$cityName, $countryName $flag"
                    !cityName.isNullOrBlank() -> cityName
                    !countryName.isNullOrBlank() -> "Selected location in $countryName $flag"
                    else -> "Selected location"
                }
                Text(
                    text = locationText,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = year,
                    onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) year = it },
                    label = { Text("Year") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(year.toIntOrNull() ?: 2024) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun addEventsOverlay(
    mapView: MapView,
    onShowDialog: (GeoPoint, String?, String?) -> Unit,
    countryBoundariesState: MutableState<Map<String, CountryBoundary>?>
) {
    val receiver = object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
        override fun longPressHelper(p: GeoPoint?): Boolean {
            p?.let { point ->
                val matchingCountry = LocationUtils.getCountryNameAt(point, countryBoundariesState.value)
                onShowDialog(point, null, matchingCountry?.takeIf { it.isNotBlank() }) 
            }
            return true
        }
    }
    mapView.overlays.add(MapEventsOverlay(receiver))
}

private fun updateMap(
    mapView: MapView,
    places: List<Place>,
    visitedCountries: Set<String>,
    onDeletePlace: (Place) -> Unit,
    countryBoundaries: Map<String, CountryBoundary>?
) {
    val eventsOverlay = mapView.overlays.find { it is MapEventsOverlay }
    mapView.overlays.clear()
    eventsOverlay?.let { mapView.overlays.add(it) }

    drawCountryBoundaries(mapView, visitedCountries, countryBoundaries)

    places.forEach { place ->
        val marker = Marker(mapView)
        marker.position = GeoPoint(place.latitude, place.longitude)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.icon = AppCompatResources.getDrawable(mapView.context, R.drawable.ic_pin_marker)
        marker.title = "${place.cityName}, ${place.countryName} (${place.year})"
        marker.setOnMarkerClickListener { _, _ ->
            onDeletePlace(place)
            true
        }
        mapView.overlays.add(marker)
    }
    mapView.invalidate()
}

private fun drawCountryBoundaries(
    mapView: MapView, 
    visitedCountries: Set<String>, 
    countryBoundaries: Map<String, CountryBoundary>?
) {
    if (visitedCountries.isEmpty() || countryBoundaries == null) return

    visitedCountries.forEach { countryName ->
        countryBoundaries[countryName]?.polygons?.forEach { points ->
            addPolygon(mapView, points)
        }
    }
}

private fun parsePolygon(coordsJson: JSONArray): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    for (i in 0 until coordsJson.length()) {
        val coord = coordsJson.getJSONArray(i)
        points.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
    }
    return points
}

private fun addPolygon(mapView: MapView, points: List<GeoPoint>) {
    val polygon = Polygon(mapView)
    polygon.points = points
    polygon.fillPaint.color = 0x3300FF00
    polygon.outlinePaint.color = 0xFF006600.toInt()
    polygon.outlinePaint.strokeWidth = 2f
    mapView.overlays.add(0, polygon)
}
