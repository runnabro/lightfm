# LightFM — light-tool

An internet-radio player for the Light Phone III, styled like a retro car FM radio.
The LP3 has no FM tuner chip, so "FM" here is the interaction metaphor: a horizontal
tuner dial you turn with the hardware dimmer wheel (or swipe), local stations seeded
from your location, and six memory presets like a car radio's 1–6 buttons.

A tool can't be built standalone — the SDK's Gradle plugin generates the manifest and
enforces its API allowlist. Sources live here and are copied into a clone of
[`lightphone/light-sdk`](https://github.com/lightphone/light-sdk)'s `tool/` module to build
(same pattern as L-Message).

## Layout

- `spike/src/main/kotlin/com/lightfm/radio/` — the Kotlin/Compose tool (one screen).
- `spike/lighttool.toml` — the tool manifest (`INTERNET` permission + the `detached-audio` capability).
- `install-into-sdk.sh` — copies the sources + toml into an SDK clone's `:tool` module.

## Build & run

Prereqs (one-time):

- A clone of `lightphone/light-sdk` (default expected at `../.context/light-sdk`, or pass a path).
- JDK 17 + Android SDK (platform 36). The SDK's `./gradlew` wrapper handles Gradle 9.
- A GitHub Packages PAT with `read:packages` in the SDK clone's `local.properties`:
  ```
  gpr.user=<github-username>
  gpr.key=ghp_<token>
  ```
  (the SDK pulls `light-keyboard` from GitHub Packages.)

The SDK's `:tool` module already applies the serialization + KSP plugins and depends on
`:sdk:client`, so Ktor / kotlinx-serialization / DataStore / Media3 all come transitively —
this install adds no Gradle config.

> **SDK patches (two, all idempotent, applied by `install-into-sdk.sh`):**
> 1. **Volume** — the stock SDK exposes no volume control, so it adds a `volume` property to
>    `sdk/client/.../audio/LightAudioPlayer.kt` (marker `// LightFM-volume`), mirroring the SDK's own `speed`
>    property (`commands.dispatch { it.volume = v }`) so it queues until the player connects and works in
>    detached mode. Without it the volume slider + volume buttons wouldn't compile.
> 2. **HLS** — the SDK's media3 setup omits the HLS module, so any `.m3u8` stream (many radio streams,
>    incl. ~170 of the bundled ones) crashes ExoPlayer with `ClassNotFoundException`. It adds
>    `androidx.media3:media3-exoplayer-hls` (matching the SDK's media3 version) to `tool/build.gradle.kts`
>    (marker `// LightFM-hls`). The app also guards at runtime, treating DASH/SmoothStreaming/`.pls`/`.m3u`
>    URLs as "no stream" (static) rather than crashing.
>
> Screen-off playback is no longer a patch — it's the SDK's supported **`detached-audio`** capability
> (`capabilities = ["detached-audio"]` in lighttool.toml + a `LightAudioPlayback.Detached` player), which
> runs a foreground `MediaSessionService`. The old `WAKE_LOCK`/wake-mode hack was removed when we bumped the
> SDK to the version that added detached audio.
>
> Re-running the installer is safe (all patches no-op if already present).

Emulator (default; `lighttool.toml` ships with `serverPackage = "com.thelightphone.sdk.emulator"`):

```sh
./install-into-sdk.sh ~/path/to/light-sdk
cd ~/path/to/light-sdk && ./gradlew :tool:installDebug
# open "LightFM" in the LightOS emulator (Settings → Allowed Tools → "Built with SDK")
adb logcat | grep -iE "LightFM|ExoPlayer|Media"
```

Real Light Phone III — set `serverPackage = "com.lightos"` in `spike/lighttool.toml` first, then:

```sh
adb devices        # confirm the LP3 is attached
./install-into-sdk.sh ~/path/to/light-sdk
cd ~/path/to/light-sdk && ./gradlew :tool:installDebug
```

## Controls

- **Dial = the real FM band (88.1–107.9).** Every licensed station in range sits at its **true
  frequency** (from the bundled FCC table) — e.g. 94.9 = Wild 94.9, 106.1 = KMEL, 88.5 = KQED. Big
  commercial + public/college stations play (● LIVE); a station with no known stream shows its call
  sign and plays **static** ("no stream"); empty frequencies are static too. Stations we can't stream
  are **dimmed** on the dial so the ones that actually play stand out at a glance. Turn the dimmer wheel
  (317/318) or swipe to browse; tapping a channel (or pressing the wheel, 319) plays it. It starts
  **stopped** and stopping fully ends the stream (no data use when you're not listening). The
  **header** (centered, L-Message two-line style) shows the tuned **frequency** big on top and
  **station name · city** below — both flipping live as you scroll. Tap it to change location.
- **Play / stop:** a permanent **bottom bar** shows the current status on the left and a play/stop
  button on the right (also the wheel press, 319) that starts or fully stops the stream — so it isn't
  streaming when you're not listening.
- **Volume:** press the hardware volume buttons; a volume HUD **slides up from the bottom** (a slider)
  and **fades after ~2s**. Level is persisted, and the slider position is mapped through a **dB taper**
  so each step is an even, native-feeling loudness change (ExoPlayer's raw gain is linear). This is a
  per-app gain layered under the system volume — it can't exceed it, and a tool can't reach the system
  volume (sandbox), so at the top it shows a hint: *"Max app volume, open system volume to get louder."*
  (Needs the SDK volume patch — see below.)
- **⋯ (top-right) → options half-sheet:** *Snap to stations* (Light UI toggle, persisted) — a **fling/
  swipe** settles on the nearest station that actually streams, skipping call-sign-only/static channels;
  the **wheel** always steps channel-by-channel (through the empties) so you can glide to any frequency; *Wheel
  tunes the dial* (persisted, on by default) — turn it off and the dimmer wheel's turns fall back to
  LightOS (screen brightness) instead of tuning (the wheel *press* still plays/stops); plus *Set
  location* (shows your current city).
- **Tap the title (location)** → embedded-keyboard search with **city or US ZIP autocomplete**;
  pick a result to retune that market.
- **Presets (horizontally-scrolling row of filled tiles)** — L-Message-avatar-style tiles with a subtle
  dark-gray fill, landscape, ~3.5 show at once (the 4th is cropped as the scroll affordance); the playing
  one brightens to white. Tap a saved preset to recall + play (jumps the dial to its frequency);
  **hold** any slot to save the tuned station into it; **keep holding** (a *"Keep holding to remove."*
  hint appears) to clear it. Starts with 6 blank slots; once all are filled a plain **"+"** (no
  background) appends the tuned station as a new slot (grows past 6). Persisted (DataStore).

## Data sources (three layers, joined by call sign)

1. **Dial — FCC** FM facility database (public domain). Bundled `spike/src/main/assets/fcc_fm.csv`
   (~14.4k licensed US FM + LPFM: `call,freq,lat,lon`). Runtime keeps stations within ~90 km, one per
   channel (nearest wins) → the real frequencies + call signs.
2. **Commercial streams — `iheart_streams.tsv`** (`CALL\turl\tname`, ~3k entries). Resolved offline
   from broadcaster catalogs; covers the big brands (Wild 94.9, KMEL, KOIT, Live 105, KBLX, 99.7 NOW,
   KFOX…) as their own public HTTPS simulcast URLs (revma/StreamTheWorld/Amperwave). Preferred stream
   when present; shows the brand name.
2b. **Public radio — `npr_streams.tsv`** (~994 `CALL\turl\tname`), resolved offline from NPR's Station
   Finder API (`station.api.npr.org/v3`, enumerate orgIds). All HTTPS, exact call-sign+frequency match,
   **low ToS** (public radio is freely streamable).
2c. **Audacy — `audacy_streams.tsv`** (~1.17k), one call to `api.audacy.com/experience/v1/stations`,
   kept `bband == "FM"` and filtered to call signs present in the FCC table. Authoritative superset of the
   Audacy mounts we'd otherwise get piecemeal via iHeart.
2d. **StreamTheWorld/Triton probe — `triton_streams.tsv`** (~318 net-new), a build-time probe of every
   FCC call sign not already covered: `GET playerservices.streamtheworld.com/api/livestream-redirect/
   <CALL>FMAAC.aac` (206 = exists). Catches local/independent commercial FMs in no catalog. Same ToS
   class as iHeart/Audacy.
2e. **radio.garden — `radiogarden_streams.tsv`** (~1.2k net-new), the **local/community long tail**
   (LPFM, college, ethnic, small-market) our commercial + public-flagship sources miss — e.g. KPOO,
   KALX, KPFA, KDFC. Built offline from radio.garden's place/channel API (`radio.garden/api/ara/…`),
   keeping titles with an FM freq + FCC call sign and resolving each `listen/{id}` 302 to the station's
   **own** direct HTTPS stream (streamguys/berkeley.edu/…). Lowest precedence.
   The five stream layers are merged into one call-sign→stream map (precedence radio.garden < Triton <
   NPR < Audacy < iHeart), checked before Radio Browser.
2f. **Manual pins — `manual_streams.tsv`** (`CALL\turl\tname`), hand-verified stream URLs merged at the
   **top** precedence — always win. For the long-tail LPFMs in no directory: find the station's own
   HTTPS stream (its site's player / a directory) and add a row. E.g. `KEXC` (SF 92.7 = a KEXP Bay Area
   relay) → `https://kexp-mp3-128.streamguys1.com/kexp128.mp3`.
3. **Everything else — Radio Browser** (public domain), matched to FCC first by **call sign**, then by
   **exact frequency** (nearest RB station on that channel — catches stations RB lists under a brand
   name like "Wild 94.9" rather than a call sign). Still-unmatched channels show the call sign and play
   **static**.

### Coverage / honesty caveat

With the commercial layers, most of a metro's dial now plays (the SF majors all stream). The tradeoff:
the **commercial** layers (iHeart / Audacy / Triton) point at broadcasters' own public streams that their
apps use — **their ToS restrict use to their own apps**, so this is a gray area. They're resolved *offline*
(no runtime calls to those services; the app just hotlinks the public stream), which is the low-risk end for
a personal device, but it isn't "blessed" and mount URLs can change over time (regenerate the TSVs to
refresh). **NPR** (public radio) is the clean, freely-streamable layer, and **radio.garden** is mostly
community/college/LPFM (generally freely streamable, lower risk — though discovered via radio.garden's
undocumented API at build time; the bundled URLs are the stations' *own* streams). If you'd rather stay
strictly clean, drop `iheart_streams.tsv` / `audacy_streams.tsv` / `triton_streams.tsv` and it falls back to
FCC + NPR + radio.garden + Radio Browser (commercial brands then show as static). Radioplayer's API (which
joins frequency+stream officially) is Europe/Canada-only and partner-gated — not usable for a US tool.

### Regenerating the bundled data

- `fcc_fm.csv`: FCC FM Query text export (`https://transition.fcc.gov/fcc-bin/fmq?...&list=4`), keep
  `service ∈ {FM,FL}` + `status == LIC`, DMS→decimal, dedupe by facility id, strip `-FM/-LP` suffix.
- `iheart_streams.tsv`: page `https://us.api.iheart.com/api/v2/content/liveStations?limit=20000`, take
  `callLetters` (strip suffix) + `secure_shoutcast_stream`/`secure_hls_stream` + `name`.
- `npr_streams.tsv`: enumerate `https://station.api.npr.org/v3/stations/{orgId}` (orgId ~1..2300, no
  auth), keep `brand.band == "FM"`, take `brand.call` + `brand.name` + a `streamsV2[].urls[].href`
  (prefer a direct mp3/aac over a `.pls`, resolving the playlist to its inner URL), dedupe by call sign.
- `audacy_streams.tsv`: one call `https://api.audacy.com/experience/v1/stations?page[size]=3000`, keep
  `attributes.bband == "FM"`, strip the FM/AM/HD suffix off `attributes.callsign`, keep only call signs
  present in `fcc_fm.csv`, take the best `attributes.station_stream[].url` (direct mp3 > aac > hls).
- `triton_streams.tsv`: for every FCC call sign not already in the iHeart/NPR/Audacy sets, probe
  `https://playerservices.streamtheworld.com/api/livestream-redirect/<CALL>FMAAC.aac` (HTTP 206 = the
  mount exists); the probed URL is the bundled stream. ~10k probes, low hit rate (~300).
- `radiogarden_streams.tsv`: fetch `radio.garden/api/ara/content/places` (filter country=US, ~2.1k
  places), then `…/page/{id}/channels` for each; keep `page.title`s with an FM freq + an FCC call sign
  not already covered; resolve each `…/listen/{cid}/channel.mp3` (follow the 302) to the direct HTTPS
  stream. Needs a browser `User-Agent` + `Referer: https://radio.garden/`. ~2.1k + ~1.5k requests → ~1.2k.

## Notes / gotchas

- Location: the LP3 sandbox blocks device GPS, so stations are seeded via IP-geolocation over
  HTTPS (`ipwho.is`, then `ipapi.co`), mapped to Radio Browser's `state` filter. Manual entry
  is the fallback. Once you set a location by hand it's **remembered** (persisted to DataStore) and
  preferred over IP-geo on every relaunch — so a VPN or travel won't reset your market.
- **Offline:** the whole **dial builds offline** — the FCC table + all the stream URLs are bundled, so with
  a remembered/cached location the app shows the full local band (names, frequencies, which stations
  stream) with **no network** (Radio Browser + IP-geo are online-only bonuses). Empty channels even play
  their **static** loop offline (bundled `static.wav`). Only the live streams themselves need internet — a
  streamable station tapped offline just reports "Station unavailable." First-ever launch with no network
  *and* no prior location can't seed the dial (needs one online session or a hand-set location).
- **Screen-off / background audio: works, via the SDK's `detached-audio` capability.** `lighttool.toml`
  declares `capabilities = ["detached-audio"]`, and the player is created with `LightAudioPlayback.Detached`
  (`HomeViewModel`). The SDK then injects a foreground `MediaSessionService` and grants the foreground-service
  permissions, so the stream keeps playing with the screen off / the tool backgrounded. Verified on the LP3:
  the media session stays `PLAYING` through screen-off (it died in seconds before). Two caveats: the tool
  loses focus on screen-off (LightOS shows its launcher) so audio continues from the detached service, not the
  screen; and under prolonged *deep* Doze (~30 min stationary) even the foreground service's network gets cut,
  so the stream would stall then. This landed in the official SDK as `feat: detached audio playback` — earlier
  we'd (correctly, at the time) documented it as impossible in the stock sandbox; the SDK bump changed that.
- Live streams report `durationMs == 0`, so there's no seek bar — status shows `● LIVE`.
  The player exposes no error/buffering flow, so a ~7s watchdog treats "still not playing"
  as a dead stream and auto-skips.
- The location editor uses a local `MutableStateFlow(defaultKeyboardOptions())`, NOT
  `rememberKeyboardOptions()` — the latter calls the LP3 keyboard service whose response predates
  the SDK (missing `swipeEnabled`) and hard-crashes on deserialize at startup. Confirmed on a real
  LP3 (TLP301).
