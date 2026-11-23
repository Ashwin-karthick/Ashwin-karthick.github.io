# Hermes for Android: phase 1 test build

An Android app that runs [Hermes Agent](https://github.com/NousResearch/hermes-agent) on the phone
itself. It contains its own terminal and its own Linux (Ubuntu 24.04), so no Termux is needed.

This first build proves the hard part works. A setup screen runs five steps, and each one types its
commands into the built-in terminal so you can watch:

1. **Check this phone.** Confirms a 64-bit ARM phone with enough free space, and that the Linux runner (proot) starts.
2. **Download Ubuntu.** About 30 MB, checked against a fixed SHA-256, then unpacked inside the app.
3. **Prepare Ubuntu.** `apt-get` installs curl, git and certificates.
4. **Install Hermes.** Runs the official installer (`install.sh --skip-setup`).
5. **Start Hermes and test the connection.** Starts `hermes dashboard` on `127.0.0.1:9119` with a
   token the app generates, then checks the app can call its API.

The Terminal tab shows every step's output. **+ Shell** opens a normal Ubuntu shell where you can type
anything, including `hermes`.

The friendly setup wizard (connect AI, web tools and so on) and the Astryx dashboard come in later
phases, once this build has shown it works on your phone.

## Install on your phone

1. Download the APK (`hermes-mobile-*.apk`).
2. Open it. Android asks you to allow installs from that app (your browser or Files). Allow it.
3. Play Protect may warn that the app was "built for an older version of Android". That's expected
   (see "Why target SDK 28" below). Choose **Install anyway**.
4. Open **Hermes** and tap **Run all steps**. Keep the app on screen during the first run if you can.
5. If anything fails, tap **Share log** and send the text back.

Allow notifications if asked: the app shows one while it is working so Android doesn't stop it.
**Battery settings** opens the screen where you can set Hermes to "Unrestricted", which also helps.

## What to expect

| | Measured in testing* |
|---|---|
| Ubuntu download | 30 MB |
| Ubuntu after step 3 | about 540 MB |
| After installing Hermes | see the test notes in the pull request or commit |

\*Measured on a desktop running the same ARM64 Ubuntu under emulation, not on a phone. Times on a phone will differ.

## If a step says "Android stopped this step (signal 9)"

Android 12 and later stop background child processes when there are too many, or when they use a
lot of CPU. A long install can trigger this.

- **Android 14 and later:** turn on Developer options, enable **Disable child process restrictions**, and restart the phone.
- **Android 12 and 13:** this needs a computer with `adb`:
  `adb shell settings put global settings_enable_monitor_phantom_procs false`

Then tap **Try again** on the failed step. Steps are safe to rerun.

## Why target SDK 28

Android blocks apps that target SDK 29 or later from running programs stored in their own data
folder. The built-in Linux needs exactly that, so the app targets SDK 28, the same as Termux. This
means it can't be published on Google Play, and a future Android version could stop allowing it.

## Building

```bash
./tools/fetch-proot.sh            # Termux's proot + libraries for arm64, checked by SHA-256
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest -Drootfs.tarball=/path/to/ubuntu-base-24.04.3-base-arm64.tar.gz
```

GitHub Actions (`.github/workflows/hermes-android.yml`) builds the APK on every push that changes
this folder and attaches it to the `hermes-android-latest` pre-release.

Builds are signed with a **public, test-only key** (`signing/`) so each new APK installs over the
last one without wiping Linux and Hermes. Never treat that signature as proof of anything.

## How it fits together

```
Android app (Kotlin)
 ├─ Setup screen ── runs one step at a time, judges success by exit code
 ├─ Terminal tab ── Termux's terminal-view library
 └─ SetupService ── foreground service: owns the terminal sessions, holds a wake lock
        │
        ▼  /system/bin/sh assets/hm-linux.sh "<command>"
     proot (Termux build, shipped as libproot.so)
        │
        ▼
     Ubuntu 24.04 arm64 in files/linux/rootfs ── Hermes installed by its own installer
        │
        ▼
     hermes dashboard on 127.0.0.1:9119 ◄── app calls it with X-Hermes-Session-Token
```

Third-party components and their licences are listed in [NOTICE.md](NOTICE.md).
