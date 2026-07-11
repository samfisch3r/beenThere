package com.beenthere.android.ui.models

import org.osmdroid.util.GeoPoint

data class CountryBoundary(
    val name: String,
    val polygons: List<List<GeoPoint>>
)
