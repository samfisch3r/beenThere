package com.beenthere.android.ui.models

import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint

data class CountryBoundary(
    val name: String,
    val countryCode: String?,
    val polygons: List<List<GeoPoint>>,
    val bbox: BoundingBox? = null
)
