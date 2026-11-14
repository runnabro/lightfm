package com.lightfm.radio

import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow

// LP3 hardware dimmer wheel, delivered as Android keycodes (see light-keyboard LightDeviceKeys).
private const val KEY_ROTARY_UP = 317      // one detent "up"   → next channel / station
private const val KEY_ROTARY_DOWN = 318    // one detent "down" → previous
private const val KEY_ROTARY_PRESS = 319   // press the wheel   → play / stop
private const val KEY_VOL_UP = 24
private const val KEY_VOL_DOWN = 25
private const val VOL_STEP = 0.08f
private const val VOLUME_TAPER_DB = 40f // fader range so equal slider steps ≈ equal loudness change

// player.volume is a LINEAR gain (0=silence, 1=unity); perceived loudness is logarithmic, so a linear
// step near the top barely changes loudness. Map the 0..1 slider position through a dB taper instead,
// so each press feels like a real volume step (can't raise the ceiling — that's the system volume).
private fun perceptualGain(position: Float): Float = when {
    position <= 0f -> 0f
    position >= 1f -> 1f
    else -> 10f.pow((position - 1f) * VOLUME_TAPER_DB / 20f)
}

private const val STREAM_WATCHDOG_MS = 7_000L // no error flow exists → infer a dead stream
private const val STATIC_LOOP = 120           // repeats of the ~6s static asset (~12 min)
private const val VOLUME_HUD_MS = 1_800L      // how long the volume HUD lingers after a change
private const val VOLUME_HINT_MS = 4_000L     // longer linger when showing the "at max" hint, to read it
private const val FCC_RADIUS_KM = 90.0        // FM reception radius for the local dial

