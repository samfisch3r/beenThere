package com.beenthere.android.ui

import android.app.Application
import androidx.lifecycle.*
import com.beenthere.android.data.AppDatabase
import com.beenthere.android.data.Place
import com.beenthere.android.data.PlaceRepository
import com.beenthere.android.ui.models.CountryBoundary
import com.beenthere.android.ui.models.PhotonFeature
import com.beenthere.android.ui.models.PhotonResponse
import com.beenthere.android.utils.LocationUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class PlaceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: PlaceRepository
    
    val allPlaces: StateFlow<List<Place>>

    private val _searchResults = MutableStateFlow<List<PhotonFeature>>(emptyList())
    val searchResults: StateFlow<List<PhotonFeature>> = _searchResults.asStateFlow()

    private val _countryBoundaries = MutableStateFlow<Map<String, CountryBoundary>?>(null)
    val countryBoundaries: StateFlow<Map<String, CountryBoundary>?> = _countryBoundaries.asStateFlow()

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private var searchJob: Job? = null

    init {
        val placeDao = AppDatabase.getDatabase(application).placeDao()
        repository = PlaceRepository(placeDao)
        allPlaces = repository.allPlaces
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )
        
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch(Dispatchers.IO) {
            // Stage 1: Fast metadata loading from small file
            val metadata = LocationUtils.loadCountryMetadata(getApplication())
            _countryBoundaries.value = metadata
            
            // Stage 2: Heavy polygon loading from big file in background
            loadCountryBoundaries()
        }
    }

    private fun loadCountryBoundaries() {
        try {
            val context = getApplication<Application>()
            val inputStream = context.assets.open("countries.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val features = JSONObject(jsonString).getJSONArray("features")
            
            // Update existing boundaries with polygons instead of replacing them
            val currentMap = _countryBoundaries.value?.toMutableMap() ?: mutableMapOf()
            
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val props = feature.getJSONObject("properties")
                val name = props.optString("name").takeIf { it.isNotBlank() } ?: 
                           props.optString("NAME").takeIf { it.isNotBlank() } ?: 
                           props.optString("admin").takeIf { it.isNotBlank() }

                if (name != null) {
                    LocationUtils.registerCountryProperties(props)
                    val countryCode = LocationUtils.resolveIsoCode(props)
                    
                    val bboxJson = feature.optJSONArray("bbox")
                    val bbox = if (bboxJson != null && bboxJson.length() == 4) {
                        org.osmdroid.util.BoundingBox(
                            bboxJson.getDouble(3), // north
                            bboxJson.getDouble(2), // east
                            bboxJson.getDouble(1), // south
                            bboxJson.getDouble(0)  // west
                        )
                    } else null

                    val geometry = feature.getJSONObject("geometry")
                    val type = geometry.getString("type")
                    val coordsJson = geometry.getJSONArray("coordinates")
                    val polygons = mutableListOf<com.beenthere.android.ui.models.PolygonData>()

                    if (type == "Polygon") {
                        val exterior = parsePolygon(coordsJson.getJSONArray(0))
                        val holes = mutableListOf<List<GeoPoint>>()
                        for (k in 1 until coordsJson.length()) {
                            holes.add(parsePolygon(coordsJson.getJSONArray(k)))
                        }
                        polygons.add(com.beenthere.android.ui.models.PolygonData(exterior, holes))
                    } else if (type == "MultiPolygon") {
                        for (j in 0 until coordsJson.length()) {
                            val polyCoords = coordsJson.getJSONArray(j)
                            val exterior = parsePolygon(polyCoords.getJSONArray(0))
                            val holes = mutableListOf<List<GeoPoint>>()
                            for (k in 1 until polyCoords.length()) {
                                holes.add(parsePolygon(polyCoords.getJSONArray(k)))
                            }
                            polygons.add(com.beenthere.android.ui.models.PolygonData(exterior, holes))
                        }
                    }
                    
                    val boundary = CountryBoundary(name, countryCode, polygons, bbox)
                    currentMap[name] = boundary
                    if (countryCode != null) {
                        currentMap[countryCode] = boundary
                    }
                }
            }
            _countryBoundaries.value = currentMap
        } catch (e: Exception) {
            e.printStackTrace()
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

    fun performSearch(query: String, lat: Double? = null, lon: Double? = null) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.length < 3) {
            _searchResults.value = emptyList()
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500.milliseconds)
            val results = withContext(Dispatchers.IO) {
                try {
                    val encodedQuery = java.net.URLEncoder.encode(trimmedQuery, "UTF-8")
                    val lang = Locale.getDefault().language
                    // Use limit 30, bias results if location is provided, filter for places, and request names in system language
                    var url = "https://photon.komoot.io/api/?q=$encodedQuery&limit=30&lang=$lang" +
                              "&osm_tag=place&osm_tag=boundary:administrative"
                    if (lat != null && lon != null) {
                        url += "&lat=$lat&lon=$lon"
                    }

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "BeenThere-Android-App")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext emptyList()
                        val body = response.body.string()
                        val photonResponse = json.decodeFromString<PhotonResponse>(body)
                        photonResponse.features
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            // Prioritize cities/towns/villages and filter out low-relevance POIs
            val sortedResults = results.sortedWith(compareByDescending<PhotonFeature> { 
                it.properties.osm_key == "place" 
            }.thenByDescending {
                it.properties.osm_value == "city" || it.properties.osm_value == "town"
            })

            _searchResults.value = sortedResults.distinctBy {
                "${it.properties.name}-${it.geometry.coordinates[0]}-${it.geometry.coordinates[1]}"
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): PhotonFeature? = withContext(Dispatchers.IO) {
        try {
            val lang = Locale.getDefault().language
            val url = "https://photon.komoot.io/reverse?lat=$lat&lon=$lon&lang=$lang"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BeenThere-Android-App")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body.string()
                val photonResponse = json.decodeFromString<PhotonResponse>(body)
                
                // Prioritize features that are actually cities, towns or villages
                // If not found, fall back to the first result (most specific)
                photonResponse.features.find { 
                    it.properties.osm_key == "place" && 
                    (it.properties.osm_value == "city" || it.properties.osm_value == "town" || it.properties.osm_value == "village")
                } ?: photonResponse.features.firstOrNull()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun insert(place: Place) = viewModelScope.launch {
        repository.insert(place)
    }

    fun delete(place: Place) = viewModelScope.launch {
        repository.delete(place)
    }
}
