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
        val code = resolveIsoCode(props)
        
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
                    val translation = props.optString(key)
                    if (translation.isNotBlank()) {
                        names.add(translation)
                        // Also index the first word for common name matching (e.g. "Kıbrıs" from "Kıbrıs Cumhuriyeti")
                        if (translation.contains(" ")) {
                            names.add(translation.split(" ")[0])
                        }
                    }
                }
            }

            for (name in names) {
                if (name.isNotBlank()) {
                    val lowerName = name.lowercase(Locale.US).trim()
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

    /**
     * Resolves the ISO code from properties, handling -99 and special territories.
     */
    fun resolveIsoCode(props: JSONObject): String? {
        val rawCode = props.optString("ISO_A2").takeIf { it.isNotBlank() } ?:
                      props.optString("iso_a2").takeIf { it.isNotBlank() } ?:
                      props.optString("ISO_A2_EH").takeIf { it.isNotBlank() }
        
        if (rawCode != null && rawCode != "-99") return rawCode

        // Use English names for logical grouping of disputed/special territories
        val name = props.optString("NAME_EN").lowercase(Locale.US)

        return when {
            name.contains("somaliland") -> "SO"
            name.contains("kosovo") -> "XK"
            name.contains("taiwan") -> "TW"
            else -> null
        }
    }

    fun getCountryAt(point: GeoPoint, countryBoundaries: Map<String, CountryBoundary>?): CountryBoundary? {
        if (countryBoundaries == null) return null
        
        var bestMatch: CountryBoundary? = null
        var minBboxArea = Double.MAX_VALUE

        for (boundary in countryBoundaries.values) {
            val bbox = boundary.bbox
            if (bbox != null && !bbox.contains(point)) {
                continue
            }

            for (polyData in boundary.polygons) {
                if (isPointInPolygon(point, polyData.exterior)) {
                    val inHole = polyData.holes.any { isPointInPolygon(point, it) }
                    if (!inHole) {
                        // When multiple countries match (e.g., enclaves),
                        // prefer the one with the smallest bounding box area.
                        val area = if (bbox != null) {
                            (bbox.latNorth - bbox.latSouth) * (bbox.lonEast - bbox.lonWest)
                        } else Double.MAX_VALUE
                        
                        if (area < minBboxArea) {
                            minBboxArea = area
                            bestMatch = boundary
                        }
                        // Break polygon loop once matched, but continue checking other countries
                        break
                    }
                }
            }
        }
        return bestMatch
    }

    private fun isPointInPolygon(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        var intersectCount = 0
        val lat = point.latitude
        val lng = point.longitude
        for (i in 0 until (polygon.size - 1)) {
            val p1 = polygon[i]
            val p2 = polygon[i + 1]
            if ((p1.latitude > lat) != (p2.latitude > lat) && 
                (lng < (p2.longitude - p1.longitude) * (lat - p1.latitude) / (p2.latitude - p1.latitude) + p1.longitude)) {
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
     * Converts any country name variation into a standard name in the current system language.
     */
    fun normalizeCountryName(name: String?): String {
        if (name.isNullOrBlank()) return "Unknown"
        val code = countryNameToCode(name) ?: return name
        return Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
    }

    fun countryNameToCode(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val defaultLocale = Locale.getDefault()
        val normalized = name.trim().lowercase(defaultLocale)
        
        countryCodeMap[normalized]?.let { return it }
        
        // Handle names with dots or the Turkish dotless i explicitly if needed
        val cleanName = normalized.replace(".", "").replace("ı", "i")
        countryCodeMap[cleanName]?.let { return it }

        // Fallback for common abbreviations not in the JSON
        if (normalized == "usa" || normalized == "united states") return "US"
        if (normalized == "uk" || normalized == "united kingdom") return "GB"

        // Java Locale fallback
        return Locale.getISOCountries().find { code ->
            val locale = Locale.Builder().setRegion(code).build()
            locale.getDisplayCountry(defaultLocale).lowercase(defaultLocale) == normalized ||
            locale.getDisplayCountry(Locale.US).lowercase(Locale.US) == normalized
        }
    }
}
