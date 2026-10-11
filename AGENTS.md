# Airloom — guide for developers and agents

Airloom is a native Android TV app for a single IPTV provider, IPTV.ONLINE. It is a standalone project: do not bring in code or assets from other projects. Check `git status` before changing anything and preserve unfinished work that is not yours; when working in parallel, agree on file ownership first.

## Conventions

- **Languages.** `AGENTS.md`, `docs/BUILD.md`, code comments and commit messages are written in English. `README.md`, release notes and every string in the app UI are in Russian.
- **The repository is public.** Tracked files must never contain real provider keys, private URLs, access headers, contents of the user's sources, LAN addresses or device models, e-mail addresses, paths to other projects, or verbatim user messages. The same goes for fixtures, screenshots, logs, reports and shell commands that end up in history. Use synthetic data in tests; app errors must not reveal full stream URLs.
- **Local-only files** (git-ignored, absent from a fresh clone): `.env.local` — TV address and model (`.env` is the tracked template); `docs/VALIDATION.md` — the validation log; `tools/`, `artifacts/`, `local.properties`.
- **Git.** Commit, merge and push only when the user asks. Commit with the identity already set in the repository-local git config, and do not add `Co-Authored-By` trailers.

## Product and constraints

- The acceptance device is an Android 11 / API 30 TV. The project uses `minSdk 26`, `compileSdk 35`, `targetSdk 35`, a Russian UI, Compose for TV / TV Material 3 and D-pad navigation. The app does not need root.
- One profile and one IPTV.ONLINE key. The wizard and settings offer `iptv.online`, `iptv.me` or a custom main domain; playlist, EPG, logo and stream domains can each be overridden. An unset stream domain keeps the addresses from the M3U. EPG comes in full or light variants, as `.xml` or `.xml.gz`.
- Importing arbitrary M3U/XMLTV sources or local files is not part of the UI. The M3U/XMLTV parsers remain as an internal part of the provider import. Text fields have a "Вставить" (paste) button that reads the clipboard **of the TV itself**, not of the computer running ADB.
- The home screen is "Каналы" (Channels): an always-visible rail on the left (logo, favourites, recents, all channels, categories; search and settings are buttons in its header, visible when the rail is expanded), next to it the channel list with number, current programme and its progress, and on the right the stage — a channel frame with the current programme title and the schedule with a date picker. The rail expands with labels over the screen when it gets focus. There is no shared TV-guide grid. OK opens the player; the player's channel panel is a card on the left over the video with the current section's list (the same one CH+/CH− step through), and ← swaps it for the sections. Search is a layer over the channels screen (`SearchPanel`), not a separate screen: the rail header button opens a card in place of the rail and list, Back closes it. Search and the supported player actions give access to the archive.
- A cold start must not begin playback or produce sound. With preview enabled, a short silent capture of a single frame with a time limit is allowed. Favourites, order, hidden state and manual EPG bindings must survive an update; a failed or cancelled import must not destroy the last working version.

| Module | Responsibility |
|---|---|
| `app` | Compose UI, navigation, ViewModel, wizard and settings |
| `core` | Pure Kotlin models, M3U/XMLTV, URL and archive rules |
| `data` | Room, DataStore, import, EPG and WorkManager |
| `playback` | Media3, the player and first-frame capture |

The Room schema is at version 8 (migration 7→8 rebuilds the programme search index and takes a while on a large database at first launch). Every schema change needs a migration and an updated exported schema in `data/schemas/`.

## UI design system

The UI is built from shared parts in `app/.../ui/Theme.kt`. New screens and edits must use them instead of introducing their own colours and focus styles.

