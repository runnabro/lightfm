package com.lightfm.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Wider-than-tall so the tiles read as rectangles, not squares.
private const val CELL_ASPECT = 1.6f

// Progressive hold: hold to save, keep holding for the remove hint, hold through it to remove.
private const val PRESET_SAVE_MS = 500L
private const val PRESET_REMOVE_HINT_MS = 600L // after save, before the "keep holding to remove" hint
private const val PRESET_REMOVE_MS = 900L      // after the hint, before the slot is cleared

/**
 * Memory presets as filled, L-Message-avatar-style tiles on a horizontally-scrolling row. ~3.5 (wide)
 * tiles show at once (the 4th is cropped as the scroll affordance). Tap a saved slot to recall + play;
 * hold to save the tuned station into it; keep holding to remove it. Once every slot is filled, a "+"
 * appends the tuned station as a new slot.
 */
@Composable
fun PresetRow(
    state: HomeUiState,
    modifier: Modifier = Modifier,
    onPlay: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    var removeHint by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.75f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        val spacing = 0.6f.gridUnitsAsDp()
        // 3.5 tiles visible → the 4th peeks in, signalling there's more to scroll.
        val cellWidth = (maxWidth - spacing * 3) / 3.5f
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(state.presets.size) { slot ->
                PresetCell(state, slot, Modifier.width(cellWidth), onPlay, onSave, onRemove) { removeHint = it }
            }
            if (state.canAddPreset) {
                item { AddCell(Modifier.width(cellWidth), onAdd) }
            }
        }
        if (removeHint) {
            LightText(
                "Keep holding to remove.",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter)
                    .offset(y = -(0.9f.gridUnitsAsDp()))
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 0.5f.gridUnitsAsDp(), vertical = 0.15f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun PresetCell(
    state: HomeUiState,
    slot: Int,
    modifier: Modifier,
    onPlay: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onRemoveHint: (Boolean) -> Unit,
) {
    val preset = state.presets.getOrNull(slot)
    // Highlight the specific slot that was recalled — not every slot holding the same station.
    val active = preset != null && slot == state.activePresetSlot && state.isPlaying
    val colors = LightThemeTokens.colors
    val shape = RoundedCornerShape(0.35f.gridUnitsAsDp())
    // Filled tile, faint dark-gray fill; the playing slot fills a little more (still subtle).
    val fill = colors.contentSecondary.copy(alpha = if (active) 0.1f else 0.05f)
    val textColor = when {
        preset != null -> colors.content       // saved/playing: bright text
        else -> colors.contentSecondary        // empty: dim
    }
    val subLabel = when {
        preset == null -> "—"
        preset.station.frequency != null -> "%.1f".format(preset.station.frequency)
        else -> preset.station.name
    }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .aspectRatio(CELL_ASPECT)
            .background(fill, shape)
            // Tap = play; hold = save; keep holding → "keep holding to remove" → clear the slot.
            .pointerInput(slot) {
                awaitEachGesture {
                    awaitFirstDown()
                    var saved = false
                    val hold = scope.launch {
                        delay(PRESET_SAVE_MS)
                        saved = true
                        onSave(slot)
                        delay(PRESET_REMOVE_HINT_MS)
                        onRemoveHint(true)
                        delay(PRESET_REMOVE_MS)
                        onRemove(slot)
                        onRemoveHint(false)
                    }
                    val up = waitForUpOrCancellation()
                    hold.cancel()
                    onRemoveHint(false)
                    if (!saved && up != null) onPlay(slot)
                }
            }
            .padding(horizontal = 0.25f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(text = "${slot + 1}", variant = LightTextVariant.Copy, color = textColor)
        LightText(
            text = subLabel,
            variant = LightTextVariant.Superfine,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = textColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AddCell(modifier: Modifier, onAdd: () -> Unit) {
    // Plain "+" (the SDK's only plus icon, ADD, is circled), no background.
    Box(
        modifier = modifier.aspectRatio(CELL_ASPECT).lightClickable { onAdd() },
        contentAlignment = Alignment.Center,
    ) {
        LightText("+", variant = LightTextVariant.Subtitle, color = LightThemeTokens.colors.contentSecondary)
    }
}
