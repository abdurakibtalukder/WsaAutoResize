# WSA AutoResize

A lightweight Xposed / Vector module designed for **Windows Subsystem for Android (WSA)** to make Android apps behave cleanly like native desktop windows.

## What It Fixes
* **Removes Size Compat Mode (`↻` button & grey bars):** Hooks Android's `ActivityInfo` and `ActivityRecord` so non-resizable apps stop locking to fixed phone dimensions.
* **Unlocks Portrait-Only Apps:** Overrides `setRequestedOrientation` so apps expand freely when you widen or maximize the Windows window.
* **Auto-Scales UI DPI to Window Width:** Dynamically adjusts `densityDpi` based on window size (`180 DPI` for narrow windows, `150 DPI` for wide/maximized PC windows `>= 1000px`).

## Requirements
* **Windows Subsystem for Android (WSA)** (Android 12/13)
* **Magisk** with **Zygisk** enabled
* **[Vector](https://github.com/JingMatrix/Vector)** (or LSPosed) Xposed framework

## Installation
1. Download **`WsaAutoResize.apk`** from the **[Releases](../../releases)** page.
2. Install the APK inside WSA.
3. Open **Vector / LSPosed Manager** > **Modules** > enable **WSA AutoResize**.
4. In the module's **Scope**, check:
   * **System Framework** (`android`)
   * Any apps you want auto-resized and auto-scaled
5. **Important:** If you use Magisk DenyList / Zygisk Assistant / Shamiko, make sure your target apps are **not** inside Magisk's DenyList (otherwise hooks are blocked inside those apps). Also disable conflicting DPI/orientation modules (such as *App Settings Reborn*) for those apps.
6. **Turn off WSA** in *Windows Subsystem for Android Settings* and reopen your app.

## Building from Source
This repository builds automatically via **GitHub Actions** on every push to `main` and publishes release APKs automatically when pushing a `v
