package com.lightfm.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay

// Type a city or US ZIP on the docked keyboard; matching locations autocomplete below. Returns the
// chosen location as a StationQuery (or null on back).
class LocationSearchScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<StationQuery?>(sealedActivity) {

    @Composable
    override fun Content() {
        val state = rememberTextFieldState()
        val themeColors by LightThemeController.colors.collectAsState()
        val http = remember { HttpClient(OkHttp) { install(ContentNegotiation) { json(LightFmJson) } } }
        val geocoder = remember { Geocoder(http) }
        DisposableEffect(Unit) { onDispose { http.close() } }

        val q = state.text.toString().trim()
        var results by remember { mutableStateOf<List<GeoChoice>>(emptyList()) }
        LaunchedEffect(q) {
            if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
            delay(300) // debounce keystrokes
            results = geocoder.search(q)
        }

        val keyboard = rememberEmbeddedKeyboard(state, singleLine = true) {
            results.firstOrNull()?.let { goBack(it.toQuery()) }
        }

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text("Set location"),
                )
                LightText(
                    text = q.ifEmpty { "City or ZIP" },
                    variant = LightTextVariant.Copy,
                    lighten = q.isEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.4f.gridUnitsAsDp()),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    for (r in results) {
                        LightText(
                            text = r.label,
                            variant = LightTextVariant.Copy,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { goBack(r.toQuery()) }
                                .padding(vertical = 0.5f.gridUnitsAsDp()),
                        )
                    }
                    if (q.length >= 2 && results.isEmpty()) {
                        LightText(
                            "No matches.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                        )
                    }
                }
                LightEmbeddedLp3Keyboard(viewModel = keyboard)
            }
        }
    }
}
