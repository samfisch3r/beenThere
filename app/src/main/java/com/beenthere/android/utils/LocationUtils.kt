package com.beenthere.android.utils

import android.content.Context
import com.beenthere.android.ui.models.CountryBoundary
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.Locale

object LocationUtils {
    private val countryCodeMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Loads essential country metadata (names, codes, bboxes) from the small info file.
     * This is much faster than parsing full polygons.
     */
    fun loadCountryMetadata(context: Context): Map<String, CountryBoundary> {
        val metadataMap = mutableMapOf<String, CountryBoundary>()
        try {
            val inputStream = context.assets.open("country_info.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val features = JSONObject(jsonString).getJSONArray("features")
            
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val props = feature.getJSONObject("properties")
                
                registerCountryProperties(props)
                val name = props.optString("NAME")
                val code = resolveIsoCode(props)
                
                val bboxJson = feature.optJSONArray("bbox")
                val bbox = if (bboxJson != null && bboxJson.length() == 4) {
                    org.osmdroid.util.BoundingBox(
                        bboxJson.getDouble(3), // north
                        bboxJson.getDouble(2), // east
                        bboxJson.getDouble(1), // south
                        bboxJson.getDouble(0)  // west
                    )
                } else null

                if (name.isNotBlank()) {
                    val boundary = CountryBoundary(name, code, emptyList(), bbox)
                    metadataMap[name] = boundary
                    if (code != null) metadataMap[code] = boundary
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return metadataMap
    }

    /**
     * Registers all name variations for a country from properties.
     */
    fun registerCountryProperties(props: JSONObject) {
        val code = resolveIsoCode(props)
        
        if (code != null) {
            val upperCode = code.uppercase()
            val names = mutableSetOf<String>()
            
            // Core name fields - handle both upper and lower case variants found in various GeoJSON sources
            listOf("NAME", "name", "NAME_LONG", "name_long", "NAME_EN", "name_en", "NAME_CIAWF", "NAME_SORT", "ABBREV", "abbrev", "admin").forEach { key ->
                val value = props.optString(key)
                if (value.isNotBlank() && value != "null") {
                    names.add(value)
                }
            }
            
            // Add all translations (NAME_AR, NAME_DE, NAME_FR, etc.)
            for (key in props.keys()) {
                if (key.startsWith("NAME_", ignoreCase = true)) {
                    val translation = props.optString(key)
                    if (translation.isNotBlank() && translation != "null") {
                        names.add(translation)
                        // Also index the first word for common name matching (e.g. "Kıbrıs" from "Kıbrıs Cumhuriyeti")
                        if (translation.contains(" ")) {
                            names.add(translation.split(" ")[0])
                        }
                    }
                }
            }

            for (name in names) {
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

        // Use distinct values to avoid redundant checks if map has multiple keys for same boundary
        val uniqueBoundaries = countryBoundaries.values.distinct()
        
        for (boundary in uniqueBoundaries) {
            val bbox = boundary.bbox
            if (bbox != null && !bbox.contains(point)) {
                continue
            }

            // Optimization: If we have polygons, use them for precision.
            if (boundary.polygons.isNotEmpty()) {
                for (polyData in boundary.polygons) {
                    if (isPointInPolygon(point, polyData.exterior)) {
                        val inHole = polyData.holes.any { isPointInPolygon(point, it) }
                        if (!inHole) {
                            val area = (bbox!!.latNorth - bbox.latSouth) * (bbox.lonEast - bbox.lonWest)
                            if (area < minBboxArea) {
                                minBboxArea = area
                                bestMatch = boundary
                            }
                            break
                        }
                    }
                }
            } else if (bestMatch == null) {
                // Fallback: If no polygons loaded yet, the BBox match is our best guess
                val area = if (bbox != null) {
                    (bbox.latNorth - bbox.latSouth) * (bbox.lonEast - bbox.lonWest)
                } else Double.MAX_VALUE
                
                if (area < minBboxArea) {
                    minBboxArea = area
                    bestMatch = boundary
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
        val normalized = name.trim().lowercase(Locale.US)
        
        countryCodeMap[normalized]?.let { return it }
        
        // Handle names with dots or the Turkish dotless i explicitly if needed
        val cleanName = normalized.replace(".", "").replace("ı", "i")
        countryCodeMap[cleanName]?.let { return it }

        // Java Locale fallback
        val defaultLocale = Locale.getDefault()
        return Locale.getISOCountries().find { code ->
            val locale = Locale.Builder().setRegion(code).build()
            val countryNameDefault = locale.getDisplayCountry(defaultLocale).lowercase(Locale.US)
            val countryNameUS = locale.getDisplayCountry(Locale.US).lowercase(Locale.US)
            countryNameDefault == normalized || countryNameUS == normalized
        }
    }
}
