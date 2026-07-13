package com.beenthere.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beenthere.android.ui.PlaceViewModel
import com.beenthere.android.utils.LocationUtils

data class CountryStat(val name: String, val cityCount: Int)

@Composable
fun CountriesScreen(viewModel: PlaceViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val places by viewModel.allPlaces.collectAsStateWithLifecycle()
    
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }

    val countryStats = remember(places) {
        places.groupBy { LocationUtils.normalizeCountryName(it.countryName) }
            .map { (name, cityList) -> CountryStat(name, cityList.size) }
            .sortedBy { it.name }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Visited Countries",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = countryStats.size.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray.copy(alpha = 0.5f)
            )
        }

        LazyColumn {
            items(countryStats) { stat ->
                CountryItem(stat)
                HorizontalDivider(color = Color.LightGray)
            }
        }
    }
}

@Composable
fun CountryItem(stat: CountryStat) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = LocationUtils.getFlagEmoji(stat.name), fontSize = 24.sp, modifier = Modifier.padding(end = 16.dp))
        Text(
            text = stat.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(text = "${stat.cityCount} cities", fontSize = 14.sp, color = Color.Gray)
    }
}
