package com.lightfm.radio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@InitialScreen
class HomeScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel> get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel =
        HomeViewModel(
            lightContext.dataStore,
            DefaultLightAudio(sealedActivity),
            runCatching { lightContext.readAsset("fcc_fm.csv") }.getOrNull(),
            runCatching { lightContext.readAsset("iheart_streams.txt") }.getOrNull(),
            runCatching { lightContext.readAsset("npr_streams.txt") }.getOrNull(),
            runCatching { lightContext.readAsset("audacy_streams.txt") }.getOrNull(),
            runCatching { lightContext.readAsset("triton_streams.txt") }.getOrNull(),
            runCatching { lightContext.readAsset("radiogarden_streams.txt") }.getOrNull(),
            runCatching { lightContext.readAsset("manual_streams.txt") }.getOrNull(),
        )

    private fun openLocationSearch() =
        navigateTo({ LocationSearchScreen(it) }) { q: StationQuery? -> q?.let(viewModel::applyLocation) }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.ui.collectAsState()

        LightTheme(colors = colors) {
            Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    when (state.mode) {
                        is HomeMode.Tuned -> RadioHeader(
                            line1 = channelLabel(state.displayChannel),
                            line2 = headerSubtitle(state),
                            onTitle = { openLocationSearch() },
                            onOverflow = { viewModel.openSheet() },
                        )
                        is HomeMode.Empty -> RadioHeader("Set location", null, onTitle = { openLocationSearch() }, onOverflow = null)
                        else -> RadioHeader("LightFM", null, onTitle = null, onOverflow = null)
                    }
                    when (val mode = state.mode) {
                        is HomeMode.Loading -> CenterMessage("Tuning in…", Modifier.weight(1f).fillMaxWidth())
                        is HomeMode.Empty -> CenterMessage(mode.message, Modifier.weight(1f).fillMaxWidth())
                        is HomeMode.Tuned -> {
                            TunerDial(
                                state = state,
                                commands = viewModel.dialCommands,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                onSettled = viewModel::onSettled,
                                onTapChannel = viewModel::tuneAndPlay,
                                onCenter = viewModel::onCenterChanged,
                            )
                            PresetRow(
                                state = state,
                                modifier = Modifier.fillMaxWidth(),
                                onPlay = viewModel::playPreset,
                                onSave = viewModel::savePreset,
                                onAdd = viewModel::addPreset,
                                onRemove = viewModel::removePreset,
                            )
                            BottomBar(state = state, onPlayStop = viewModel::togglePlayStop)
                        }
                    }
                }

                if (state.sheetOpen) {
                    OptionsSheet(
                        snapOn = state.snapToStations,
                        wheelTunes = state.wheelTunes,
                        verticalOn = state.verticalDial,
                        location = state.label,
                        onToggleSnap = viewModel::toggleSnap,
                        onToggleWheel = viewModel::toggleWheelTunes,
                        onToggleVertical = viewModel::toggleVerticalDial,
                        onSetLocation = { viewModel.closeSheet(); openLocationSearch() },
                        onDismiss = viewModel::closeSheet,
                    )
                }

                VolumeHud(
                    visible = state.volumeHudVisible,
                    hint = state.volumeMaxHint,
                    volume = state.volume,
                    onVolume = viewModel::setVolume,
                    onDismissHint = viewModel::dismissVolumeHint,
                )
            }
        }
    }
}

// Top header, L-Message style: big first line (Copy) + dim subheading (Detail), overflow on the right.
@Composable
private fun RadioHeader(line1: String, line2: String?, onTitle: (() -> Unit)?, onOverflow: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp())) {
        Column(
            Modifier.align(Alignment.Center)
                .widthIn(max = 20f.gridUnitsAsDp())
                .let { if (onTitle != null) it.lightClickable { onTitle() } else it },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(line1, variant = LightTextVariant.Copy, align = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line2 != null) {
                LightText(
                    line2,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onOverflow != null) {
            LightIcon(
                icon = LightIcons.ELLIPSES,
                size = 2f,
                modifier = Modifier.align(Alignment.CenterEnd).lightClickable { onOverflow() },
                contentDescription = "Options",
            )
        }
    }
}

// Header subheading: station name • location (from the live centered channel).
private fun headerSubtitle(state: HomeUiState): String {
    val name = state.stationByChannel[state.displayChannel]?.name
    val location = state.label.takeIf { it.isNotBlank() }
    return listOfNotNull(name, location).joinToString(" • ").ifBlank { "—" }
}

@Composable
private fun CenterMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
        )
    }
}

