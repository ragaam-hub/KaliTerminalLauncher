# Kali Terminal Launcher

A lightweight Android home-screen launcher with a Kali-inspired black/green terminal interface.

## Features
- Can be selected as the Android default Home app.
- Lists launchable apps installed on the device.
- Search and launch apps by tapping their tiles.
- Local UI commands: `help`, `apps`, `date`, `clear`, `about`, and `open <app name>`.

## Build
**Tanpa Android Studio (build online):** upload folder ini ke repo GitHub, lalu buka tab *Actions* > *Build APK* > Run. Unduh `app-debug.apk` dari Artifacts dan install di HP.

**Dengan Android Studio:**
1. Open this folder in Android Studio (Hedgehog or newer recommended).
2. Allow Gradle to sync. Use JDK 17.
3. Connect an Android phone with USB debugging enabled, or use an emulator.
4. Select Run, or use Build > Build APK(s).
5. On the phone, press Home and choose **Kali Terminal Launcher** as the default Home app.

The terminal is a themed command interface, not a real Linux/Kali shell. It does not execute arbitrary system commands, access private data, or perform security testing.
