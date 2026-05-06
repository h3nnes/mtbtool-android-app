# MTB Tool

An Android app for performing bandlock and managing EFS NV items on Qualcomm-based Xiaomi devices without root, using [Shizuku](https://github.com/RikkaApps/Shizuku) for privileged access.

It wraps around the mtb binary located in `/vendor/bin` directory. If this binary does not exist, the app will NOT work! Tested on MIUI/HyperOS devices running Android 13+.

## What it does

### Import tab
Bulk-imports EFS NV item configuration files (`.txt`) exported from QPST/QXDM. Parses the file into individual write/delete commands and runs them sequentially via `mtb`, showing live progress. After a successful import a "Reboot Modem" button appears to apply the changes.

### Read tab
Reads a single EFS NV item by name and displays its raw bytes as a color-coded hex dump. Supports both 4G/LTE (`/nv/item_files/modem/lte/rrc/efs/`) and 5G/NR (`/nv/item_files/modem/nr5g/RRC/`) base paths, selectable via a dropdown.

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

### Info tab
App version and basic usage notes.

## Structure

```
app/src/main/
├── aidl/dev/henrik/mtbtool/
│   └── IMtbService.aidl          # Shizuku UserService interface (execMtb, execMtbWithOutput)
└── java/dev/henrik/mtbtool/
    ├── MainActivity.kt           # Entry point, Shizuku lifecycle, DataStore instance
    ├── ShizukuManager.kt         # Shizuku permission + UserService binding
    ├── MtbUserService.kt         # Privileged UserService, runs mtb binary
    ├── BulkImporter.kt           # Parses .txt files, streams import events
    ├── FeatureDef.kt             # Feature definitions, NV write payloads, isDisabled checks
    ├── FeaturesChecker.kt        # checkAll() and disableFeature() coroutine functions
    ├── BandPreferences.kt        # DataStore key definitions for band configuration
    ├── BandlockManager.kt        # Band bitmask build/parse logic + DataStore helpers
    └── ui/
        ├── MainScreen.kt         # 5-tab scaffold with FloatingBottomBar
        ├── HomeScreen.kt         # Import tab UI
        ├── ReadScreen.kt         # Read tab UI + hex display
        ├── FeaturesScreen.kt     # Features tab UI
        ├── BandlockScreen.kt     # Bandlock tab UI + BandConfigScreen
        ├── InfoScreen.kt         # Info tab UI
        ├── NvParseUtils.kt       # Shared hex output parsing + byte coloring
        └── component/
            └── FloatingBottomBar.kt  # Liquid-glass navigation bar
```

## Requirements

- Android 13+ (minSdk 33)
- Shizuku 13+ running on the device
- Xiaomi device with Qualcomm modem and `mtb` binary at `/vendor/bin/mtb`

## Build

Make sure, that `Shizuku-API` and `miuix` are inside the same folder as the app project.

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Transparency

Non-biological intelligence was used to support the development of this app

## License

MIT — see [LICENSE](LICENSE).