- **Colour by role** — `LocalPalette`: background, panel, container, tile, text, line; `focus`/`onFocus`; an accent with its container and a paired dark tone. The four accents are a table in `accentTones`; there is no dynamic palette because it needs Android 12.
- **One focus style** — `Modifier.focusHighlight`: a light fill means "I am here", the accent means "selected" or "on air". Focus never uses borders or an accent fill.
- **Text is never smaller than 12 sp**: `Label` raises anything smaller to 12. Main roles: 22 — panel title, 24–30 — programme title, 15 — channel, 14 — programme, 12 — caption.
- **Margins**: `SafeHorizontal` 48 dp and `SafeVertical` 27 dp on every screen. The 80 dp rail sits in the left margin.
- **Shape**: `RowShape` 14 dp for rows, `PanelShape` 24 dp for the frame and panels, fully rounded player buttons and chips. The expanded rail is a card with a 28 dp radius on all corners, inset 12 dp (`DrawerInset`) from the left, top and bottom edges; its items start 22 dp from the screen edge.
- **One channel row** — `ChannelRowBody` in the main list, the player's channel panel and search. For an archive row, search passes `title` (programme title) and `highlight` (the match is drawn in the accent).
- **Long OK in search** on a channel row opens the same `RowActionsPopup` as the channel list, without pin and move: those belong to a list, and search results are not one. When hiding, focus first moves to another channel's row (`hiding` in `SearchPanel`); archive rows have no long press. EPG binding lives in settings, in the channel card.
- **Search is a 428 dp card** with the same `DrawerInset` and 28 dp radius as the expanded rail: it covers the rail and the list and stops 12 dp short of the stage. The query field holds focus with a light fill, like rows (`SearchField`; the bordered `Entry` is for settings and the wizard). Search has no paste button.
- **The player's channel panel is a 340 dp card on the left** (`PlayerChannelsPanel`, layout width `PlayerPanelWidth` 352 dp): same `DrawerInset` and 28 dp radius, rows 320 dp wide as in the main list. The header shows the section name and channel count. The playing channel has an accent fill (`selected` in `ChannelRowBody`) and an equaliser. ← swaps the list for sections — the same `RailItem`s in rail order (`railEntries`); OK picks a section, → and Back return to the channels without picking. Picking a section changes `channelSelection`, so it also changes the section on the channels screen. Long OK opens `RowActionsPopup` with favourite and hide, as in search. The video under the card is not dimmed.
- There is deliberately no background blur: `RenderEffect` needs Android 12 and is expensive on a weak CPU.

## UI responsiveness: invariants

The TV is slow, so recompositions and main-thread work are kept to a minimum. Preserve these rules when touching the UI or the player:

