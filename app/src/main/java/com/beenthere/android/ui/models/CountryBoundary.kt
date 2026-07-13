package com.beenthere.android.ui.models

import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint

data class CountryBoundary(
    val name: String,
    val countryCode: String?,
    val polygons: List<PolygonData>,
    val bbox: BoundingBox? = null
)

data class PolygonData(
    val exterior: List<GeoPoint>,
    val holes: List<List<GeoPoint>> = emptyList()
)
