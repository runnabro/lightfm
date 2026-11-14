#!/usr/bin/env bash
# Copy the LightFM tool sources into a light-sdk clone's `tool/` module so it can be built.
# Usage: ./install-into-sdk.sh [path-to-light-sdk-clone]
# Default clone path: ./.context/light-sdk
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
sdk="${1:-$(cd "$here/.context/light-sdk" && pwd)}"
tool_source="$here/tool"
pkg="com/lightfm/radio"
source_pkg="$tool_source/src/main/kotlin/$pkg"
source_assets="$tool_source/src/main/assets"

if [[ ! -d "$sdk/tool" ]]; then
  echo "error: '$sdk' doesn't look like a light-sdk clone (no tool/ dir)." >&2
  echo "       pass the path: ./install-into-sdk.sh /path/to/light-sdk" >&2
  exit 1
fi

if [[ ! -d "$source_pkg" || ! -f "$tool_source/lighttool.toml" || ! -d "$source_assets" ]]; then
  echo "error: LightFM source files are missing; expected them under '$tool_source'." >&2
  exit 1
fi

echo "Installing LightFM → $sdk/tool"
echo "Only the com/lightfm/radio Kotlin package is replaced; other SDK sources and assets are preserved."
rm -rf "$sdk/tool/src/main/kotlin/$pkg"
mkdir -p "$sdk/tool/src/main/kotlin/$pkg"
cp "$source_pkg"/*.kt "$sdk/tool/src/main/kotlin/$pkg/"
cp "$tool_source/lighttool.toml" "$sdk/tool/lighttool.toml"
mkdir -p "$sdk/tool/src/main/assets"
cp -R "$source_assets/." "$sdk/tool/src/main/assets/"

# The stock SDK exposes no volume control to tools; add a `volume` property to LightAudioPlayer
# (idempotent) so LightFM can offer a volume slider + volume-button control.
python3 - "$sdk" <<'PY'
import sys, os
p = os.path.join(sys.argv[1], "sdk/client/src/main/kotlin/com/thelightphone/sdk/audio/LightAudioPlayer.kt")
if not os.path.exists(p):
    print("warn: LightAudioPlayer.kt not found; skipping volume patch"); raise SystemExit(0)
s = open(p).read()
if "LightFM-volume" in s:
    print("LightAudioPlayer volume patch already present")
else:
    # Mirror the SDK's own `speed` property: dispatch through `commands` so it queues until the player
    # connects and re-applies on reconnect — works for both Attached and Detached playback.
    anchor = "    /** Playback rate, clamped to a minimum positive rate. */"
    prop = ("    // LightFM-volume: expose playback volume (stock SDK has none), mirroring the SDK's `speed`.\n"
            "    var volume: Float = 1.0f\n"
            "        set(value) {\n"
            "            val v = value.coerceIn(0f, 1f)\n"
            "            commands.dispatch { it.volume = v }\n"
            "            field = v\n"
            "        }\n\n")
    if anchor in s:
        s = s.replace(anchor, prop + anchor, 1)
        open(p, "w").write(s)
        print("patched LightAudioPlayer for volume")
    else:
        print("warn: volume anchor not found; skipping volume patch")
PY

# The stock SDK's media3 setup omits the HLS module, so any .m3u8 stream (many radio streams are
# HLS) crashes ExoPlayer with ClassNotFoundException. Add the matching media3-exoplayer-hls
# dependency (idempotent, version pulled from the SDK's own catalog) so HLS streams play.
python3 - "$sdk" <<'PY'
import sys, os, re
sdk = sys.argv[1]
build = os.path.join(sdk, "tool/build.gradle.kts")
if not os.path.exists(build):
    print("warn: tool/build.gradle.kts not found; skipping HLS patch"); raise SystemExit(0)
s = open(build).read()
if "LightFM-hls" in s:
    print("HLS dependency patch already present"); raise SystemExit(0)
ver = "1.10.1"
try:
    m = re.search(r'(?m)^\s*media3\s*=\s*"([^"]+)"', open(os.path.join(sdk, "gradle/libs.versions.toml")).read())
    if m: ver = m.group(1)
except Exception:
    pass
anchor = 'implementation(project(":sdk:client"))'
s = s.replace(anchor, anchor + f'\n    implementation("androidx.media3:media3-exoplayer-hls:{ver}") // LightFM-hls', 1)
open(build, "w").write(s)
print(f"patched tool build for media3-exoplayer-hls:{ver}")
PY

# (Screen-off playback is now handled by the SDK's detached-audio capability — `capabilities =
# ["detached-audio"]` in lighttool.toml + a Detached player — so the old wake-mode patch is gone.)

echo "Done. Next:"
echo "  cd \"$sdk\""
echo "  # ensure local.properties has gpr.user / gpr.key (GitHub PAT with read:packages)"
echo "  ./gradlew :tool:installDebug"