// L-Message-style scrim half-sheet docked at the top.
// Permanent bottom bar: status on the left, play/stop button on the right.
@Composable
private fun BottomBar(state: HomeUiState, onPlayStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (isLive(state)) {
                Box(Modifier.size(0.5f.gridUnitsAsDp()).background(LightThemeTokens.colors.contentSecondary, CircleShape))
                Spacer(Modifier.width(0.4f.gridUnitsAsDp()))
            }
            LightText(statusText(state), variant = LightTextVariant.Detail, lighten = true)
        }
        LightIcon(
            icon = if (state.isPlaying || state.tuning) LightIcons.STOP else LightIcons.PLAY,
            size = 1.5f,
            modifier = Modifier.lightClickable { onPlayStop() },
        )
    }
}

// A live stream shows a circle dot (rendered in BottomBar) + this text.
private fun isLive(state: HomeUiState): Boolean =
    state.error == null && !state.tuning && state.isPlaying && state.volume > 0f && state.current?.hasStream == true

private fun statusText(state: HomeUiState): String = when {
    state.error != null -> state.error
    state.tuning -> "Tuning…"
    state.isPlaying && state.volume <= 0f -> "Muted"
    state.isPlaying && state.current?.hasStream == true -> "LIVE"
    state.isPlaying && state.current != null -> "No stream"
    state.isPlaying -> "Static"
    else -> "" // stopped: the play button already conveys it
}

// Transient volume HUD: slides up from the bottom on a volume change, then fades after a delay. At the
// app's ceiling it shows a hint above the bar (tap to dismiss) — we can't reach the system volume.
@Composable
private fun BoxScope.VolumeHud(
    visible: Boolean,
    hint: Boolean,
    volume: Float,
    onVolume: (Float) -> Unit,
    onDismissHint: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(LightThemeTokens.colors.background)
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.8f.gridUnitsAsDp()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = hint) {
                LightText(
                    "Max app volume, open system volume to get louder.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .lightClickable { onDismissHint() }
                        .padding(bottom = 0.6f.gridUnitsAsDp()),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LightIcon(
                    icon = if (volume <= 0f) LightIcons.SPEAKER_MUTED else LightIcons.SPEAKER_ON,
                    size = 1.8f,
                )
                Spacer(Modifier.width(0.75f.gridUnitsAsDp()))
                LightTouchableProgressBar(
                    colors = LightThemeTokens.colors,
                    progress = volume,
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OptionsSheet(
    snapOn: Boolean,
    wheelTunes: Boolean,
    verticalOn: Boolean,
    location: String,
    onToggleSnap: () -> Unit,
    onToggleWheel: () -> Unit,
    onToggleVertical: () -> Unit,
    onSetLocation: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(LightThemeTokens.colors.background.copy(alpha = 0.7f))
                .lightClickable { onDismiss() },
        )
        Column(
            Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(LightThemeTokens.colors.background)
                .padding(vertical = 0.8f.gridUnitsAsDp()),
        ) {
            SheetRow(label = "Snap to stations", onClick = onToggleSnap, toggle = snapOn)
            SheetRow(label = "Wheel tunes the dial", onClick = onToggleWheel, toggle = wheelTunes)
            SheetRow(label = "Vertical dial", onClick = onToggleVertical, toggle = verticalOn)
            SheetRow(
                label = "Set location",
                onClick = onSetLocation,
                value = "›",
                subtitle = location.substringBefore(",").trim().takeIf { it.isNotBlank() },
            )
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    subtitle: String? = null,
    toggle: Boolean? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.7f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            LightText(label, variant = LightTextVariant.Copy)
            if (subtitle != null) LightText(subtitle, variant = LightTextVariant.Detail, lighten = true)
        }
        when {
            toggle != null -> LightIcon(
                icon = if (toggle) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                width = 3f,
                height = 1.8f,
            )
            value != null -> LightText(value, variant = LightTextVariant.Copy, lighten = true)
        }
    }
}
