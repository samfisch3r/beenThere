package com.beenthere.android.ui

import android.app.Application
import androidx.lifecycle.*
import com.beenthere.android.data.AppDatabase
import com.beenthere.android.data.Place
import com.beenthere.android.data.PlaceRepository
import com.beenthere.android.ui.models.PhotonFeature
import com.beenthere.android.ui.models.PhotonResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.milliseconds

class PlaceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: PlaceRepository
    
    val allPlaces: StateFlow<List<Place>>

    private val _searchResults = MutableStateFlow<List<PhotonFeature>>(emptyList())
    val searchResults: StateFlow<List<PhotonFeature>> = _searchResults.asStateFlow()

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
    }

    fun performSearch(query: String) {
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
                    val url = "https://photon.komoot.io/api/?q=$encodedQuery"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "BeenThere-Android-App")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext emptyList()
                        val body = response.body?.string() ?: return@withContext emptyList()
                        val photonResponse = json.decodeFromString<PhotonResponse>(body)
                        photonResponse.features
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            _searchResults.value = results.distinctBy { it.toString() }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
    }

    fun insert(place: Place) = viewModelScope.launch {
        repository.insert(place)
    }

    fun delete(place: Place) = viewModelScope.launch {
        repository.delete(place)
    }
}
