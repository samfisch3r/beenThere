package com.beenthere.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beenthere.android.data.Place
import com.beenthere.android.ui.PlaceViewModel
import com.beenthere.android.utils.LocationUtils

@Composable
fun CitiesScreen(viewModel: PlaceViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val places by viewModel.allPlaces.collectAsStateWithLifecycle()
    // Observe country boundaries to trigger recomposition when metadata is loaded
    val metadata by viewModel.countryBoundaries.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Visited Cities",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = places.size.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray.copy(alpha = 0.5f)
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(places, key = { "${it.id}_${metadata?.size ?: 0}" }) { place ->
                CityItem(place)
                HorizontalDivider(color = Color.LightGray)
            }
        }
    }
}

@Composable
fun CityItem(place: Place) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = LocationUtils.getFlagEmoji(place.countryName),
            fontSize = 24.sp,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = place.cityName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                text = LocationUtils.normalizeCountryName(place.countryName),
                fontSize = 14.sp,
                color = Color.Gray
            )
        }
        Text(
            text = place.year.toString(),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.DarkGray
        )
    }
}
