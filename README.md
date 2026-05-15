# MTB Tool

An Android app for managing EFS NV items on Xiaomi Qualcomm devices that works with root access or root-backed Shizuku. Probes root access and falls back to [Shizuku](https://github.com/RikkaApps/Shizuku) for privileged access to `/vendor/bin/mtb`.

It wraps around the mtb binary located in `/vendor/bin` directory. If this binary does not exist, the app will NOT work! Tested on MIUI/HyperOS devices running Android 13+.

[![tg_badge]](https://t.me/mtbtoolapp)

## Screenshots

<p align="center">
  <img src="https://github.com/user-attachments/assets/27cd16a2-d237-4c41-ab47-fcd53c060a52" width="48%" alt="Screenshot 1"/>
  <img src="https://github.com/user-attachments/assets/063bd70c-15d7-417b-a0f5-8440b26375c2" width="48%" alt="Screenshot 2"/>
</p>
<p align="center">
  <img src="https://github.com/user-attachments/assets/f03056ee-7122-4444-a158-87e2eeb4f1b8" width="48%" alt="Screenshot 3"/>
  <img src="https://github.com/user-attachments/assets/886bde76-07a5-4ca1-a53a-f251f1bf0fdc" width="48%" alt="Screenshot 4"/>
</p>


## What it does

### Import tab
Bulk-imports EFS NV item files from a `.json` file. Parses the import json file into individual write/delete commands and runs them sequentially via `mtb`, showing live progress. After a successful import a "Reboot Modem" button appears to apply the changes. See [tools directory](tools/) for a description of the file format that is used.

### Read tab
Reads a single EFS NV item by name and displays its raw bytes as a colour-coded hex dump. Supports 4G/LTE (`/nv/item_files/modem/lte/rrc/efs/`), 5G/NR (`/nv/item_files/modem/nr5g/RRC/`), and a custom base path, selectable via a dropdown. The hex view is horizontally scrollable. The item name field triggers a read on keyboard confirm as well as the Send button.

### Features tab
Checks and disables specific modem features by writing known-good NV values:

Each feature row shows as a toggle. Enabled features make the toggle appear as off; greyed out enabled toggle = already disabled. Toggling a switch on writes the NV values to disable a feature immediately. A "Reboot Modem" button at the bottom applies all changes. Already-disabled features can be restored to their original values via the "Restore original values" button. If an NV item did not exist before disabling a feature, it will be deleted again upon restoring previous values.

### Bandlock tab
Configures and applies a persistent band lock to the modem by writing three EFS NV items:

| NV path | Size | Purpose |
|---|---|---|
| `/nv/item_files/modem/mmode/lte_bandpref` | 8 bytes | LTE bands B1–B64 |
| `/nv/item_files/modem/mmode/lte_bandpref_extn_65_256` | 24 bytes | LTE extension bands (B66, B71) |
| `/nv/item_files/modem/mmode/nr_nsa_band_pref` | 64 bytes | NR NSA bands (sub-6 and mmWave) |
| `/nv/item_files/modem/mmode/nr_band_pref` | 64 bytes | NR SA bands (sub-6 and mmWave) |

**Workflow:**

1. **Detect supported bands** — reads hardware-supported bands directly from the modem via DIAG. On success, the supported band list is populated automatically and the current NV lock is read and shown. On failure (device doesn't expose this NV item), a dialog offers to configure bands manually instead.
2. **Configure bands manually** — always accessible via the "Configure bands manually" entry. Opens a configuration screen where you tick every LTE/NR band your hardware supports. Configuration is saved to DataStore and persists across restarts. On app start, previously saved bands are loaded immediately without needing to re-detect.
3. **Edit** — tick/untick bands in the 4-column grid to build the desired lock mask. Presets (select all / unselect all per band type) are available via a dropdown.
4. **Apply Bandlock** — writes all three NV items in sequence. A spinner is shown while writing.
5. **Reboot Modem** — sends the modem-restart command (`mtb 11 0`) to activate the new lock.

The band source (detected from hardware or manually configured) is shown as a caption below the detect button. If detection succeeds after the user has manual bands configured, a dialog asks whether to switch to the detected bands or keep the manual configuration.

### Cells tab
Live monitor for LTE and NR cell information. Polls the modem at a configurable interval (1 s, 2 s, 5 s, 10 s, 30 s) and displays per-cell signal metrics for all visible LTE and NR cells, plus uplink TX power. Start/stop logging with a single button. Cell data persists across tab switches — polling continues in the background while you navigate the app.

### Info tab
App version, backend description, and device compatibility notes. Also, a HyperOS 3 inspired glow effect background animation :)

## Backend

The app tries to acquire privileged access in this order:

1. **Root** — binds a `RootService` (libsu 6) running as root UID. Preferred; works without Shizuku.
2. **Shizuku** — binds a `UserService` running at shell UID. Used automatically if root is denied.

At least one backend must be running for any command to execute. The status banner at the top of each tab indicates whether the backend is ready.

## Structure

```
app/src/main/aidl/
└── dev
    └── henrik
        └── mtbtool
            └── IMtbService.aidl
app/src/main/java/
└── dev
    └── henrik
        └── mtbtool
            ├── BandlockManager.kt
            ├── BandPreferences.kt
            ├── BulkImporter.kt
            ├── CellMonitor.kt
            ├── ExecutionManager.kt
            ├── FeatureDef.kt
            ├── FeaturesChecker.kt
            ├── MainActivity.kt
            ├── MtbRootService.kt
            ├── MtbUserService.kt
            ├── NvImportParser.kt
            ├── RootManager.kt
            ├── ShizukuManager.kt
            └── ui
                ├── BandlockScreen.kt
                ├── CellsScreen.kt
                ├── component
                │   ├── animation
                │   │   ├── DampedDragAnimation.kt
                │   │   ├── DragGestureInspector.kt
                │   │   └── InteractiveHighlight.kt
                │   └── FloatingBottomBar.kt
                ├── effect
                │   ├── BgEffectBackground.kt
                │   ├── BgEffectConfig.kt
                │   ├── BgEffectPainter.kt
                │   ├── DeviceType.kt
                │   ├── FrameTimeSeconds.kt
                │   └── OS3BgFrag.kt
                ├── FeaturesScreen.kt
                ├── HomeScreen.kt
                ├── InfoScreen.kt
                ├── MainScreen.kt
                ├── NvParseUtils.kt
                └── ReadScreen.kt
```

## Requirements

- Android 13+ (minSdk 33)
- Xiaomi device with Qualcomm modem and `mtb` binary at `/vendor/bin/mtb`
- Root-backed **Shizuku 13+** running on the device, OR classic su **Root access**

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

## License

[MIT LICENSE](LICENSE).

[tg_badge]: https://img.shields.io/badge/TG-Channel-4991D3?style=for-the-badge&logo=telegram
