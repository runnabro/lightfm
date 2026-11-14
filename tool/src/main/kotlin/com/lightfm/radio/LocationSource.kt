package com.lightfm.radio

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolves the user's location to a [StationQuery]. Device GPS is unavailable to LP3 tools
 * (the SDK sandbox blocks it), so this uses IP-geolocation over HTTPS — no key required.
 * `region` comes back as a full state name (e.g. "California"), which matches Radio Browser's
 * `state` filter directly.
 */
interface LocationSource {
    suspend fun resolve(): StationQuery?
}

class IpGeoLocationSource(private val http: HttpClient) : LocationSource {

    @Serializable
    private data class IpWhoIs(
        val success: Boolean = false,
        @SerialName("country_code") val countryCode: String? = null,
        val region: String? = null,
        val city: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    @Serializable
    private data class IpApiCo(
        val error: Boolean = false,
        @SerialName("country_code") val countryCode: String? = null,
        val region: String? = null,
        val city: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    override suspend fun resolve(): StationQuery? =
        resolveIpWhoIs() ?: resolveIpApiCo()

    private suspend fun resolveIpWhoIs(): StationQuery? = runCatching {
        val g: IpWhoIs = http.get("https://ipwho.is/").body()
        if (!g.success || g.countryCode.isNullOrBlank()) return null
        toQuery(g.countryCode, g.region, g.city, g.latitude, g.longitude)
    }.getOrNull()

    private suspend fun resolveIpApiCo(): StationQuery? = runCatching {
        val g: IpApiCo = http.get("https://ipapi.co/json/").body()
        if (g.error || g.countryCode.isNullOrBlank()) return null
        toQuery(g.countryCode, g.region, g.city, g.latitude, g.longitude)
    }.getOrNull()

    private fun toQuery(
        countryCode: String,
        region: String?,
        city: String?,
        lat: Double?,
        lon: Double?,
    ): StationQuery {
        val label = listOfNotNull(city?.takeIf { it.isNotBlank() }, region?.takeIf { it.isNotBlank() })
            .joinToString(", ")
            .ifBlank { countryCode }
        return StationQuery(
            countryCode = countryCode,
            state = region?.takeIf { it.isNotBlank() },
            label = label,
            lat = lat,
            lon = lon,
        )
    }
}
