package com.lightfm.radio

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
private data class ServerDto(val name: String? = null)

@Serializable
private data class StationDto(
    val stationuuid: String = "",
    val name: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val codec: String? = null,
    val bitrate: Int = 0,
    val tags: String? = null,
    @SerialName("geo_lat") val geoLat: Double? = null,
    @SerialName("geo_long") val geoLon: Double? = null,
) {
    fun toStationOrNull(): Station? {
        val url = urlResolved.trim()
        val title = name.trim()
        if (stationuuid.isBlank() || title.isBlank() || !url.startsWith("https://")) return null
        return Station(stationuuid, title, url, codec, bitrate, tags, geoLat, geoLon, parseFrequency(title))
    }
}

// A real FM frequency embedded in the station name ("88.5 KQED", "KALX 90.7FM"). Requires a decimal
// so "Live 105" and other bare integers don't produce false hits.
private val FREQ_RE = Regex("""(?<!\d)(8[7-9]|9\d|10[0-7])\.(\d)(?!\d)""")

private fun parseFrequency(name: String): Double? {
    val m = FREQ_RE.find(name) ?: return null
    val f = m.groupValues[1].toInt() + m.groupValues[2].toInt() / 10.0
    return if (f in 87.5..107.9) f else null
}

/**
 * Radio Browser (radio-browser.info) — free, no key. Resolves a mirror once, sends a
 * descriptive User-Agent (set on the shared [HttpClient]), and returns HTTPS-only stations.
 */
class RadioBrowserClient(private val http: HttpClient) {

    @Volatile private var mirror: String? = null

    private suspend fun mirror(): String {
        mirror?.let { return it }
        val resolved = runCatching {
            http.get("https://all.api.radio-browser.info/json/servers")
                .body<List<ServerDto>>()
                .mapNotNull { it.name }
                .map { "https://$it" }
                .randomOrNull()
        }.getOrNull() ?: FALLBACK_MIRROR
        mirror = resolved
        return resolved
    }

    /**
     * Local stations for a country/state, non-broken + HTTPS. Fetches a popularity-ordered pool,
     * then — when the user's coordinates are known — reorders nearest-first (stations with geo)
     * so results are actually local, not just statewide-popular. Capped to [DIAL_CAP].
     */
    suspend fun stations(
        countryCode: String?,
        state: String?,
        userLat: Double? = null,
        userLon: Double? = null,
        limit: Int = POOL_LIMIT,
    ): List<Station> {
        val params = buildList {
            if (!countryCode.isNullOrBlank()) add("countrycode=$countryCode")
            if (!state.isNullOrBlank()) add("state=${enc(state)}")
            add("hidebroken=true")
            add("is_https=true")
            add("order=votes")
            add("reverse=true")
            add("limit=$limit")
        }.joinToString("&")
        val pool = http.get("${mirror()}/json/stations/search?$params")
            .body<List<StationDto>>()
            .mapNotNull { it.toStationOrNull() }
        return localFirst(pool, userLat, userLon).take(DIAL_CAP)
    }

    // Stations with coordinates sorted nearest-first; the rest keep their popularity order after.
    private fun localFirst(stations: List<Station>, lat: Double?, lon: Double?): List<Station> {
        if (lat == null || lon == null) return stations
        val (located, unlocated) = stations.partition { it.lat != null && it.lon != null }
        return located.sortedBy { haversineKm(lat, lon, it.lat!!, it.lon!!) } + unlocated
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }

    /** Free-text station name search — the typed-location fallback path. */
    suspend fun searchByName(name: String, limit: Int = DIAL_CAP): List<Station> =
        http.get(
            "${mirror()}/json/stations/search?name=${enc(name)}" +
                "&hidebroken=true&is_https=true&order=votes&reverse=true&limit=$limit",
        ).body<List<StationDto>>().mapNotNull { it.toStationOrNull() }

    /** Register a listen (API etiquette). Fire-and-forget; failures ignored. */
    suspend fun registerClick(uuid: String) {
        runCatching { http.get("${mirror()}/json/url/$uuid") }
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    companion object {
        private const val FALLBACK_MIRROR = "https://de1.api.radio-browser.info"
        private const val POOL_LIMIT = 400   // fetch a wide pool, then reorder locally
        private const val DIAL_CAP = 120     // stations shown on the dial
        private const val EARTH_RADIUS_KM = 6371.0
    }
}
