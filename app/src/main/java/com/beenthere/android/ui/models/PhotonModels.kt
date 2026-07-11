package com.beenthere.android.ui.models

import kotlinx.serialization.Serializable

@Serializable
data class PhotonResponse(val features: List<PhotonFeature>)

@Serializable
data class PhotonFeature(
    val properties: PhotonProperties,
    val geometry: PhotonGeometry
) {
    override fun toString(): String {
        return buildString {
            append(properties.name)
            properties.city?.let { if (it != properties.name) append(", $it") }
            properties.state?.let { append(", $it") }
            properties.country?.let { append(", $it") }
        }
    }
}

@Serializable
data class PhotonProperties(
    val name: String,
    val country: String? = null,
    val city: String? = null,
    val state: String? = null,
    val osm_key: String? = null,
    val osm_value: String? = null,
    val osm_type: String? = null
)

@Serializable
data class PhotonGeometry(val coordinates: List<Double>)
