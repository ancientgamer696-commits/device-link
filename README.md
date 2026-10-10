# DeviceLink 📱⇄🖥 — control your Android from a browser tab

A **zero-infrastructure LAN remote** for your own Android phone. One APK on the
phone exposes your screen, camera and full touch input to a webpage — no root,
no ADB, no accounts, nothing leaves your Wi-Fi.

Built as a prototype for Redmi Note 10 Lite / Note 9 Pro (any Android 8+ works).

## What it does

| Feature | How |
|---|---|
| Live screen (≈8 fps MJPEG-over-WebSocket) | `MediaProjection` → `ImageReader` → scaled JPEG, streamed on `:8765` |
| Full touch injection — taps, drags, swipes, scroll | Accessibility `dispatchGesture` (stroke synthesized on pointer-up) |
| Keyboard text → phone clipboard+paste | Accessibility node paste into focused field |
| Camera live feed (back cam, 640×480) | `Camera2` + `ImageReader` JPEG mode toggle |
| Back / Home / Recents / Shade buttons | `performGlobalAction` |
| Brightness, rotation lock, screen wake | `Settings.System` (needs “modify settings”) + WakeLock |
| App launcher grid, launch any app | `queryIntentActivities` |
| Screenshot to browser (download) *and* to phone gallery | current frame → `MediaStore` |
| Battery %, model, resolution, FPS, ping | `meta`/`pong` JSON messages |
| Viewer webpage served by the phone itself | tiny HTTP server on `:8766` — open on any laptop/phone browser |

## Quick start (2 minutes)

1. **Download `app-debug.apk`** from the [latest release](../../releases/latest) → install
   (MIUI: allow *Install unknown apps* for your browser/File manager).
2. Open **DeviceLink**. On MIUI do this first, in order:
   - *Battery saver → No restrictions* + press button **3** in the app (ignore battery optimizations)
   - *Autostart* → enable DeviceLink (else MIUI kills the service when the screen sleeps)
3. Tap **Start screen streaming** → confirm *Start now* on the system dialog.
4. Tap **Enable Input (Accessibility)** → find *DeviceLink Input* → ON → allow.
5. On your laptop/second phone (same Wi-Fi) open the URL the app shows:
   `http://<phone-ip>:8766` — or use the [GitHub Pages launcher](https://ancientgamer696-commits.github.io/device-link/).

That's it. The webpage is the remote: screen on top, tap/drag on it to control,
buttons below for keys/camera/apps/brightness/text, ⬇ Screenshot saves the frame
in your browser **and** on the phone (`Pictures/DeviceLink`).

## Architecture

```
┌───────────── phone (no root) ─────────────┐        ┌────── browser (no install) ──────┐
│ MainActivity   permission + consent UX    │        │  viewer.html                       │
│ StreamService  MediaProjection → JPEG ────┼── :8765 ws ──► canvas + touch → JSON msgs  │
│                Camera2 → JPEG             │        │  keys / apps / camera / brightness │
│                Accessibility gesture ◄────┼── :8766 http ──► serves the viewer itself    │
│ InputService   dispatchGesture/paste      │        └────────────────────────────────────┘
└───────────────────────────────────────────┘         (optional) Cloudflare relay worker
```

* **Repo `worker/`** — a Cloudflare Durable-Object relay (`tunnel.js`) that lets you reach the phone
  from outside the LAN with a pairing code. Optional; deploy with `wrangler`.
* **`site/index.html`** — GitHub-Pages launcher page (enter phone IP → opens the viewer).
* **`android/app/src/main/assets/viewer.html`** — the whole remote UI, single file, also served by the phone.

## Message protocol (ws `:8765`)

Browser → phone (JSON text):
`{t:"down"|"move"|"up", x:0..1, y:0..1}` · `{t:"key", v:"back|home|recents|notif|power"}` ·
`{t:"text", v:"…"}` · `{t:"cam", on:bool}` · `{t:"bri", v:10..255}` · `{t:"rot", v:0|1}` ·
`{t:"wake"}` · `{t:"open", v:"https://…"}` · `{t:"apps"}` · `{t:"launch", pkg:"…"}` · `{t:"shot"}` · `{t:"ping", id}`

Phone → browser: binary frames = **JPEG** (screen or camera) · `{t:"meta", model,w,h,mode,battery}` ·
`{t:"appList", a:[{n,p}]}` · `{t:"pong", id}`

## Limitations (prototype, honestly)

* ~8 fps / 46-quality JPEG — fine for control & demos, not for video/game streaming. Web-codec (H.264) capture is the upgrade path.
* Touch is *synthesized gestures*, so absolute low-latency pointer mirroring isn't possible; drags replay on pointer-up (≤1 s).
* Android 13+ blocks `WAKE_LOCK` screen-on from background in some OEMs; use *pin screen* in quick settings.
* `queryIntentActivities` is filtered on Android 11+ unless **QUERY_ALL_PACKAGES** is granted (this debug build declares it; Play would not allow it — fine for sideload).
* Security: **LAN-only, no auth**. Anyone on your Wi-Fi can view/control while the service runs. Stop the notification to kill it. Do not port-forward. The optional relay adds pairing codes but still ships without end-to-end encryption.
* Not for Play Store; personal/demo use only, on devices you own.

## Build

Push to `main` → GitHub Actions builds the debug APK (artifact). Push a tag `vX.Y.Z` → it also opens a GitHub Release with `app-debug.apk`.
Local build if you have Android Studio: open `android/` and run `./gradlew assembleDebug`.

## Deviations from “full android control”

Root/ADB-only things (shell commands, `input tap` at kernel latency, installing apps silently, reading logcat) are **not** possible for a normal sideloaded app — that's an Android platform boundary, not a code gap. Everything reachable without root via Accessibility + MediaProjection + Camera2 is wired in.
