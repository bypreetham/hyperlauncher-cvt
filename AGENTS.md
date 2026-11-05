# AGENTS.md

For any coding agent (Claude Code, Gemini CLI, Antigravity, Codex, Cursor, Copilot ...). People: see [README.md](README.md).

## Jobs

| Asked to | Do |
|---|---|
| Install on a TV | Steps below |
| Clean up a TV | "Cleaning a TV" below |
| Change the app | "Working on the code" below |

## Install on a TV (3 commands)

The TV needs Developer options with **USB debugging** and **Network debugging** on, and a person must tick
*Always allow* on the TV's prompt once. You cannot do that part.

```bash
adb connect <TV_IP>:5555
./install.sh <TV_IP> [--lite]   # Windows: .\install.ps1 <TV_IP> [-Lite]; pass an APK path to skip the build
adb -s <TV_IP>:5555 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.hyperlauncher.tv/com.gathin.tvhome.MainActivity
```

Needs `adb`, JDK 17 (not newer) and `ANDROID_HOME`. `install.sh` writes `local.properties` if missing.

## Cleaning a TV (rules that are not negotiable)

1. **Back up first.** The app writes a snapshot (package lists, original settings) before its first change, to its
   files dir (`files/backup/snapshot-*.txt`). If you work with plain `adb`, save `pm list packages -d`,
   `pm list packages -3` and `getprop` yourself before the first change.
2. **Disable, never uninstall system apps.** Use `pm disable-user --user 0 <pkg>`, one package per call. No root.
   Every step must be undoable: `pm enable <pkg>` and `cmd package install-existing <pkg>`.
3. **Never touch protected packages**: `android`, `com.android.systemui|shell|phone|settings|tv.settings|providers.*|
   permissioncontroller|packageinstaller|inputmethod.*|bluetooth|tv|wifi*|externalstorage|documentsui|keychain`,
   `com.google.android.gms|gsf|vending|permissioncontroller|packageinstaller|ext.*|overlay.*`,
   and `com.hyperlauncher.tv`. The exact regex is `PROTECTED` in
   `app/src/main/java/com/hyperlauncher/control/ControlCenterActivity.java`; the app refuses these itself.
4. **Leave TV essentials alone**: HDMI/tuner/input services, remote and Bluetooth pairing, the keyboard, Play Store.
5. **Use the catalog**: `app/src/main/assets/debloat_catalog.txt` (tab separated: package, how many of 380 TV models list
   it, category ADS/TRACK/BG/BLOAT, recommended 0/1). Prefer `recommended=1`; show the user the list and ask before
   disabling anything else. Report exactly what you disabled.
6. **Undo**: the Control Center's *RESTORE EVERYTHING* (Debloat page) or `pm enable` each package you disabled.
7. Do not change DNS, Developer options or accessibility settings unless asked.

Useful on the TV: `adb -s <TV_IP>:5555 shell pm list packages -d` (disabled), `... -3` (user apps),
`dumpsys package <pkg>` (read before disabling anything unclear).

### Debug hook (debuggable builds only)

```bash
adb -s <TV_IP>:5555 shell am force-stop com.hyperlauncher.tv
adb -s <TV_IP>:5555 shell am start -n com.hyperlauncher.tv/com.hyperlauncher.control.ControlCenterActivity --es test state
adb -s <TV_IP>:5555 logcat -d -s HyperTest
```

`--es test quiet_ads | dns_on | disable (with --es arg <pkg>) | undo_ads | restore_all | state`.

## Working on the code

- Java + XML Views, no Compose (small TVs, 2 GB RAM). minSdk 26, targetSdk 34, AGP 8.5.2, Gradle 8.9, **JDK 17**.
- Two flavors: `full` (network ADB Control Center) and `lite` (no ADB client, no INTERNET permission). Build with
  `./gradlew --no-daemon :app:assembleFullDebug :app:assembleLiteDebug -q` → `app/build/outputs/apk/{full,lite}/debug/app-{full,lite}-debug.apk`.
  `BuildConfig.ADB` gates the Control Center; the real `AdbClient` is in `app/src/full`, a stub in `app/src/lite`. A change must work in both.
- Map:
  - `app/src/main/java/com/hyperlauncher/control/ControlCenterActivity.java`: Control Center, built in code (side rail,
    Overview, Monitor, Apps, Debloat, Home & Theme, Settings).
  - `app/src/full/.../control/AdbClient.java`: self-ADB over `127.0.0.1:5555` (CNXN/AUTH/OPEN/WRTE, RSA key in the app's files).
  - `app/src/main/java/com/gathin/tvhome/`: the launcher (`MainActivity`, `Config`), `Ui` (shared look), `Icons`
    (code-drawn icons), `LayoutSettingsActivity` ("Customize Launcher"), `WallpaperPickerActivity`, `HiddenAppsActivity`.
  - Launcher settings are in the `launcher_settings` SharedPreferences; bumping `Config.SETTINGS_REVISION_KEY` makes
    Home rebuild itself.
- A RecyclerView built in code cannot have scrollbars enabled (it crashes); ListView / ScrollView can.
- Check UI changes on a real TV or emulator with a screenshot; do not claim a screen works from a clean build alone.

## Commits

Commit as the repository owner. Do not add `Co-Authored-By` or tool-attribution trailers. Never commit `local.properties`,
APKs, backups of a TV, or keys.
