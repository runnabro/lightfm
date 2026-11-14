package com.lightfm.radio

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Location autocomplete. A 5-digit query is treated as a US ZIP (zippopotam.us); anything else is a
 * city-name search via the Light Phone cloud geocoder (the same endpoint the weather tool uses).
 * Both are keyless HTTPS and return coordinates + region so results feed straight into a station query.
 */
class Geocoder(private val http: HttpClient) {

    @Serializable private data class CityResp(val results: List<CityDto> = emptyList())

    @Serializable private data class CityDto(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country: String? = null,
        val admin1: String? = null,
    )

    @Serializable private data class ZipResp(
        val places: List<ZipPlace> = emptyList(),
    )

    @Serializable private data class ZipPlace(
        @SerialName("place name") val place: String? = null,
        val state: String? = null,
        @SerialName("state abbreviation") val stateAbbrev: String? = null,
        val latitude: String? = null,
        val longitude: String? = null,
    )

    suspend fun search(query: String): List<GeoChoice> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return if (q.length == 5 && q.all { it.isDigit() }) zip(q) else city(q)
    }

    private suspend fun city(q: String): List<GeoChoice> = runCatching {
        http.get("https://production.lightphonecloud.com/tools/weather/geolocation") {
            parameter("name", q)
            parameter("count", "8")
        }.body<CityResp>().results.map {
            GeoChoice(
                label = listOfNotNull(it.name, it.admin1).joinToString(", "),
                countryCode = if (it.country?.contains("United States", true) == true) "US" else null,
                state = it.admin1?.takeIf { s -> s.isNotBlank() },
                lat = it.latitude,
                lon = it.longitude,
            )
        }
    }.getOrDefault(emptyList())

    private suspend fun zip(z: String): List<GeoChoice> = runCatching {
        http.get("https://api.zippopotam.us/us/$z").body<ZipResp>().places.mapNotNull { p ->
            val lat = p.latitude?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = p.longitude?.toDoubleOrNull() ?: return@mapNotNull null
            GeoChoice(
                label = listOfNotNull(p.place, p.stateAbbrev).joinToString(", ").ifBlank { z } + " · $z",
                countryCode = "US",
                state = p.state,
                lat = lat,
                lon = lon,
            )
        }
    }.getOrDefault(emptyList())
}
