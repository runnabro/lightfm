package com.lightfm.radio

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A licensed US FM station from the bundled FCC table (call sign + real frequency + coordinates). */
data class FccStation(val callSign: String, val frequency: Double, val lat: Double, val lon: Double)

object FccStations {

    /** Parse the bundled `fcc_fm.csv` (call,freq,lat,lon per line). */
    fun parse(bytes: ByteArray?): List<FccStation> {
        if (bytes == null) return emptyList()
        return String(bytes, Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 4 || p[0].isBlank()) return@mapNotNull null
            val freq = p[1].toDoubleOrNull() ?: return@mapNotNull null
            val lat = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val lon = p[3].toDoubleOrNull() ?: return@mapNotNull null
            FccStation(p[0], freq, lat, lon)
        }.toList()
    }

    /** Nearest FCC station per FM channel within [radiusKm] of (lat,lon). */
    fun near(all: List<FccStation>, lat: Double, lon: Double, radiusKm: Double): Map<Int, FccStation> {
        val map = LinkedHashMap<Int, FccStation>()
        all.asSequence()
            .mapNotNull { s -> channelForFrequency(s.frequency)?.let { ch -> Triple(ch, s, haversineKm(lat, lon, s.lat, s.lon)) } }
            .filter { it.third <= radiusKm }
            .sortedBy { it.third }
            .forEach { (ch, s, _) -> map.putIfAbsent(ch, s) }
        return map
    }
}

fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * 6371.0 * asin(min(1.0, sqrt(a)))
}
