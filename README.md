# Code Agent (Android)

Personal Android app that runs a **Debian Linux environment via proot** and drives
code-agent CLIs (Claude Code, Codex, Cursor Agent, GitHub Copilot) from a Compose UI.

Sideload only — not for Play Store.

## Hard constraint: `targetSdk = 28`

Android 10+ SELinux blocks `exec()` of files in the app data directory for apps with
`targetSdkVersion >= 29`. Debian binaries live under `filesDir/debian`, so this app
must target API 28 (same reason Termux stays on 28). Fine for sideloading on Android 14+.

## Architecture

1. **Native layer**: Termux `proot` + `libtalloc` + `libandroid-shmem` (from Termux apt)
   plus a PTY JNI (`libtermux.so`, Apache-2.0 terminal emulator).
2. **Debian rootfs**: downloaded on first launch from proot-distro releases, extracted
   into `filesDir/debian`.
3. **Agent CLIs**: installed inside Debian via Setup screen (`npm` / install scripts).
4. **UI**: Compose chat (NDJSON stream parsers) + Termux-style shell screen.

Kotlin never implements agent logic — it only builds argv, parses NDJSON, and renders.

## Build

Requirements (already vendored under the project if you followed the setup once):

- JDK 17 (`.jdk-17/` or set `org.gradle.java.home` in `gradle.properties`)
- Android SDK (`local.properties` → `sdk.dir`)
- Optional for rebuilding JNI: NDK r27c (`.android-ndk/`) + CMake (`.android-cmake/`)

```bash
# Refresh proot / shmem / talloc into jniLibs
python3 tools/fetch_native.py

# Rebuild libtermux.so (needs NDK + cmake present)
bash tools/build_jni.sh

# Debug APK
./gradlew :app:assembleDebug

# Signed release APK (uses release.keystore + keystore.properties)
./gradlew :app:assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/app-code-agent-debug.apk`
- `app/build/outputs/apk/release/app-code-agent-release.apk`

Install:

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-code-agent-debug.apk
```

## First run on phone

1. Open the app → **Setup**: download Debian rootfs, pick agents to install.
2. Open **Shell** and log in to each CLI (`claude`, `codex`, `cursor-agent`, `copilot`)
   using their device-code / browser flow. Credentials stay in the rootfs under `/root`.
3. **New session** → choose/create `/root/workspace/<name>`.
4. Chat: switch agent in the top bar, `@file` to mention workspace files, `+` to import
   external files. Right drawer = session history.

## Phantom process killer (Android 12+)

Long agent runs may be killed by the phantom-process limit. The app starts a foreground
service + wake lock. If shells still die, from a PC:

```bash
adb shell device_config put activity_manager max_phantom_processes 2147483647
```

## Disk

Reserve **≥ 1.5 GB** free (rootfs ~35 MB + apt + Node + CLIs).

## License notes

- App code: personal / as-is.
- `terminal-emulator` / `terminal-view`: Apache-2.0 (from Termux; see `third_party/`).
- `proot` binaries: GPL-2.0 (Termux package); redistributed as runtime payload.
