package com.lightfm.radio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Maps location → stations, caches the last dial for offline relaunch, and owns preset
 * read/write. DataStore keys share the tool's single "DEFAULT_DATASTORE" so they're namespaced.
 */
class StationRepository(
    private val dataStore: DataStore<Preferences>,
    private val client: RadioBrowserClient,
    private val location: LocationSource,
) {
    /** Online path: IP-geo → Radio Browser. null only if location can't be resolved at all. */
    suspend fun locateAndFetch(): LocatedStations? {
        val q = location.resolve() ?: return null
        return fetch(q)
    }

    /** Fetch local stations for a resolved query (from IP-geo or picked location). */
    suspend fun fetch(q: StationQuery): LocatedStations {
        val byState = runCatching { client.stations(q.countryCode, q.state, q.lat, q.lon) }
            .onFailure { android.util.Log.w("LightFM", "station fetch failed", it) }
            .getOrNull().orEmpty()
        // Non-US regions rarely match RB's state naming — drop the state filter if it came back empty.
        val stations = byState.ifEmpty {
            if (q.state != null) runCatching { client.stations(q.countryCode, null, q.lat, q.lon) }.getOrNull().orEmpty()
            else emptyList()
        }
        return LocatedStations(q.label, stations, q.lat, q.lon).also { if (stations.isNotEmpty()) cache(it) }
    }

    suspend fun cached(): LocatedStations? {
        val prefs = dataStore.data.first()
        val json = prefs[K_STATIONS] ?: return null
        val label = prefs[K_LABEL] ?: return null
        val stations = runCatching { LightFmJson.decodeFromString<List<Station>>(json) }.getOrNull() ?: return null
        return LocatedStations(label, stations, prefs[K_LAT]?.toDoubleOrNull(), prefs[K_LON]?.toDoubleOrNull())
    }

    private suspend fun cache(located: LocatedStations) {
        runCatching {
            dataStore.edit {
                it[K_STATIONS] = LightFmJson.encodeToString(located.stations)
                it[K_LABEL] = located.label
                located.lat?.let { v -> it[K_LAT] = v.toString() }
                located.lon?.let { v -> it[K_LON] = v.toString() }
            }
        }
    }

    suspend fun presets(): List<RadioPreset?> {
        val json = dataStore.data.first()[K_PRESETS] ?: return List(PRESET_COUNT) { null }
        val saved = runCatching { LightFmJson.decodeFromString<List<RadioPreset>>(json) }.getOrDefault(emptyList())
        // At least PRESET_COUNT slots, but grow to fit any the user added past that.
        val size = maxOf(PRESET_COUNT, (saved.maxOfOrNull { it.slot } ?: -1) + 1)
        return List(size) { slot -> saved.firstOrNull { it.slot == slot } }
    }

    suspend fun savePresets(presets: List<RadioPreset?>) {
        runCatching {
            dataStore.edit { it[K_PRESETS] = LightFmJson.encodeToString(presets.filterNotNull()) }
        }
    }

    /** The location the user explicitly picked, if any — preferred over IP-geo on next launch. */
    suspend fun savedQuery(): StationQuery? {
        val json = dataStore.data.first()[K_USER_QUERY] ?: return null
        return runCatching { LightFmJson.decodeFromString<StationQuery>(json) }.getOrNull()
    }

    suspend fun saveQuery(q: StationQuery) {
        runCatching { dataStore.edit { it[K_USER_QUERY] = LightFmJson.encodeToString(q) } }
    }

    suspend fun snapEnabled(): Boolean = dataStore.data.first()[K_SNAP] ?: false

    suspend fun saveSnap(enabled: Boolean) {
        runCatching { dataStore.edit { it[K_SNAP] = enabled } }
    }

    suspend fun wheelTunes(): Boolean = dataStore.data.first()[K_WHEEL] ?: true

    suspend fun saveWheelTunes(enabled: Boolean) {
        runCatching { dataStore.edit { it[K_WHEEL] = enabled } }
    }

    suspend fun verticalDial(): Boolean = dataStore.data.first()[K_VERTICAL] ?: false

    suspend fun saveVerticalDial(enabled: Boolean) {
        runCatching { dataStore.edit { it[K_VERTICAL] = enabled } }
    }

    suspend fun volume(): Float = dataStore.data.first()[K_VOL] ?: 0.85f

    suspend fun saveVolume(v: Float) {
        runCatching { dataStore.edit { it[K_VOL] = v } }
    }

    companion object {
        private val K_STATIONS = stringPreferencesKey("lightfm_stations")
        private val K_LABEL = stringPreferencesKey("lightfm_location_label")
        private val K_LAT = stringPreferencesKey("lightfm_lat")
        private val K_LON = stringPreferencesKey("lightfm_lon")
        private val K_PRESETS = stringPreferencesKey("lightfm_presets")
        private val K_USER_QUERY = stringPreferencesKey("lightfm_user_query")
        private val K_SNAP = booleanPreferencesKey("lightfm_snap")
        private val K_WHEEL = booleanPreferencesKey("lightfm_wheel_tunes")
        private val K_VERTICAL = booleanPreferencesKey("lightfm_vertical_dial")
        private val K_VOL = floatPreferencesKey("lightfm_volume")
    }
}
