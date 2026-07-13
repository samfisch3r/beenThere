package com.beenthere.android.utils

import android.content.Context
import android.icu.text.Transliterator
import com.beenthere.android.ui.models.CountryBoundary
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.Locale

object LocationUtils {
    private val countryCodeMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val normalizationCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val nameToCodeCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val transliterationCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    
    private val transliterator by lazy { Transliterator.getInstance("Any-Latin; Latin-ASCII; Any-Lower") }
    private val nonAlphaNumeric = Regex("[^a-z0-9\\s]")
    private const val NULL_CODE = "##"

    private var currentLocale: Locale = Locale.getDefault()
    private var currentLanguage: String = currentLocale.language

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
            
            // Fast-path for common keys to avoid full key iteration if possible
            val commonKeys = arrayOf("NAME", "name", "NAME_EN", "name_en", "ISO_A3", "iso_a3", "ABBREV", "abbrev", "ADMIN", "admin")
            for (key in commonKeys) {
                val value = props.optString(key)
                if (value.isNotEmpty() && value != "null") {
                    names.add(value)
                    val spaceIndex = value.indexOf(' ')
                    if (spaceIndex != -1) {
                        names.add(value.substring(0, spaceIndex))
                    }
                }
            }
            
            // Only fall back to full iteration if we have very few names (unlikely with the common keys above)
            if (names.size < 3) {
                val keys = props.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = props.optString(key)
                    if (value.isNotEmpty() && value != "null") {
                        val upperKey = key.uppercase(Locale.US)
                        if (upperKey.contains("NAME") || upperKey.contains("ABBREV") || upperKey == "ADMIN") {
                            names.add(value)
                            val spaceIndex = value.indexOf(' ')
                            if (spaceIndex != -1) {
                                names.add(value.substring(0, spaceIndex))
                            }
                        }
                    }
                }
            }

            for (name in names) {
                val lowerName = name.lowercase(Locale.US).trim()
                if (lowerName.isNotEmpty()) {
                    countryCodeMap[lowerName] = upperCode
                    
                    // Only perform expensive normalization if the name contains non-ASCII characters or special symbols
                    if (lowerName.any { it.code > 127 || !it.isLetterOrDigit() }) {
                        val flattened = lowerName.normalizeForMatching()
                        if (flattened != lowerName && flattened.isNotEmpty()) {
                            countryCodeMap[flattened] = upperCode
                        }
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
        val name = (props.optString("NAME_EN").takeIf { it.isNotBlank() } ?: 
                    props.optString("NAME").takeIf { it.isNotBlank() } ?: 
                    props.optString("name")).lowercase(Locale.US)

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
        var minArea = Double.MAX_VALUE
        
        // Use a Set to track processed boundaries by identity to avoid redundant expensive checks
        // (especially important since the map contains multiple keys for the same boundary object)
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CountryBoundary, Boolean>())
        
        for (boundary in countryBoundaries.values) {
            if (!seen.add(boundary)) continue
            
            val bbox = boundary.bbox ?: continue
            
            // Fast bounding box check
            if (bbox.contains(point)) {
                val area = (bbox.latNorth - bbox.latSouth) * (bbox.lonEast - bbox.lonWest)
                
                // Only perform expensive polygon check if this country is smaller than our current best match
                if (area < minArea) {
                    val matches = if (boundary.polygons.isEmpty()) {
                        true // BBox match fallback
                    } else {
                        boundary.polygons.any { poly ->
                            isPointInPolygon(point, poly.exterior) && 
                            poly.holes.none { isPointInPolygon(point, it) }
                        }
                    }
                    
                    if (matches) {
                        bestMatch = boundary
                        minArea = area
                    }
                }
            }
        }
        return bestMatch
    }

    private fun isPointInPolygon(point: GeoPoint, polygon: List<GeoPoint>): Boolean {
        val size = polygon.size
        if (size < 3) return false
        
        var intersectCount = 0
        val lat = point.latitude
        val lng = point.longitude
        
        // Optimize property access and loop to avoid repeated indexing and getter calls
        val lastPoint = polygon[size - 1]
        var p1Lat = lastPoint.latitude
        var p1Lng = lastPoint.longitude
        
        for (i in 0 until size) {
            val p2 = polygon[i]
            val p2Lat = p2.latitude
            val p2Lng = p2.longitude
            
            if ((p1Lat > lat) != (p2Lat > lat)) {
                val minLng = if (p1Lng < p2Lng) p1Lng else p2Lng
                if (lng < minLng) {
                    intersectCount++
                } else {
                    val maxLng = if (p1Lng > p2Lng) p1Lng else p2Lng
                    if (lng < maxLng) {
                        // Calculate intersection
                        val intersectLng = (p2Lng - p1Lng) * (lat - p1Lat) / (p2Lat - p1Lat) + p1Lng
                        if (lng < intersectLng) {
                            intersectCount++
                        }
                    }
                }
            }
            p1Lat = p2Lat
            p1Lng = p2Lng
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
        
        val locale = Locale.getDefault()
        if (locale != currentLocale) {
            currentLocale = locale
            currentLanguage = locale.language
            normalizationCache.clear()
        }
        
        val cacheKey = "${name}_${currentLanguage}"
        normalizationCache[cacheKey]?.let { return it }
        
        val code = countryNameToCode(name)
        val result = if (code != null) {
            Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
        } else {
            name
        }
        
        normalizationCache[cacheKey] = result
        return result
    }

    fun countryNameToCode(name: String?): String? {
        if (name.isNullOrBlank()) return null
        
        val cached = nameToCodeCache[name]
        if (cached != null) {
            return if (cached == NULL_CODE) null else cached
        }
        
        val normalized = name.trim().lowercase(Locale.US)
        
        var code = countryCodeMap[normalized]
        
        if (code == null) {
            // Fallback to the flattened/normalized version (handles dots, accents, script conversion, etc.)
            val flattened = normalized.normalizeForMatching()
            if (flattened != normalized) {
                code = countryCodeMap[flattened]
            }
        }
        
        nameToCodeCache[name] = code ?: NULL_CODE
        return code
    }

    private fun String.normalizeForMatching(): String {
        if (this.isEmpty()) return this
        
        transliterationCache[this]?.let { return it }
        
        val flattened = transliterator.transliterate(this)
        val result = flattened.replace(nonAlphaNumeric, "").trim()
        
        transliterationCache[this] = result
        return result
    }
}
