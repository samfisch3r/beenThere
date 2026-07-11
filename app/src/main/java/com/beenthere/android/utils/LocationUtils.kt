package com.beenthere.android.utils

import com.beenthere.android.ui.models.CountryBoundary
import org.osmdroid.util.GeoPoint

object LocationUtils {
    fun getCountryNameAt(point: GeoPoint, countryBoundaries: Map<String, CountryBoundary>?): String? {
        if (countryBoundaries == null) return null
        
        for (boundary in countryBoundaries.values) {
            for (polygon in boundary.polygons) {
                if (isPointInPolygon(point, polygon)) return boundary.name
            }
        }
        return null
    }

    private fun isPointInPolygon(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        var intersectCount = 0
        val lat = point.latitude
        val lng = point.longitude
        for (i in 0 until (polygon.size - 1)) {
            val p1 = polygon[i]
            val p2 = polygon[i + 1]
            val lat1 = p1.latitude
            val lng1 = p1.longitude
            val lat2 = p2.latitude
            val lng2 = p2.longitude
            if ((lat1 > lat) != (lat2 > lat) && (lng < (lng2 - lng1) * (lat - lat1) / (lat2 - lat1) + lng1)) {
                intersectCount++
            }
        }
        return intersectCount % 2 != 0
    }

    fun getFlagEmoji(countryName: String): String {
        val countryCode = countryNameToCode(countryName) ?: return "🏳️"
        val firstLetter = Character.codePointAt(countryCode, 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(countryCode, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    }

    private fun countryNameToCode(name: String): String? {
        val map = mapOf(
            "France" to "FR", "Germany" to "DE", "Italy" to "IT", "Spain" to "ES",
            "United Kingdom" to "GB", "USA" to "US", "United States" to "US",
            "Switzerland" to "CH", "Japan" to "JP", "China" to "CN", "Brazil" to "BR", "India" to "IN",
        )
        return map[name] ?: java.util.Locale.getISOCountries().find { code ->
            java.util.Locale.Builder().setRegion(code).build().displayCountry.equals(name, ignoreCase = true)
        }
    }
}
