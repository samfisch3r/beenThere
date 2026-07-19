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
    
    private val iso3To2Map by lazy {
        Locale.getISOCountries().associateBy { 
            try { Locale.Builder().setRegion(it).build().isO3Country.uppercase(Locale.US) } catch (_: Exception) { "" }
        }.filterKeys { it.isNotEmpty() }
    }

    private val standardNameToCodeMap by lazy {
        val map = mutableMapOf<String, String>()
        val locales = arrayOf(Locale.US, Locale.getDefault())
        for (code in Locale.getISOCountries()) {
            val upperCode = code.uppercase(Locale.US)
            for (locale in locales) {
                try {
                    val name = Locale.Builder().setRegion(upperCode).build().getDisplayCountry(locale).lowercase(Locale.US)
                    if (name.isNotEmpty()) map[name] = upperCode
                } catch (_: Exception) {}
            }
        }
        map
    }

    private val transliterator by lazy { Transliterator.getInstance("Any-Latin; Latin-ASCII; Any-Lower") }
    private val nonAlphaNumeric = Regex("[^a-z0-9\\s]")
    private const val NULL_CODE = "##"

    /**
     * Loads essential country metadata (names, codes, bboxes) from the small info file.
     * This is much faster than parsing full polygons.
     */
    fun loadCountryMetadata(context: Context): Map<String, CountryBoundary> {
        nameToCodeCache.clear()
        normalizationCache.clear()
        countryCodeMap.clear() // Clear this too to avoid stale mappings
        val metadataMap = mutableMapOf<String, CountryBoundary>()
        try {
            val inputStream = context.assets.open("country_info.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val features = JSONObject(jsonString).getJSONArray("features")
            
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val props = feature.getJSONObject("properties")
                
                registerCountryProperties(props)
                val name = props.optString("NAME").takeIf { it.isNotBlank() } ?: 
                           props.optString("name").takeIf { it.isNotBlank() } ?: 
                           props.optString("NAME_EN")
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
                }
            }
            
            // Only fall back to full iteration if we have very few names
            if (names.size < 2) {
                val keys = props.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = props.optString(key)
                    if (value.isNotEmpty() && value != "null") {
                        val upperKey = key.uppercase(Locale.US)
                        if (upperKey.contains("NAME") || upperKey.contains("ABBREV") || upperKey == "ADMIN") {
                            names.add(value)
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
     * Supports both 2-letter and 3-letter codes, converting the latter to 2-letter.
     */
    fun resolveIsoCode(props: JSONObject): String? {
        val keys = arrayOf(
            "ISO_A2", "iso_a2", "ISO_A2_EH", "ISO_A3", "iso_a3", 
            "ISO_N3", "iso_n3", "ISO", "code", "iso3166_1_alpha_2", "iso3166_1_alpha_3"
        )
        
        var rawCode: String? = null
        for (key in keys) {
            val value = props.optString(key)
            if (value.isNotBlank() && value != "-99" && value != "null") {
                rawCode = value
                break
            }
        }
        
        val name = (props.optString("NAME_EN").takeIf { it.isNotBlank() } ?: 
                    props.optString("NAME").takeIf { it.isNotBlank() } ?: 
                    props.optString("name")).lowercase(Locale.US).trim()

        // Name-based overrides handle -99 cases like France, Norway, Kosovo, etc.
        val nameResolvedCode = when {
            name.contains("somaliland") -> "SO"
            name.contains("kosovo") -> "XK"
            name.contains("taiwan") -> "TW"
            else -> standardNameToCodeMap[name]
        }
        
        if (nameResolvedCode != null) return nameResolvedCode
        if (rawCode == null) return null

        val upperCode = rawCode.uppercase(Locale.US).trim()
        
        // Generalized approach: if the code contains a known problematic standard code, use it.
        // This handles "CN-TW" -> "TW", "RS-XK" -> "XK", etc.
        if (upperCode.contains("TW")) return "TW"
        if (upperCode.contains("XK")) return "XK"
        
        // If it's a composite code with a hyphen, try to find a 2-letter part
        if (upperCode.contains("-")) {
            val parts = upperCode.split("-")
            // Prefer the part that isn't a broad parent category like CN, US, or FR
            val candidate = parts.find { it.length == 2 && it !in arrayOf("CN", "US", "FR") }
                ?: parts.firstOrNull { it.length == 2 }
            if (candidate != null) return candidate
        }

        return when (upperCode.length) {
            2 -> upperCode
            3 -> iso3To2Map[upperCode] ?: upperCode // Return ISO-3 as fallback
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

    /**
     * Converts a country name or code into its corresponding flag emoji.
     * Returns a white flag 🏳️ if the country cannot be identified.
     */
    fun getFlagEmoji(input: String?): String {
        val code = countryNameToCode(input) ?: return "🏳️"
        
        var cleanCode = code.uppercase(Locale.US).trim()
        
        // Handle ISO-3 codes if they somehow made it here
        if (cleanCode.length == 3) {
            cleanCode = iso3To2Map[cleanCode] ?: return "🏳️"
        }
        
        if (cleanCode.length != 2 || !cleanCode.all { it in 'A'..'Z' }) return "🏳️"

        val firstLetter = Character.codePointAt(cleanCode, 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(cleanCode, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    }

    /**
     * Converts a country name or code into its full display name (e.g., "France").
     */
    fun normalizeCountryName(name: String?): String {
        if (name.isNullOrBlank()) return "Unknown"
        
        val code = countryNameToCode(name) ?: return name
        
        val cacheKey = "display_$code"
        normalizationCache[cacheKey]?.let { return it }
        
        val result = try {
            Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
        } catch (_: Exception) {
            code
        }
        
        if (result.isNotEmpty()) {
            normalizationCache[cacheKey] = result
            return result
        }
        return code
    }

    fun countryNameToCode(name: String?): String? {
        if (name.isNullOrBlank()) return null
        
        val trimmedName = name.trim()
        if (trimmedName.length == 2 && trimmedName.all { it.isLetter() }) {
            return trimmedName.uppercase(Locale.US)
        }

        val cached = nameToCodeCache[trimmedName]
        if (cached != null) {
            return if (cached == NULL_CODE) null else cached
        }
        
        val normalized = trimmedName.lowercase(Locale.US)
        
        var code = countryCodeMap[normalized] ?: standardNameToCodeMap[normalized]
        
        if (code == null) {
            // Only attempt partial matching for names long enough to be unique (>= 4 chars)
            // This prevents "Sudan" or "South Africa" from matching "S." or "South" (SS)
            if (normalized.length >= 4) {
                code = countryCodeMap.entries.find { (key, _) -> 
                    key.length >= 4 && (normalized.contains(key) || key.contains(normalized))
                }?.value
            }
        }
        
        if (code == null) {
            // Fallback to the flattened/normalized version (handles dots, accents, script conversion, etc.)
            val flattened = normalized.normalizeForMatching()
            if (flattened != normalized && flattened.isNotEmpty()) {
                code = countryCodeMap[flattened] ?: if (flattened.length >= 4) {
                    countryCodeMap.entries.find { (key, _) ->
                        key.length >= 4 && (flattened.contains(key) || key.contains(flattened))
                    }?.value
                } else null
            }
        }
        
        nameToCodeCache[trimmedName] = code ?: NULL_CODE
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

    fun clearCaches() {
        nameToCodeCache.clear()
        normalizationCache.clear()
        transliterationCache.clear()
    }
}
