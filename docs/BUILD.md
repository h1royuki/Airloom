# Build, install and verify

## Requirements

- JDK 21 (the toolchain is pinned in the `core` module; target bytecode is Java 17).
- Android SDK Platform 35, Build Tools 35.0.0, Platform Tools.
- Internet access for the first dependency download.

Gradle 8.9 is pinned in the wrapper, and the distribution is verified against the official SHA-256. `scripts/build.sh` uses `JAVA_HOME` and `ANDROID_HOME`; it also finds an SDK/JDK in the project's git-ignored `tools/` directory and the standard SDK locations. On another machine it is enough to set your own paths:

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
./scripts/build.sh
```

Without arguments the script runs the `core` tests and builds the debug APK. Gradle tasks can be passed directly, for example `./scripts/build.sh :app:compileDebugKotlin`.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. The minimum version is Android 8.0 / API 26; the acceptance platform is Android 11 / API 30. `targetSdk 35` is compatible with API 30.

## Checks

```sh
./scripts/check.sh                          # JVM tests, lint, debug APK and test APKs
./scripts/check.sh --android emulator-5560  # the same plus instrumented tests on a dedicated test AVD
```

`check.sh` runs Gradle with a single worker and `android.experimental.lint.useK2Uast=false`: parallel lint/UAST crashes on AGP 8.7.3. With `--android` it installs the debug and test APKs on the given emulator, runs both instrumentation suites through `am instrument` and saves the reports to `artifacts/check/`. Do not run two test suites on one device at the same time.

A single class or test, once the APKs are installed:

```sh
adb -s emulator-5560 shell am instrument -w -e class tv.airloom.app.NavigationRailUiTest \
  tv.airloom.app.test/androidx.test.runner.AndroidJUnitRunner
```

The slow synthetic benchmark is not part of the regular run. Its class is `tv.airloom.data.CataloguePerformanceTest`: 2,000 channels, 500,000 programmes, a bounded sample of the original OFFSET search and a full pass of the current search. It uses its own temporary database and never contacts the provider.

## Signed APK

```sh
./scripts/sign-release.sh
```

Output: `artifacts/airloom-release.apk`. On first use the script creates a permanent personal key and password in `~/.local/share/airloom/signing`, outside the repository and with restricted permissions. Another external directory can be set with `AIRLOOM_SIGNING_DIR`.

Back up the **whole** signing directory: without the original key, later APKs cannot be installed as an update that keeps the data. Never publish the key or the password. Bump `versionCode` in the app configuration for every update. Debug and release use different keys, so one cannot be installed over the other; install release builds for regular use.

To try a debug build on a personal TV and still be able to update to release later, sign it with the same permanent key first:

```sh
./scripts/sign-apk.sh app/build/outputs/apk/debug/app-debug.apk artifacts/airloom-debug-signed.apk
```

Install that APK on the TV instead of the stock debug APK. `sign-apk.sh` aligns the APK and verifies the signature; the build keeps its debug capabilities.

## Installing over ADB

```sh
adb connect TV_IP:5555
adb devices -l
adb -s TV_IP:5555 shell getprop ro.build.version.sdk
adb -s TV_IP:5555 install -r artifacts/airloom-release.apk
adb -s TV_IP:5555 shell am start -n tv.airloom.app/.MainActivity
```

Use the exact serial from `adb devices`, especially when several devices are connected. Root, a TV reboot and changes to system apps are not needed.

If an already running ADB server reports `No route to host` although the TV's address and port are reachable, start a separate server without stopping existing connections: `adb -P 5038 connect TV_IP:5555`. Then add `-P 5038` to every command for that TV.

Driving an emulator or TV from the shell: `adb -s SERIAL shell input keyevent KEYCODE_DPAD_CENTER`, and likewise `KEYCODE_DPAD_UP`, `KEYCODE_DPAD_DOWN`, `KEYCODE_DPAD_LEFT`, `KEYCODE_DPAD_RIGHT`, `KEYCODE_BACK`.

## TV emulator

```sh
./scripts/tv-emulator.sh
```

The script uses its own AVD, `Airloom_TV_API31`, in `~/.cache/airloom-tools/avd` on port 5558 (`emulator-5558` in ADB). If the system image is missing, it prints the install command. AVDs of other projects are not touched. `AIRLOOM_AVD_HOME` and `AIRLOOM_EMULATOR_PORT` select another AVD directory and port — that is how the separate test AVD on `emulator-5560` is started:

```sh
AIRLOOM_AVD_HOME="$HOME/.cache/airloom-tools/test-avd" AIRLOOM_EMULATOR_PORT=5560 \
  ./scripts/tv-emulator.sh -no-window -no-audio -no-boot-anim
```

Keep the two apart: `emulator-5558` is for manual testing and is updated without wiping data, `emulator-5560` is for automated tests.

On Apple Silicon a compatible ARM64 TV image exists from API 31 on. The official API 30 TV image is x86, so the API 31 emulator is used for TV navigation and Android 11 is verified on a physical TV.

## Acceptance on a TV

The emulator confirms neither hardware codec support nor the behaviour of a particular TV. Before a release, check on an Android 11 TV:

- A clean install, entering the key and domain, a wrong key, a missing EPG, cancelling and re-running the wizard.
- Remote-only control, focus restoration, large text and long titles.
- No video stream request before an explicit start; moving between the preview and full screen.
- Live playback, an available archive entry and returning to live.
- Favourites, ordering, hiding, source refresh and data surviving an APK update.
- Wi-Fi coming back, an hour of continuous viewing and UI responsiveness while the full EPG is importing.

Never put private links into commands, reports or test fixtures. Provider data is entered in the app itself.
