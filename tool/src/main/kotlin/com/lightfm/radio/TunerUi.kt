package com.lightfm.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Retro tuner (top half). A header shows the tuned frequency + station name (flipping live as you
 * scroll); below is a free-scrolling, center-snapping FM band; status + play sit under it. Stations
 * with a stream play (▶ LIVE), known stations with none show static ("no stream"), empties are static.
 * The band renders horizontally (car-radio faceplate) or vertically (hi-fi scale) per state.verticalDial.
 */
@Composable
fun TunerDial(
    state: HomeUiState,
    commands: SharedFlow<DialCommand>,
    modifier: Modifier = Modifier,
    onSettled: (Int) -> Unit,
    onTapChannel: (Int) -> Unit,
    onCenter: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val channels = remember { (0 until FM_CHANNEL_COUNT).toList() }
    val snap = rememberSnapFlingBehavior(remember(listState) { SnapLayoutInfoProvider(listState, SnapPosition.Center) })

    val centered by remember { derivedStateOf { listState.centeredIndex() } }

    LaunchedEffect(state.stationByChannel) { listState.scrollToItem(state.currentChannel) }
    // Re-center when the dial flips orientation (the LazyRow ↔ LazyColumn swap shares this listState):
    // jump the new list to the channel that was showing so the tuned frequency stays under the needle.
    LaunchedEffect(state.verticalDial) { listState.scrollToItem(state.displayChannel) }
    // collectLatest: a new tick cancels the in-flight animation and chases the newest target, so a
    // fast spin scrolls smoothly forward instead of queuing one full animation per detent.
    LaunchedEffect(listState) {
        commands.collectLatest { cmd ->
            val target = when (cmd) {
                is DialCommand.By -> listState.centeredIndex() + cmd.delta
                is DialCommand.To -> cmd.index
            }.coerceIn(0, FM_CHANNEL_COUNT - 1)
            listState.animateScrollToItem(target)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .collect { inProgress -> if (!inProgress) onSettled(listState.centeredIndex()) }
    }
    // Report the centered channel up so the header (top bar) flips live as you scroll.
    LaunchedEffect(listState) {
        snapshotFlow { listState.centeredIndex() }.distinctUntilChanged().collect(onCenter)
    }

    val onTap: (Int) -> Unit = { ch ->
        onTapChannel(ch)
        scope.launch { listState.animateScrollToItem(ch) }
    }
    if (state.verticalDial) VerticalBand(modifier, state, listState, snap, channels, centered, onTap)
    else HorizontalBand(modifier, state, listState, snap, channels, centered, onTap)
}

// Horizontal band: ticks stand up from a bottom rail, frequency labels hang below it, center needle
// marks the tuned channel. Free-scrolls left/right, snaps the selected channel under the needle.
@Composable
private fun HorizontalBand(
    modifier: Modifier,
    state: HomeUiState,
    listState: LazyListState,
    snap: FlingBehavior,
    channels: List<Int>,
    centered: Int,
    onTap: (Int) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val itemWidth = 4.2f.gridUnitsAsDp()
        val sidePad = ((maxWidth - itemWidth) / 2f).coerceAtLeast(0.dp)
        val labelSlot = 1.7f.gridUnitsAsDp()
        val railThickness = 1.5.dp // ticks/needle sit on the rail's edge so their alphas don't double up
        val gap = 0.25f.gridUnitsAsDp()
        LazyRow(
            state = listState,
            flingBehavior = snap,
            contentPadding = PaddingValues(horizontal = sidePad),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(channels, key = { _, ch -> ch }) { _, channel ->
                val t = tickStyle(state.stationByChannel[channel], channel == centered)
                // Bottom-aligned: ticks stand on a common baseline, all frequency labels align.
                Column(
                    modifier = Modifier.width(itemWidth).fillMaxHeight().lightClickable { onTap(channel) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(Modifier.width(t.thickness).height(t.depth.gridUnitsAsDp()).background(t.tickColor))
                    Spacer(Modifier.height(gap + railThickness))
                    Box(Modifier.height(labelSlot), contentAlignment = Alignment.Center) {
                        if (t.showLabel(channel)) {
                            LightText(channelLabel(channel), variant = LightTextVariant.Fine, color = t.labelColor)
                        }
                    }
                }
            }
        }
        // Dial scale line: a fixed horizontal rail the ticks stand on (connects every tick).
        Box(
            Modifier.align(Alignment.BottomCenter)
                .offset(y = -(labelSlot + gap))
                .fillMaxWidth()
                .height(railThickness)
                .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.5f)),
        )
        // Center needle, standing on the rail's top edge (like the ticks — no alpha overlap).
        Box(
            Modifier.align(Alignment.BottomCenter)
                .offset(y = -(labelSlot + gap + railThickness))
                .width(4.dp)
                .height(2.8f.gridUnitsAsDp())
                .background(LightThemeTokens.colors.content),
        )
        // Wider than the vertical fade: horizontal channels are ~3.5gu apart (vs 1.7gu tall), so it
        // takes more width to fade the same number of channels into the edge.
        EdgeFades(vertical = false, size = 6f.gridUnitsAsDp(), color = LightThemeTokens.colors.background)
    }
}