class HomeViewModel(
    dataStore: DataStore<Preferences>,
    audio: LightAudio,
    private val fccCsv: ByteArray?,
    private val overridesTsv: ByteArray?,
    private val nprTsv: ByteArray?,
    private val audacyTsv: ByteArray?,
    private val tritonTsv: ByteArray?,
    private val radioGardenTsv: ByteArray?,
    private val manualTsv: ByteArray?,
) : LightViewModel<Unit>() {

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(LightFmJson) }
        install(DefaultRequest) { header(HttpHeaders.UserAgent, "LightFM/0.0.1") }
    }
    private val client = RadioBrowserClient(http)
    private val repo = StationRepository(dataStore, client, IpGeoLocationSource(http))
    // Detached = streaming survives the screen going off / leaving the tool (SDK runs a foreground
    // MediaSessionService), the supported replacement for the old wake-mode hack.
    private val player: LightAudioPlayer = audio.newPlayer(LightAudioUsage.Music, LightAudioPlayback.Detached)

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    // ViewModel → dial: move to a channel (wheel resolves By into a To respecting snap).
    private val _dialCommands = MutableSharedFlow<DialCommand>(extraBufferCapacity = 16)
    val dialCommands: SharedFlow<DialCommand> = _dialCommands.asSharedFlow()

    private var playIntent = false
    private var sessionCounter = 0
    private var watchdogJob: Job? = null
    private var hudHideJob: Job? = null
    private var wheelTarget = -1 // where the wheel is steering the dial; accumulates across a spin, -1 when idle
    private var fccStations: List<FccStation> = emptyList()
    private var streamOverrides: Map<String, StreamOverride> = emptyMap()

    init {
        viewModelScope.launch {
            player.isPlaying.collect { playing ->
                _ui.update { it.copy(isPlaying = playing, tuning = if (playing) false else it.tuning) }
                if (playing) {
                    watchdogJob?.cancel()
                    _ui.update { it.copy(error = null) }
                }
            }
        }
        bootstrap()
    }

    private fun bootstrap() = viewModelScope.launch {
        _ui.update {
            it.copy(
                mode = HomeMode.Loading,
                presets = repo.presets(),
                snapToStations = repo.snapEnabled(),
                wheelTunes = repo.wheelTunes(),
                verticalDial = repo.verticalDial(),
            )
        }
        withContext(Dispatchers.Default) {
            fccStations = FccStations.parse(fccCsv)
            // Merge stream catalogs by call sign; later sources win. Order low→high precedence:
            // radio.garden (local/community long tail) < Triton probe < NPR (public) < Audacy
            // (commercial) < iHeart (curated) < manual_streams.tsv (hand-verified pins, always win).
            streamOverrides = StreamOverrides.parse(radioGardenTsv) +
                StreamOverrides.parse(tritonTsv) +
                StreamOverrides.parse(nprTsv) +
                StreamOverrides.parse(audacyTsv) +
                StreamOverrides.parse(overridesTsv) +
                StreamOverrides.parse(manualTsv)
        }
        val vol = repo.volume()
        _ui.update { it.copy(volume = vol) }
        player.volume = perceptualGain(vol)
        // Prefer a location the user explicitly set; only IP-geo when they never picked one.
        // A live fetch can resolve location but come back with no stations (RB mirror hiccup / slow
        // network). Fall back to the last good cache on empty, not just on a null location.
        val saved = withContext(Dispatchers.IO) { repo.savedQuery() }
        val fresh = withContext(Dispatchers.IO) { if (saved != null) repo.fetch(saved) else repo.locateAndFetch() }
        val located = fresh?.takeIf { it.stations.isNotEmpty() }
            ?: withContext(Dispatchers.IO) { repo.cached() }?.takeIf { it.stations.isNotEmpty() }
            ?: fresh
        when {
            located == null -> _ui.update { it.copy(mode = HomeMode.Empty("Couldn't detect your location.\n\nTap the title to set it.")) }
            // The dial is built from bundled FCC + stream data using the location's lat/lon, so it
            // works fully offline (Radio Browser is only a bonus layer) — let showTuned decide.
            else -> showTuned(located)
        }
    }

    private fun showTuned(located: LocatedStations) {
        val map = buildDial(located)
        if (map.isEmpty()) {
            _ui.update { it.copy(mode = HomeMode.Empty("No stations found for ${located.label}")) }
            return
        }
        val initial = map.keys.firstOrNull() ?: 0
        _ui.update {
            it.copy(
                mode = HomeMode.Tuned(located.label),
                label = located.label,
                stationByChannel = map,
                currentChannel = initial,
                displayChannel = initial,
                error = null,
            )
        }
    }

    // The real FM dial: every licensed FCC station in range at its true frequency (nearest per
    // channel), with a Radio Browser stream where the call sign matches — else it's a known station
    // with no stream (plays static). RB-only stations that carry a frequency fill any gaps.
    private fun buildDial(located: LocatedStations): Map<Int, Station> {
        val dial = LinkedHashMap<Int, Station>()
        val rb = located.stations
        val rbTokens = rb.map { s ->
            s to (s.name + " " + (s.tags ?: "")).uppercase().split(Regex("[^A-Z0-9]+")).toHashSet()
        }
        fun matchRb(call: String): Station? {
            val c = call.uppercase()
            return rbTokens.firstOrNull { c in it.second }?.first
        }
        // Fallback: many RB stations list a brand ("Wild 94.9") not a call sign, so the token match
        // misses. Grab the nearest RB station sitting on this exact channel instead (rb is nearest-first).
        fun matchRbByFreq(ch: Int): Station? =
            rb.firstOrNull { it.frequency?.let(::channelForFrequency) == ch }

        val lat = located.lat
        val lon = located.lon
        if (lat != null && lon != null && fccStations.isNotEmpty()) {
            for ((ch, f) in FccStations.near(fccStations, lat, lon, FCC_RADIUS_KM)) {
                // Prefer a known stream (iHeart commercial / NPR public), then a Radio Browser match
                // by call sign, then any RB station broadcasting on this frequency.
                val ov = streamOverrides[f.callSign.uppercase()]
                val m = if (ov == null) matchRb(f.callSign) ?: matchRbByFreq(ch) else null
                dial[ch] = Station(
                    uuid = ov?.let { "ov_${f.callSign}" } ?: m?.uuid ?: f.callSign,
                    name = ov?.name ?: m?.name ?: f.callSign,
                    url = ov?.url ?: m?.url ?: "",
                    frequency = f.frequency,
                    callSign = f.callSign,
                )
            }
        }
        for (s in rb) {
            val ch = s.frequency?.let(::channelForFrequency) ?: continue
            if (ch !in dial) dial[ch] = s
        }
        return dial
    }

    // ---- Dimmer wheel (delivered to the ViewModel; return true to consume) ----

    // When wheel-tuning is off, don't consume the rotary turns — they fall through to LightOS
    // (screen brightness). The press (play/stop) and volume buttons always work.
    // Consume down/multiple/up alike: anything we leave unconsumed gets forwarded to LightOS, which
    // steps the screen brightness off it — so a half-consumed detent tunes AND dims.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KEY_ROTARY_UP -> tuneByWheel(+1)
        KEY_ROTARY_DOWN -> tuneByWheel(-1)
        KEY_ROTARY_PRESS -> { togglePlayStop(); true }
        KEY_VOL_UP -> { volumeUp(); true }
        KEY_VOL_DOWN -> { volumeDown(); true }
        else -> false
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean = when (keyCode) {
        KEY_ROTARY_UP -> tuneByWheel(+1)
        KEY_ROTARY_DOWN -> tuneByWheel(-1)
        KEY_ROTARY_PRESS -> true
        KEY_VOL_UP -> { volumeUp(); true }
        KEY_VOL_DOWN -> { volumeDown(); true }
        else -> false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KEY_ROTARY_UP, KEY_ROTARY_DOWN -> _ui.value.wheelTunes
        KEY_ROTARY_PRESS, KEY_VOL_UP, KEY_VOL_DOWN -> true
        else -> false
    }

    private fun tuneByWheel(delta: Int): Boolean {
        if (!_ui.value.wheelTunes) return false
        // Accumulate from the running wheel target (not the lagging center) so a fast spin keeps
        // advancing; the dial chases the latest target continuously. Reset to -1 on settle.
        // The wheel steps channel-by-channel through everything (incl. the empties between stations) —
        // only a fling/swipe snaps to stations (see onSettled), so you can glide to any frequency.
        val base = wheelTarget.takeIf { it in 0 until FM_CHANNEL_COUNT } ?: _ui.value.displayChannel
        wheelTarget = (base + delta).coerceIn(0, FM_CHANNEL_COUNT - 1)
        _dialCommands.tryEmit(DialCommand.To(wheelTarget))
        return true
    }

    // Volume buttons: step up until the app's ceiling, then surface the "go to system volume" hint.
    fun volumeUp() {
        val next = (_ui.value.volume + VOL_STEP).coerceAtMost(1f)
        setVolumeInternal(next, hint = next >= 1f)
    }

    fun volumeDown() = setVolumeInternal((_ui.value.volume - VOL_STEP).coerceAtLeast(0f), hint = false)

    fun setVolume(v: Float) = setVolumeInternal(v.coerceIn(0f, 1f), hint = false)

    fun dismissVolumeHint() {
        hudHideJob?.cancel()
        _ui.update { it.copy(volumeHudVisible = false, volumeMaxHint = false) }
    }

    private fun setVolumeInternal(vol: Float, hint: Boolean) {
        _ui.update { it.copy(volume = vol, volumeHudVisible = true, volumeMaxHint = hint) }
        player.volume = perceptualGain(vol)
        viewModelScope.launch { repo.saveVolume(vol) }
        hudHideJob?.cancel()
        hudHideJob = viewModelScope.launch {
            delay(if (hint) VOLUME_HINT_MS else VOLUME_HUD_MS)
            _ui.update { it.copy(volumeHudVisible = false, volumeMaxHint = false) }
        }
    }

    /** Play the tuned channel, or fully stop the stream (so it doesn't keep streaming when idle). */
    fun togglePlayStop() {
        if (_ui.value.isPlaying || _ui.value.tuning) stopPlayback() else playChannel(_ui.value.currentChannel)
    }


    // Snap only lands on channels that actually have a playable stream.
    private fun playableChannels(): List<Int> =
        _ui.value.stationByChannel.filterValues { it.hasStream }.keys.sorted()

    private fun nextInKeys(keys: List<Int>, from: Int, dir: Int): Int {
        if (keys.isEmpty()) return (from + dir).coerceIn(0, FM_CHANNEL_COUNT - 1)
        return if (dir > 0) keys.firstOrNull { it > from } ?: keys.first()
        else keys.lastOrNull { it < from } ?: keys.last()
    }

    /** The dial finished scrolling and landed on [channel]. Retune only now (never mid-scroll). */
    fun onSettled(channel: Int) {
        if (channel !in 0 until FM_CHANNEL_COUNT) return
        val byWheel = wheelTarget != -1 // wheel tunes precisely; only a fling/swipe snaps to stations
        wheelTarget = -1 // dial came to rest → next wheel spin starts from here

        if (_ui.value.snapToStations && !byWheel) {
            val snap = playableChannels()
            if (snap.isNotEmpty() && channel !in snap) {
                val nearest = snap.minByOrNull { abs(it - channel) }!!
                if (nearest != channel) { _dialCommands.tryEmit(DialCommand.To(nearest)); return }
            }
        }
        if (channel == _ui.value.currentChannel) return
        _ui.update { it.copy(currentChannel = channel, activePresetSlot = null) }
        if (playIntent) playChannel(channel) // only retune if actively listening
    }

    /** Live centered channel while scrolling — drives the header readout. */
    fun onCenterChanged(channel: Int) {
        if (channel !in 0 until FM_CHANNEL_COUNT) return
        // Header always follows the center. While idle (not actively listening), keep the play/save
        // target on it too, so both act on what's shown (they'd otherwise diverge on launch, before
        // any scroll fires onSettled). While playing, the station stays put as you browse.
        _ui.update {
            if (playIntent) it.copy(displayChannel = channel)
            else it.copy(displayChannel = channel, currentChannel = channel)
        }
    }

    /** Tap a channel on the dial: tune to it and play. */
    fun tuneAndPlay(channel: Int) {
        if (channel !in 0 until FM_CHANNEL_COUNT) return
        _ui.update { it.copy(currentChannel = channel, activePresetSlot = null) }
        playChannel(channel)
    }

    private fun playChannel(channel: Int) {
        val station = _ui.value.stationByChannel[channel]
        if (station != null && station.hasStream) playStation(station) else playStatic()
    }

    private fun playStation(station: Station) {
        playIntent = true
        _ui.update { it.copy(tuning = true, error = null) }
        player.setMediaQueue(
            listOf(LightAudioItem(LightAudioSource.UrlSource(station.url), LightMediaMetadata(title = station.name))),
            startIndex = 0,
        )
        player.play()
        player.volume = perceptualGain(_ui.value.volume)
        startWatchdog()
    }

    // Empty channel → local static loop. Plays instantly (no network), so no watchdog.
    private fun playStatic() {
        playIntent = true
        watchdogJob?.cancel()
        _ui.update { it.copy(tuning = false, error = null) }
        val items = List(STATIC_LOOP) {
            LightAudioItem(LightAudioSource.AssetSource("static.wav"), LightMediaMetadata(title = "Static"))
        }
        player.setMediaQueue(items, startIndex = 0)
        player.play()
        player.volume = perceptualGain(_ui.value.volume)
    }

    private fun stopPlayback() {
        playIntent = false
        watchdogJob?.cancel()
        player.stop()
        _ui.update { it.copy(tuning = false, activePresetSlot = null) }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            delay(STREAM_WATCHDOG_MS)
            if (!playIntent) return@launch
            if (player.isPlaying.value) {
                // Reconcile the UI if playback began before the tuning state was set or its
                // isPlaying notification was missed while replacing the queue.
                _ui.update { it.copy(isPlaying = true, tuning = false, error = null) }
            } else {
                _ui.update { it.copy(tuning = false, error = "Station unavailable") }
                val playable = playableChannels()
                if (playable.size > 1) {
                    _dialCommands.tryEmit(DialCommand.To(nextInKeys(playable, _ui.value.currentChannel, +1)))
                }
            }
        }
    }

    // ---- Presets ----

    fun playPreset(slot: Int) {
        val preset = _ui.value.presets.getOrNull(slot) ?: return
        val channel = preset.station.frequency?.let(::channelForFrequency) ?: return
        _ui.update {
            it.copy(stationByChannel = it.stationByChannel + (channel to preset.station), currentChannel = channel, activePresetSlot = slot)
        }
        _dialCommands.tryEmit(DialCommand.To(channel)) // move the dial (map may be content-equal → no auto-scroll)
        playStation(preset.station)
    }

    /** Save the currently tuned station into a memory slot (only real, streamable stations). */
    fun savePreset(slot: Int) {
        val station = _ui.value.current?.takeIf { it.hasStream } ?: return
        val updated = _ui.value.presets.toMutableList().also { it[slot] = RadioPreset(slot, station) }
        _ui.update { it.copy(presets = updated) }
        viewModelScope.launch { repo.savePresets(updated) }
    }

    /** Append the tuned station as a new memory slot (the "+" button, once all slots are filled). */
    fun addPreset() {
        val station = _ui.value.current?.takeIf { it.hasStream } ?: return
        val slot = _ui.value.presets.size
        val updated = _ui.value.presets + RadioPreset(slot, station)
        _ui.update { it.copy(presets = updated) }
        viewModelScope.launch { repo.savePresets(updated) }
    }

    /** Clear a memory slot (the "keep holding to remove" gesture), trimming any trailing empties. */
    fun removePreset(slot: Int) {
        val list = _ui.value.presets.toMutableList()
        if (slot !in list.indices || list[slot] == null) return
        list[slot] = null
        while (list.size > PRESET_COUNT && list.last() == null) list.removeAt(list.lastIndex)
        _ui.update { it.copy(presets = list) }
        viewModelScope.launch { repo.savePresets(list) }
    }

    // ---- Overflow sheet + snap ----

    fun openSheet() = _ui.update { it.copy(sheetOpen = true) }
    fun closeSheet() = _ui.update { it.copy(sheetOpen = false) }

    fun toggleSnap() {
        val enabled = !_ui.value.snapToStations
        _ui.update { it.copy(snapToStations = enabled) }
        viewModelScope.launch { repo.saveSnap(enabled) }
    }

    fun toggleWheelTunes() {
        val enabled = !_ui.value.wheelTunes
        _ui.update { it.copy(wheelTunes = enabled) }
        viewModelScope.launch { repo.saveWheelTunes(enabled) }
    }

    fun toggleVerticalDial() {
        val enabled = !_ui.value.verticalDial
        _ui.update { it.copy(verticalDial = enabled) }
        viewModelScope.launch { repo.saveVerticalDial(enabled) }
    }

    // ---- Location (from autocomplete) ----

    fun applyLocation(query: StationQuery) {
        viewModelScope.launch {
            _ui.update { it.copy(mode = HomeMode.Loading, sheetOpen = false) }
            withContext(Dispatchers.IO) { repo.saveQuery(query) }
            val located = withContext(Dispatchers.IO) { repo.fetch(query) }
            stopPlayback()
            showTuned(located) // builds the FCC dial from lat/lon even if RB came back empty (offline)
        }
    }

    override fun onCleared() {
        watchdogJob?.cancel()
        hudHideJob?.cancel()
        player.release()
        http.close()
        super.onCleared()
    }
}
