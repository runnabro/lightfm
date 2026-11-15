package com.lightfm.radio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON — tolerant of Radio Browser's extra fields and our own schema drift. */
val LightFmJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A playable station. `url` is Radio Browser's resolved (HTTPS) stream URL. */
@Serializable
data class Station(
    val uuid: String,
    val name: String,
    val url: String,
    val codec: String? = null,
    val bitrate: Int = 0,
    val tags: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val frequency: Double? = null, // real FM frequency (FCC, or parsed from the name)
    val callSign: String? = null,  // FCC call sign when this channel is FCC-backed
)

// Formats the stock SDK's ExoPlayer can't play — DASH/SmoothStreaming (no modules) and raw playlists
// (.pls/.m3u aren't media sources). HLS (.m3u8) IS playable via the media3-exoplayer-hls dep added by
// install-into-sdk.sh. Guarding here keeps a stray unsupported URL (e.g. from Radio Browser) from
// crashing the player — it just shows the call sign and plays static instead.
private val UNPLAYABLE_STREAM = Regex("""\.(pls|m3u|mpd|ism|isml)(\?.*)?$""", RegexOption.IGNORE_CASE)

/** Whether this dial entry has a playable stream (vs a real station we know of but can't stream). */
val Station.hasStream: Boolean
    get() = url.startsWith("https://") && !UNPLAYABLE_STREAM.containsMatchIn(url)

/** A saved memory slot (0..5) holding a whole station so it plays even off the current dial. */
@Serializable
data class RadioPreset(val slot: Int, val station: Station)

/** Location resolved to a Radio Browser query + a human label for the top bar. */
@Serializable
data class StationQuery(
    val countryCode: String?,
    val state: String?,
    val label: String,
    val lat: Double? = null,
    val lon: Double? = null,
)

/** ViewModel → dial: move the tuner by a delta (wheel) or to a channel (recall/tap). */
sealed interface DialCommand {
    data class By(val delta: Int) : DialCommand
    data class To(val index: Int) : DialCommand
}

/** A location the user picked from autocomplete (city or ZIP). */
data class GeoChoice(
    val label: String,
    val countryCode: String?,
    val state: String?,
    val lat: Double,
    val lon: Double,
) {
    fun toQuery() = StationQuery(countryCode, state, label, lat, lon)
}

/** Stations plus the label + coords they were fetched for; cached for offline relaunch. */
data class LocatedStations(
    val label: String,
    val stations: List<Station>,
    val lat: Double? = null,
    val lon: Double? = null,
)

sealed interface HomeMode {
    data object Loading : HomeMode
    data class Tuned(val label: String) : HomeMode
    data class Empty(val message: String) : HomeMode
}

data class HomeUiState(
    val mode: HomeMode = HomeMode.Loading,
    val label: String = "",
    val stationByChannel: Map<Int, Station> = emptyMap(),
    val currentChannel: Int = 0,
    val displayChannel: Int = 0, // live centered channel (drives the header; leads currentChannel while scrolling)
    val isPlaying: Boolean = false,
    val tuning: Boolean = false,
    val presets: List<RadioPreset?> = List(PRESET_COUNT) { null },
    val activePresetSlot: Int? = null, // which preset was recalled (highlight only that one, not dupes)
    val error: String? = null,
    val snapToStations: Boolean = false,
    val wheelTunes: Boolean = true,
    val verticalDial: Boolean = false,
    val sheetOpen: Boolean = false,
) {
    val current: Station? get() = stationByChannel[currentChannel]
    val hasStations: Boolean get() = stationByChannel.isNotEmpty()
    // Offer "+" (add another slot) only once every existing slot is filled.
    val canAddPreset: Boolean get() = presets.isNotEmpty() && presets.all { it != null }
}

const val PRESET_COUNT = 6 // minimum memory slots; grows past this via the + button

// US FM band: 88.1 → 107.9 MHz in 0.2 steps = 100 channels.
const val FM_MIN_FREQ = 88.1
const val FM_STEP = 0.2
const val FM_CHANNEL_COUNT = 100

fun channelFrequency(channel: Int): Double = FM_MIN_FREQ + channel * FM_STEP
fun channelLabel(channel: Int): String = "%.1f".format(channelFrequency(channel))

/** Nearest dial channel for a real frequency, or null if outside the band. */
fun channelForFrequency(freq: Double): Int? {
    val c = Math.round((freq - FM_MIN_FREQ) / FM_STEP).toInt()
    return if (c in 0 until FM_CHANNEL_COUNT) c else null
}