// Vertical band: the horizontal one rotated 90° — ticks extend right from a vertical rail, labels sit
// to the left, a horizontal center needle marks the tuned channel. The whole scale is centered.
@Composable
private fun VerticalBand(
    modifier: Modifier,
    state: HomeUiState,
    listState: LazyListState,
    snap: FlingBehavior,
    channels: List<Int>,
    centered: Int,
    onTap: (Int) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val itemHeight = 2.1f.gridUnitsAsDp()
        val sidePad = ((maxHeight - itemHeight) / 2f).coerceAtLeast(0.dp)
        val labelSlot = 3.8f.gridUnitsAsDp() // wide enough for "107.9" on one line, right-aligned to the rail
        val railThickness = 1.5.dp
        val gap = 0.25f.gridUnitsAsDp()
        // Center the whole scale horizontally (label + rail + longest tick) rather than hugging the edge.
        val groupWidth = labelSlot + gap + railThickness + 1.8f.gridUnitsAsDp()
        val leftInset = ((maxWidth - groupWidth) / 2f).coerceAtLeast(0.dp)
        LazyColumn(
            state = listState,
            flingBehavior = snap,
            contentPadding = PaddingValues(top = sidePad, bottom = sidePad, start = leftInset),
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(channels, key = { _, ch -> ch }) { _, channel ->
                val t = tickStyle(state.stationByChannel[channel], channel == centered)
                Row(
                    modifier = Modifier.fillMaxWidth().height(itemHeight).lightClickable { onTap(channel) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Box(Modifier.width(labelSlot), contentAlignment = Alignment.CenterEnd) {
                        if (t.showLabel(channel)) {
                            LightText(channelLabel(channel), variant = LightTextVariant.Fine, color = t.labelColor, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.width(gap + railThickness))
                    Box(Modifier.width(t.depth.gridUnitsAsDp()).height(t.thickness).background(t.tickColor))
                }
            }
        }
        // Vertical rail the ticks stand on (their left edge meets its right edge — no alpha overlap).
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset(x = leftInset + labelSlot + gap)
                .fillMaxHeight()
                .width(railThickness)
                .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.5f)),
        )
        // Center needle, on the rail's right edge (like the ticks), marking the tuned channel.
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset(x = leftInset + labelSlot + gap + railThickness)
                .height(4.dp)
                .width(2.8f.gridUnitsAsDp())
                .background(LightThemeTokens.colors.content),
        )
        EdgeFades(vertical = true, size = 3.5f.gridUnitsAsDp(), color = LightThemeTokens.colors.background)
    }
}

// Fades the band into the background at the two edges it scrolls toward (top/bottom vertical,
// left/right horizontal), so channels dissolve out near the ends instead of hard-clipping.
@Composable
private fun BoxScope.EdgeFades(vertical: Boolean, size: Dp, color: Color) {
    if (vertical) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(size).background(Brush.verticalGradient(listOf(color, Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(size).background(Brush.verticalGradient(listOf(Color.Transparent, color))))
    } else {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(size).background(Brush.horizontalGradient(listOf(color, Color.Transparent))))
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(size).background(Brush.horizontalGradient(listOf(Color.Transparent, color))))
    }
}

// Shared per-channel styling. depth = tick's long axis (grid units), thickness = its short axis; the
// selected channel's label brightens to full content, ticks stay faint (number + needle carry the dial),
// and a known station we can't stream dims so streamable ones pop.
private class TickStyle(
    val depth: Float,
    val thickness: Dp,
    val tickColor: Color,
    val labelColor: Color,
    private val hasStation: Boolean,
    private val selected: Boolean,
) {
    // Sparse labels, dense ticks: show a number only for stations, the selected channel, or every 2 MHz.
    fun showLabel(channel: Int): Boolean = hasStation || selected || channel % 10 == 0
}

@Composable
private fun tickStyle(entry: Station?, selected: Boolean): TickStyle {
    val hasStation = entry != null
    val playable = entry?.hasStream == true
    val depth = when {
        playable -> 1.8f
        hasStation -> 1.3f
        else -> 0.8f
    }
    val base = if (selected) LightThemeTokens.colors.content else LightThemeTokens.colors.contentSecondary
    // A known station we can't stream (call sign only → static) reads clearly dimmer so streamable
    // ones pop; the selected channel still lifts a bit (via base) so the tuned frequency stays legible.
    val labelColor = if (hasStation && !playable) base.copy(alpha = 0.3f) else base
    val tickColor = base.copy(alpha = if (hasStation && !playable) 0.15f else 0.45f)
    return TickStyle(depth, if (hasStation) 2.dp else 1.dp, tickColor, labelColor, hasStation, selected)
}

private fun LazyListState.centeredIndex(): Int {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return firstVisibleItemIndex
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }?.index
        ?: firstVisibleItemIndex
}