- **List rows receive only what they draw.** `ChannelRow`, `ChannelRowBody`, `ChannelList`, `ChannelProgrammePanel`, `NavigationRail`, `RailItem`, `PlayerChannelRow` do not take `UiState`. Programme progress reaches a row as a whole percent, so a clock tick recomposes a row only when its bar has moved. Lambdas passed into rows must not capture `state`, or every state update recomposes all visible rows again.
- **Models are declared stable** in `compose-stability.conf` (`tv.airloom.core.*`, `PlaybackState` and related classes). They must remain immutable data classes.
- **Playback position** updates every 500 ms and is read only in `PlayerControls`, from the `playback.state` flow. In `UiState.player` position and duration are zeroed on purpose.
- **EPG for lists** is requested through `ReportEpgWindow` / `UiPolicies.epgWindow`: a 40-channel window that shifts once per 10 rows. Do not bring back a request per row or resetting `epgLoad` to `LOADING` on scroll.
- **No hashing, disk access or player creation in composition.** The logo key is computed in `LogoDiskCache.resolve` off the main thread; preview frame capture starts after focus has rested for 700 ms.
- **The player is reused** for ordinary channel switches and server-side seeking (`AirloomPlayer.playFrom`). It is recreated only after an error, on auto-recovery, on manual retry and when network settings change.
- **The stale M3U/EPG check** on open starts after 45 seconds so the import does not coincide with the list loading.
- **Animations are read only in the draw phase.** Focus (`focusHighlight`) and rail expansion (`expansion` in `NavigationLayout`) are `State<Float>` values read by `graphicsLayer`, `drawBehind` and `drawWithContent`. Do not read an animated value in composition and do not bring back `animateColorAsState` for focus: the row would recompose on every frame again.
- **Panel widths are not animated.** The rail is always laid out at its full 296 dp width and clipped when drawn; the screen content under it neither moves nor re-lays out.
- **A focused row must not be removed.** When hiding a channel or category, focus first moves to a neighbouring row and only then the hide runs (`hiding` in `ChannelsScreen` and `NavigationRail`). Otherwise the system picks a row itself, and that row becomes the selected channel.
- **Left from content**: the focus search is cancelled (`focusProperties { exit }` in `NavigationLayout`) and the rail opens on the current section's item. Rail items are always focusable.
- **Rail header**: search and settings are `HeaderButton`s in the logo row, at the right edge of the 296 dp layout, so the collapsed rail clips them. There are three ways in: a second ← from the rail list, Back from the channel list (`BackHandler` in `NavigationLayout` moves focus to `NavigationFocus.header` and then calls the regular `actions.back()`, so the exit hint and two-press exit are preserved) and ↑ from the first item. Inside the header the rail itself handles ← and →; → from settings returns to the content. Back handlers inside the content are registered later and take priority.
- **Search is a layer.** `Screen.SEARCH` remains a navigation state (the player returns to it), but `AirloomApp` shows the same content for it as for `GUIDE`, with `SearchPanel` on top. The channels screen under the layer gets `LocalUiInteractive = false`: nothing is focusable, Back handlers are off, no new preview frames are captured and the frame already shown stays (`captureEnabled` in `ChannelFrame`). After the layer closes, `ChannelsScreen` returns focus to the selected row itself. The card's reveal is a single `expansion` value read only when drawing; the card is laid out at full width and clipped.
- **Player channel panel.** Its reveal is a single `expansion` value read only when drawing; there is no `AnimatedVisibility` around the panel, `PlayerOverlay` passes `open`. The list comes from `state.channels`: the panel does not sort or filter anything itself. While channels and sections replace each other, focus is parked on the card header (`parked`): it is focusable only at that moment and when the list is empty. After a section is picked the card stays on the sections until a new `state.channels` arrives (`choosing`; 300 ms if the list did not change), so rows never appear or get replaced under the focus. Hiding, and removing a favourite while in the favourites section, first move focus to a neighbouring row (`leaving`).
- **Search results are not cleared when the same query runs again** (minute tick, settings change): the focused row must not disappear. Only new text in `search()` clears them. Channel-name matches appear immediately, programmes when the database answers.
- **Programme search filters in SQLite.** `globContaining` builds a `GLOB` pattern with a case set for every letter (SQLite's `LIKE` is not case-insensitive for Cyrillic), and the programme title is part of the index `(sourceId, generation, start DESC, id, title)`, so non-matching rows are neither read from the table nor reach Kotlin. Do not bring back paging in Kotlin and do not drop `title` from the index. The lower time bound is the longest archive among the channels, but no less than two days.

Baseline to compare against (temporary `SideEffect` counters in `ChannelRow`, `RailItem` and `PlayerChannelRow` inside a throwaway test, 30 synthetic channels): a message, an EPG load phase change, the schedule of an off-screen channel and a one-second tick recompose no rows; a minute tick recomposes each visible row once; moving focus to the next row costs two recompositions for both rows together; opening, stepping through and closing the rail costs one or two recompositions of its items. Never leave such counters in the sources.

## Environment and tools

JDK 21, Android SDK Platform 35, Build Tools 35.0.0 and Platform Tools are required. The Gradle Wrapper is 8.9.

- **Tools can live inside the project:** `tools/android-sdk` and `tools/jdk-expanded`. `scripts/environment.sh` finds them by itself; `JAVA_HOME`/`ANDROID_HOME`, when set, take priority. `tools/` and `local.properties` are git-ignored, so a fresh clone has neither.
- **`local.properties` holds an absolute `sdk.dir`.** After moving the project, point it at `<project root>/tools/android-sdk`, or Gradle will not find the SDK.
- **If the SDK or JDK is not found**, check `ls tools/android-sdk tools/jdk-expanded` and `cat local.properties`. Do not search the whole disk for tools — ask the user.
- **The main development machine has no system Java** (`/usr/bin/java` is the macOS stub). `java`, `javap`, `keytool` and `apksigner` are reachable only through the project scripts.
- **`scripts/environment.sh` is written for bash.** Sourcing it from zsh silently leaves `JAVA_HOME` and `ANDROID_HOME` empty. Run the ready-made scripts, or wrap the command: `bash -c 'source scripts/environment.sh; javap ...'`.
- `adb` and `rg` are expected on `PATH`. The emulator is started only through `scripts/tv-emulator.sh`; it is not on `PATH`.

## Build and checks

```sh
./scripts/check.sh                          # JVM tests, lint, debug APK and test APKs; about a minute
./scripts/check.sh --android emulator-5560  # the same plus instrumentation on the test emulator; 6–7 minutes — only with the user's consent
```

- **Ask the user before any emulator run.** `check.sh --android`, individual `am instrument` runs and starting an emulator for verification take a long time (a full run is 6–7 minutes plus emulator boot), so do not start them on your own: ask and wait for the answer. Without asking, run only `./scripts/check.sh`. New and changed UI tests must still compile (`check.sh` builds the test APKs). If the emulator run was skipped, say so explicitly in your report and in the validation log.
- `check.sh` runs Gradle with one worker and `android.experimental.lint.useK2Uast=false`: parallel lint crashes with an internal error on AGP 8.7.3. For single tasks use `./scripts/build.sh :app:compileDebugKotlin` and the like.
- A full emulator run takes more than two minutes: run it in the background or with a timeout of at least 10 minutes and write the output to a file. Look for `OK (N tests)` and `Tests run:`. Reports are also saved to `artifacts/check/`.
- Expected results: core — 48 tests (1 skipped), data — 11, playback — 9, app — 14; lint — 0 errors; data instrumentation — 35; app instrumentation — 121. There are no known failures: any failing test is a reason to investigate.
- `CataloguePerformanceTest` is excluded from the regular run: it is a slow synthetic benchmark.
- CI (`.github/workflows/check.yml`) runs `./scripts/check.sh` on every push to `main` and on pull requests. It has no emulator and no signing key.

Run the checks that match the change unless the user asked to skip them. Do not claim something was verified on a device without evidence.

## Emulators

| Serial | AVD | Purpose |
|---|---|---|
| `emulator-5560` | `Airloom_TV_API31` in `~/.cache/airloom-tools/test-avd` | Automated tests. The app here may be uninstalled and reinstalled |
| `emulator-5558` | `Airloom_TV_API31` in `~/.cache/airloom-tools/avd` | The user's manual testing. `install -r` only; never uninstall or clear data |

Run instrumentation only on `emulator-5560` and only after the user agrees. Both emulators are Android TV API 31, ARM64; they confirm neither the codecs nor the speed of an Android 11 TV.

```sh
# Start the test emulator headless (in the background); a cold boot takes up to a minute
AIRLOOM_AVD_HOME="$HOME/.cache/airloom-tools/test-avd" AIRLOOM_EMULATOR_PORT=5560 \
  ./scripts/tv-emulator.sh -no-window -no-audio -no-boot-anim
# Ready when this prints 1
adb -s emulator-5560 shell getprop sys.boot_completed
# One class or one test, after check.sh has installed the APKs
adb -s emulator-5560 shell am instrument -w -e class tv.airloom.app.NavigationRailUiTest \
  tv.airloom.app.test/androidx.test.runner.AndroidJUnitRunner
# Shut down when done
adb -s emulator-5560 emu kill
```

- **Always pass `-s`.** The TV usually shows up in `adb devices` too; a command without a serial may go to it.
- `println` output from tests is read with `adb -s emulator-5560 logcat -d -s System.out:I`.
- Debug and release are signed with different keys. If a release build is installed on the test emulator, `check.sh` fails while installing the debug APK — run `adb -s emulator-5560 uninstall tv.airloom.app` first. Never do that on `emulator-5558` or the TV.
- On `emulator-5558`, the debug and test APKs are signed with the permanent key through `scripts/sign-apk.sh` and are updated in place without losing data.

## Release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Make sure the signing key is in place: `ls ~/.local/share/airloom/signing` must list `release.p12` and `password`. **If the key is missing, `sign-apk.sh` silently creates a new one**, and that APK will not install over the app on the TV. Stop and ask the user about a backup.
3. `./scripts/sign-release.sh` builds the release with R8 and signs it. Output: `artifacts/airloom-release.apk`; the log must contain `Verified using v2 scheme (...): true` and `Verified using v3 scheme (...): true`.
4. Check that the release build starts on the test emulator: tests run on the debug build and do not catch R8 problems. As with any emulator run, ask the user first.

```sh
adb -s emulator-5560 uninstall tv.airloom.app
adb -s emulator-5560 install --no-streaming artifacts/airloom-release.apk
adb -s emulator-5560 logcat -c
adb -s emulator-5560 shell am start -W -n tv.airloom.app/.MainActivity
adb -s emulator-5560 shell pidof tv.airloom.app                 # the process is alive
adb -s emulator-5560 logcat -d | rg 'FATAL EXCEPTION'           # empty
adb -s emulator-5560 uninstall tv.airloom.app                   # hand the emulator back to debug tests
```

5. Publish a GitHub release only when the user asks. The tag is `vX.Y.Z` on the released commit, the asset is the signed APK named `airloom-X.Y.Z.apk`, the notes are in Russian. Update the file name in the install command in `README.md`.

```sh
cp artifacts/airloom-release.apk artifacts/airloom-X.Y.Z.apk
git tag vX.Y.Z && git push origin vX.Y.Z
gh release create vX.Y.Z artifacts/airloom-X.Y.Z.apk --title "Airloom X.Y.Z" --notes "..."
```

The permanent signing key and its password live **outside the repository** in `~/.local/share/airloom/signing` (or `AIRLOOM_SIGNING_DIR`). An installed app cannot be updated in place with a different key. `artifacts/` is git-ignored. Details are in `docs/BUILD.md`.

## Physical TV

Connect to, inspect and install on the TV **only within the user's current request**. An earlier deployment is not permission for a new one.

The TV runs Android 11 / API 30, `armeabi-v7a`, 1920×1080 at 320 dpi; `ro.config.low_ram` is empty. Its address and model are in `.env.local` as `AIRLOOM_TV` and `AIRLOOM_TV_MODEL`; if the file is missing, copy `.env` and ask the user for the values. The address may change — on failure, look it up in the TV's network settings or on the router. Root and changes to system apps are not needed.

```sh
source .env.local; TV="$AIRLOOM_TV"              # address:port
adb devices -l                                   # already connected if there is a "$TV device" line
adb -s "$TV" shell getprop ro.product.model      # must equal $AIRLOOM_TV_MODEL
adb -s "$TV" shell getprop ro.build.version.sdk  # 30
adb -s "$TV" shell dumpsys package tv.airloom.app | rg 'versionCode=|versionName='
```

- **Look at `adb devices -l` first.** If the TV is listed as `device`, use that server (the regular one, port 5037). The TV may drop off the list after sleeping.
- If the TV is not listed: `adb connect "$TV"`. `offline` or `unauthorized` means checking the TV screen, the debugging prompt and Wi-Fi.
- If `adb connect` answers `No route to host`, check `ping -c 1 "${TV%:*}"` and `nc -vz "${TV%:*}" 5555`. This happens regularly: when the network is fine and the regular server still cannot connect, use a separate server on local port 5038 — `adb -P 5038 start-server`, `adb -P 5038 connect "$TV"` — and add `-P 5038` to every command for the TV. Only that server may be restarted (`adb -P 5038 kill-server`).
- Do not stop the shared server on 5037: the emulators are on it. Do not talk to the TV through two servers in one session. Do not scan the network without need.

An authorised update that keeps the data — after the steps in "Release":

```sh
adb -s "$TV" install -r --no-streaming artifacts/airloom-release.apk   # expect Success
adb -s "$TV" shell dumpsys package tv.airloom.app | rg 'versionCode=|versionName='
```

- `install -r` keeps the database and settings. **Never use `uninstall`, `pm clear` or a different signing key.** `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a foreign key — fix the signature, do not remove the app.
- A downgrade without data loss is impossible. A bad release is fixed by a new release with a higher `versionCode`, which is why checking the release build on the emulator matters.
- Installing kills the running app. The package is `tv.airloom.app`, the activity `.MainActivity`. Start it with `adb -s "$TV" shell am start -n tv.airloom.app/.MainActivity` only when the task requires opening or checking the UI.
- Installing resets the app's compilation mode to `speed-profile`. Run `cmd package compile -m speed -f tv.airloom.app` only when the user asks.
- Remote control: `adb -s "$TV" shell input keyevent KEYCODE_DPAD_CENTER` and likewise `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT/BACK`. Scroll smoothness: `adb -s "$TV" shell dumpsys gfxinfo tv.airloom.app`.
- Do not collect a full `logcat` and do not take screenshots of screens that show the key.

## Documentation

- `README.md` is the project page: what the app is and how to build and use it, in Russian. `docs/BUILD.md` covers the environment and commands. Update them when the behaviour they describe changes.
- `docs/VALIDATION.md` is the local validation log (git-ignored; create it if it is missing). After a change, append a dated section: what changed, which commands passed with the actual numbers, what was not verified. Record a TV deployment as its own section, including the installed version — that state is kept there, not in this file.
