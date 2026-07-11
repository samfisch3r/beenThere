package com.beenthere.android.utils

import android.content.Context
import com.beenthere.android.ui.models.CountryBoundary
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.Locale

object LocationUtils {
    private val countryCodeMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Loads country name-to-code mappings from country_info.json.
     */
    fun loadCountryData(context: Context) {
        if (countryCodeMap.isNotEmpty()) return
        
        try {
            val inputStream = context.assets.open("country_info.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val features = JSONObject(jsonString).getJSONArray("features")
            
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val props = feature.getJSONObject("properties")
                registerCountryProperties(props)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Registers all name variations for a country from the country_info.json properties.
     */
    fun registerCountryProperties(props: JSONObject) {
        val code = props.optString("ISO_A2").takeIf { it.isNotBlank() && it != "-99" }
        
        if (code != null) {
            val upperCode = code.uppercase()
            val names = mutableSetOf<String>()
            
            // Core name fields from country_info.json
            names.add(props.optString("NAME"))
            names.add(props.optString("NAME_LONG"))
            names.add(props.optString("NAME_EN"))
            names.add(props.optString("NAME_CIAWF"))
            names.add(props.optString("NAME_SORT"))
            names.add(props.optString("ABBREV"))
            
            // Add all translations (NAME_AR, NAME_DE, NAME_FR, etc.)
            for (key in props.keys()) {
                if (key.startsWith("NAME_")) {
                    names.add(props.optString(key))
                }
            }

            for (name in names) {
                if (name.isNotBlank()) {
                    val lowerName = name.lowercase().trim()
                    countryCodeMap[lowerName] = upperCode
                    
                    // Handle abbreviations with dots (e.g., "V.I. (Br.)" -> "vi (br)")
                    if (lowerName.contains(".")) {
                        val noDots = lowerName.replace(".", "").trim()
                        countryCodeMap[noDots] = upperCode
                    }
                }
            }
        }
    }

    fun getCountryAt(point: GeoPoint, countryBoundaries: Map<String, CountryBoundary>?): CountryBoundary? {
        if (countryBoundaries == null) return null
        
        for (boundary in countryBoundaries.values) {
            if (boundary.bbox != null && !boundary.bbox.contains(point)) {
                continue
            }

            for (polygon in boundary.polygons) {
                if (isPointInPolygon(point, polygon)) return boundary
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
        return countryCodeToEmoji(countryCode)
    }

    fun countryCodeToEmoji(code: String): String {
        if (code.length != 2) return "🏳️"
        val upperCode = code.uppercase()
        val firstLetter = Character.codePointAt(upperCode, 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(upperCode, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    }

    /**
     * Converts any country name variation into a standard English name (e.g., "Sverige" -> "Sweden").
     */
    fun normalizeCountryName(name: String?): String {
        if (name.isNullOrBlank()) return "Unknown"
        val code = countryNameToCode(name) ?: return name
        return Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.US)
    }

    private fun countryNameToCode(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val normalized = name.trim().lowercase()
        
        countryCodeMap[normalized]?.let { return it }
        
        if (normalized.contains(".")) {
            countryCodeMap[normalized.replace(".", "").trim()]?.let { return it }
        }

        // Java Locale fallback for any name not caught by the JSON mapping
        return Locale.getISOCountries().find { code ->
            val locale = Locale.Builder().setRegion(code).build()
            locale.displayCountry.lowercase() == normalized ||
            locale.getDisplayCountry(Locale.US).lowercase() == normalized
        }
    }
}
