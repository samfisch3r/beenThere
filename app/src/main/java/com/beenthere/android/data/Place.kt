package com.beenthere.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cityName: String,
    val countryName: String,
    val year: Int,
    val latitude: Double,
    val longitude: Double,
    val type: String = "CITY", // For future use: MOUNTAIN, HIKE, etc.
)
