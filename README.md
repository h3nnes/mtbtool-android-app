# MTB Tool

An Android app for managing EFS NV items on Xiaomi Qualcomm devices. Uses root access (preferred) or [Shizuku](https://github.com/RikkaApps/Shizuku) as a fallback for privileged access to `/vendor/bin/mtb`.

It wraps around the mtb binary located in `/vendor/bin` directory. If this binary does not exist, the app will NOT work! Tested on MIUI/HyperOS devices running Android 13+.

## Screenshots

<p align="center">
  <img src="https://github.com/user-attachments/assets/ec4a6e0d-8985-4ac5-8d25-d118e9c8e6ea" width="48%" alt="Screenshot 1"/>
  <img src="https://github.com/user-attachments/assets/39d40b37-4512-4477-96ed-58e8ce59b76a" width="48%" alt="Screenshot 2"/>
</p>
<p align="center">
  <img src="https://github.com/user-attachments/assets/cc6b48e9-5736-4b82-b60e-c8afb652b242" width="48%" alt="Screenshot 3"/>
  <img src="https://github.com/user-attachments/assets/febfcfcb-c5b0-41c0-ab43-6aecd5c978e5" width="48%" alt="Screenshot 4"/>
</p>

## What it does

### Import tab
Bulk-imports EFS NV item configuration files (`.txt`) exported from QPST/QXDM. Parses the file into individual write/delete commands and runs them sequentially via `mtb`, showing live progress. After a successful import a "Reboot Modem" button appears to apply the changes.

### Read tab
Reads a single EFS NV item by name and displays its raw bytes as a colour-coded hex dump. Supports 4G/LTE (`/nv/item_files/modem/lte/rrc/efs/`), 5G/NR (`/nv/item_files/modem/nr5g/RRC/`), and a custom base path, selectable via a dropdown. The hex view is horizontally scrollable. The item name field triggers a read on keyboard confirm as well as the Send button.

### Features tab
Checks and disables specific modem features by writing known-good NV values:

| Feature | Description |
|---|---|
| R17 2T2T UL Tx Switching | Disables R17 uplink Tx switching band combos |
| R16 2T1T UL Tx Switching | Disables R16 uplink Tx switching band combos |
| UL MIMO (limit to 1Tx) | Limits uplink MIMO to 1 transmit antenna |
| NR UL Carrier Aggregation | Disables NR uplink carrier aggregation |
| DL NR-CA (1cc only) | Limits downlink NR-CA to one component carrier |
| Lowband 4Rx → 2Rx | Limits low-band reception to 2 receive antennas |

Each feature row shows as a toggle. Enabled (on) = can be disabled; greyed out (off) = already disabled. Toggling a switch off writes the NV values immediately. A "Reboot Modem" button at the bottom applies all changes. Already-disabled features can be restored to their original values via the "Restore original values" button.

### Bandlock tab
Configures and applies a persistent band lock to the modem by writing three EFS NV items:

| NV path | Size | Purpose |
|---|---|---|
| `.../mmode/lte_bandpref` | 8 bytes | LTE bands B1–B64 |
| `.../mmode/lte_bandpref_extn_65_256` | 24 bytes | LTE extension bands (B66, B71) |
| `.../mmode/nr_band_pref` | 64 bytes | NR bands (sub-6 and mmWave) |

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
App version, backend description, and device compatibility notes.

## Backend

The app tries to acquire privileged access in this order:

1. **Root** — binds a `RootService` (libsu 6) running as root UID. Preferred; works without Shizuku.
2. **Shizuku** — binds a `UserService` running at shell UID. Used automatically if root is not available or denied.

At least one backend must be running for any command to execute. The status banner at the top of each tab indicates whether the backend is ready.

## Structure

```
app/src/main/
├── aidl/dev/henrik/mtbtool/
│   └── IMtbService.aidl              # Shared AIDL interface (execMtb, execMtbWithOutput)
└── java/dev/henrik/mtbtool/
    ├── MainActivity.kt               # Entry point, backend lifecycle, DataStore instance
    ├── ExecutionManager.kt           # Unified backend: root-first with Shizuku fallback
    ├── RootManager.kt                # libsu RootService binding lifecycle
    ├── MtbRootService.kt             # RootService implementation (runs as root UID)
    ├── ShizukuManager.kt             # Shizuku permission + UserService binding
    ├── MtbUserService.kt             # Shizuku UserService implementation (runs at shell UID)
    ├── BulkImporter.kt               # Parses .txt NV files, streams import events
    ├── FeatureDef.kt                 # Feature definitions, NV write payloads, isDisabled checks
    ├── FeaturesChecker.kt            # checkAll() and disableFeature() coroutine functions
    ├── BandPreferences.kt            # DataStore key definitions for band configuration
    ├── BandlockManager.kt            # Band bitmask build/parse logic + DIAG offset detection
    ├── CellMonitor.kt                # Cell polling commands, response parsers, and StalenessTracker
    └── ui/
        ├── MainScreen.kt             # 6-tab scaffold with FloatingBottomBar + status bar fade
        ├── HomeScreen.kt             # Import tab UI
        ├── ReadScreen.kt             # Read tab UI + horizontally scrollable hex dump
        ├── FeaturesScreen.kt         # Features tab UI
        ├── BandlockScreen.kt         # Bandlock tab UI + BandConfigScreen
        ├── CellsScreen.kt            # Cells tab UI
        ├── InfoScreen.kt             # Info tab UI
        ├── NvParseUtils.kt           # Shared hex output parsing + byte colouring
        └── component/
            ├── FloatingBottomBar.kt  # Liquid-glass navigation bar
            └── animation/
                ├── DampedDragAnimation.kt
                ├── DragGestureInspector.kt
                └── InteractiveHighlight.kt
```

## Requirements

- Android 13+ (minSdk 33)
- Xiaomi device with Qualcomm modem and `mtb` binary at `/vendor/bin/mtb`
- **Root access** (granted via su), OR **Shizuku 13+** running on the device

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

## License

MIT — see [LICENSE](LICENSE).
