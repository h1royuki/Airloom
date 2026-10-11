# Android TV UI tests

Run on an unlocked Android TV device/emulator with D-pad support (API 30 is the acceptance target):

```sh
./scripts/build.sh :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -e class tv.airloom.app.MainActivityLaunchTest,tv.airloom.app.OnboardingUiTest tv.airloom.app.test/androidx.test.runner.AndroidJUnitRunner
```

Alternatively, run `./scripts/build.sh :app:connectedDebugAndroidTest` when only the intended test device is connected. Do not clear app data or uninstall the application to prepare these tests.

- `MainActivityLaunchTest` uses the real MainActivity, Hilt, Room and DataStore. It reads the existing wizard state and checks idle Media3 state before/after Activity recreation. Welcome assertions are skipped when the wizard has already advanced. Idle-launch checks apply to every startup destination, including a saved fullscreen preference. Assertions never print stored URLs or playback request objects.
- `OnboardingUiTest` uses the real AirloomViewModel and AirloomApp with a fresh in-memory repository for each test. It exercises D-pad OK, movement from the name field to the URL with D-pad Down without opening the IME, explicit OK to edit, Done/Back to leave editing, disabled empty import, error/correction/retry, Back navigation, restoring a persisted draft into a new ViewModel, and local M3U import through completion. EPG may be skipped and the resulting guide must have zero prepared media items. A successful local XMLTV import must preserve the playlist summary (channels, categories and archive channels), including after creating a fresh ViewModel at the EPG step and returning to the playlist step. A saved fullscreen destination also remains idle until explicit Play. A deferred cancelled-import cleanup verifies that stale progress/finally callbacks cannot overwrite a newer import.
- Only a uniquely named synthetic M3U file is written to the application's import directory. The test deletes that file in cleanup; it never modifies user playlists or preferences. Error UI is exercised with a deterministic injected repository failure, not a live network dependency. Source validation and Room imports are covered by data-module tests.

- `NavigationRailUiTest` checks a completely hidden rail (zero width), full-height expansion from categories/settings/search with D-pad Left, exact content focus restoration, reopening, and text-editing arrows. Sources are synthetic.
- `PlayerMotionUiTest` checks archive clock time, timeline edges and large text, no-EPG fallback, progress reset on channel change, automatic hiding, and immediate focus/interaction removal during both overlay exit animations. It saves synthetic player/navigation preview images only in the emulator application's cache; it does not capture real provider screens.

- `ProgrammeBrowserUiTest` uses a synthetic in-memory catalogue, injectable clock and a recording player. It covers date/month selection with D-pad, out-of-range dates, light theme and large text, cancellation and retry, stale-request cancellation, EPG replacement, midnight-spanning programmes, current/archive/future actions, channel-change reset, player return, and Compose saved-state restoration. Calendar/history captures contain only fixtures and are written to the app cache. When the emulator app uses the release signing key, sign both debug and androidTest APKs with the existing external key before updating; do not uninstall or clear data to bypass a signature mismatch.

- `SettingsManagerUiTest` exercises the settings/list manager through the real ViewModel and isolated synthetic repository: hidden category/channel access without playback, visibility inheritance, focus after restoration, bulk failure/retry, scoped ordering/cancellation, catalogue invalidation, provider draft/discard/error/retry, large light-theme layout, long D-pad OK menus, and an 80-channel selection with an always-visible mode toolbar. Captures contain fixtures only and live in the test application's cache. `RepositoryIntegrationTest.management*` separately verifies real Room transactions, failure rollback, stale-order rejection and independent preferences.
