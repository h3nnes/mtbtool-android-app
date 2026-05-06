package dev.henrik.mtbtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.henrik.mtbtool.BandlockManager
import dev.henrik.mtbtool.SavedBands
import dev.henrik.mtbtool.ShizukuManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

// ── State ──────────────────────────────────────────────────────────────────────

sealed class BandlockState {
    /** Nothing detected yet. */
    data object Idle : BandlockState()
    /** Running the two DIAG commands + 4 NV reads. */
    data object Detecting : BandlockState()
    /** Detection succeeded; checkboxes reflect current NV prefs. */
    data object Ready : BandlockState()
    /** Detection or NV-read failed. */
    data class DetectError(val message: String) : BandlockState()
    /** Writing NV paths. */
    data object Applying : BandlockState()
    /** Write failed. */
    data class ApplyError(val message: String) : BandlockState()
}

enum class BandSource { Diag, Manual }

private val ColorError = Color(0xFFF44336)

// ── BandlockScreen ─────────────────────────────────────────────────────────────

@Composable
fun BandlockScreen(
    shizukuManager: ShizukuManager,
    dataStore: DataStore<Preferences>,
    contentPadding: PaddingValues = PaddingValues()
) {
    var state by remember { mutableStateOf<BandlockState>(BandlockState.Idle) }
    var rebootError by remember { mutableStateOf<String?>(null) }
    var rebootInFlight by remember { mutableStateOf(false) }
    var showReboot by remember { mutableStateOf(false) }
    var bandSource by remember { mutableStateOf<BandSource?>(null) }
    var showDetectFailedDialog by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var detectInfoMessage by remember { mutableStateOf<String?>(null) }
    var pendingDiagBands by remember { mutableStateOf<SavedBands?>(null) }
    var showSwitchToDiagDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Hardware-supported band sets — fixed after first detection
    var supportedLte   by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var supportedNrNsa by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var supportedNrSa  by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Checkbox state: key = band number, value = currently enabled in NV
    val lteChecked   = remember { mutableStateMapOf<Int, Boolean>() }
    val nrNsaChecked = remember { mutableStateMapOf<Int, Boolean>() }
    val nrSaChecked  = remember { mutableStateMapOf<Int, Boolean>() }

    // ── Helper: read current NV prefs and populate checkboxes ─────────────────
    // Called after detection (to show current state) and after apply (to reflect written values).
    suspend fun readCurrentPrefs(
        supported_lte: Set<Int>,
        supported_nrNsa: Set<Int>,
        supported_nrSa: Set<Int>
    ) {
        val rawLtePrimary = shizukuManager.execMtbWithOutput(
            arrayOf("4", "4", "0", BandlockManager.PATH_LTE_PRIMARY)
        )
        val rawLteExt = shizukuManager.execMtbWithOutput(
            arrayOf("4", "4", "0", BandlockManager.PATH_LTE_EXTENSION)
        )
        val rawNrNsa = shizukuManager.execMtbWithOutput(
            arrayOf("4", "4", "0", BandlockManager.PATH_NR_NSA)
        )
        val rawNr = shizukuManager.execMtbWithOutput(
            arrayOf("4", "4", "0", BandlockManager.PATH_NR)
        )

        val enabledLte = BandlockManager.parseLtePrimary(BandlockManager.parseBytes(rawLtePrimary)) +
                         BandlockManager.parseLteExtension(BandlockManager.parseBytes(rawLteExt))
        val enabledNrNsa = BandlockManager.parseNrNsa(BandlockManager.parseBytes(rawNrNsa))
        val enabledNr    = BandlockManager.parseNr(BandlockManager.parseBytes(rawNr))

        lteChecked.clear()
        supported_lte.forEach   { band -> lteChecked[band]   = band in enabledLte }
        nrNsaChecked.clear()
        supported_nrNsa.forEach { band -> nrNsaChecked[band] = band in enabledNrNsa }
        nrSaChecked.clear()
        supported_nrSa.forEach  { band -> nrSaChecked[band]  = band in enabledNr }
    }

    // On start: load persisted bands from DataStore; if configured go straight to Ready
    LaunchedEffect(Unit) {
        val saved = BandlockManager.loadSavedBands(dataStore)
        if (BandlockManager.isConfigured(saved.lte, saved.nrNsa, saved.nr)) {
            supportedLte   = saved.lte
            supportedNrNsa = saved.nrNsa
            supportedNrSa  = saved.nr
            bandSource     = BandSource.Manual
            readCurrentPrefs(saved.lte, saved.nrNsa, saved.nr)
            state = BandlockState.Ready
        }
    }

    // ── Detect / re-detect logic ───────────────────────────────────────────────
    fun runDetect() {
        detectInfoMessage = null
        if (!shizukuManager.isReady) {
            if (supportedLte.isNotEmpty() || supportedNrNsa.isNotEmpty() || supportedNrSa.isNotEmpty()) {
                detectInfoMessage = "Shizuku not ready — your configured bands are still active."
            } else {
                state = BandlockState.DetectError("Shizuku not ready")
                showDetectFailedDialog = true
            }
            return
        }
        state = BandlockState.Detecting
        scope.launch {
            try {
                shizukuManager.execMtbWithOutput(BandlockManager.DIAG_OPEN_ARGS)
                val raw = shizukuManager.execMtbWithOutput(BandlockManager.DIAG_READ_ARGS)
                val diagBytes = BandlockManager.parseDiagResponse(raw)
                val hasBands = supportedLte.isNotEmpty() || supportedNrNsa.isNotEmpty() || supportedNrSa.isNotEmpty()
                if (diagBytes.isEmpty()) {
                    if (hasBands) {
                        detectInfoMessage = "Hardware-supported bands could not be detected. Your configured bands are still active."
                        state = BandlockState.Ready
                    } else {
                        state = BandlockState.DetectError("No response from modem — unexpected output format")
                        showDetectFailedDialog = true
                    }
                    return@launch
                }
                val detected = BandlockManager.parseSupportedBands(diagBytes)

                if (detected.lte.isEmpty() && detected.nrNsa.isEmpty() && detected.nrSa.isEmpty()) {
                    if (hasBands) {
                        detectInfoMessage = "Hardware-supported bands could not be detected. Your configured bands are still active."
                        state = BandlockState.Ready
                    } else {
                        state = BandlockState.DetectError("Hardware-supported bands could not be detected.")
                        showDetectFailedDialog = true
                    }
                    return@launch
                }

                // Detection succeeded
                if (bandSource == BandSource.Manual) {
                    // User has manual config — ask whether to switch
                    pendingDiagBands = SavedBands(detected.lte, detected.nrNsa, detected.nrSa)
                    showSwitchToDiagDialog = true
                    state = BandlockState.Ready
                } else {
                    supportedLte   = detected.lte
                    supportedNrNsa = detected.nrNsa
                    supportedNrSa  = detected.nrSa
                    readCurrentPrefs(detected.lte, detected.nrNsa, detected.nrSa)
                    showReboot = false
                    bandSource = BandSource.Diag
                    state = BandlockState.Ready
                }
            } catch (e: Exception) {
                val hasBands = supportedLte.isNotEmpty() || supportedNrNsa.isNotEmpty() || supportedNrSa.isNotEmpty()
                if (hasBands) {
                    detectInfoMessage = "Hardware-supported bands could not be detected. Your configured bands are still active."
                    state = BandlockState.Ready
                } else {
                    state = BandlockState.DetectError(e.message ?: "Unknown error")
                    showDetectFailedDialog = true
                }
            }
        }
    }

    if (showConfig) {
        BandConfigScreen(
            dataStore = dataStore,
            onSaved = { saved ->
                supportedLte   = saved.lte
                supportedNrNsa = saved.nrNsa
                supportedNrSa  = saved.nr
                bandSource     = BandSource.Manual
                showConfig     = false
                scope.launch {
                    readCurrentPrefs(saved.lte, saved.nrNsa, saved.nr)
                    state = BandlockState.Ready
                }
            },
            onBack = { showConfig = false },
            contentPadding = contentPadding
        )
    } else {

    val isBusy = state is BandlockState.Detecting || state is BandlockState.Applying

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                top    = contentPadding.calculateTopPadding() + 16.dp,
                start  = 16.dp,
                end    = 16.dp,
                bottom = 0.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Detect button ──────────────────────────────────────────────────────
        Button(
            onClick  = { runDetect() },
            modifier = Modifier.fillMaxWidth(),
            enabled  = !isBusy,
            colors   = ButtonDefaults.buttonColorsPrimary()
        ) {
            if (state is BandlockState.Detecting) CircularProgressIndicator()
            else Text(if (state is BandlockState.Idle) "Detect Supported Bands" else "Re-detect Supported Bands")
        }

        // Band source caption — shown once bands are available
        if (bandSource != null) {
            Text(
                text  = when (bandSource) {
                    BandSource.Diag   -> "Source: Detected from hardware"
                    BandSource.Manual -> "Source: Manually configured"
                    null              -> ""
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        }

        if (state is BandlockState.Idle) {
            Text(
                text  = "Reads hardware-supported bands directly from the modem via DIAG, then shows which are currently enabled.",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        }

        // Always-visible manual config entry
        Card {
            ArrowPreference(
                title   = "Configure bands manually",
                summary = "Set supported bands for your device",
                onClick = { if (!isBusy) showConfig = true }
            )
        }

        // ── Error / info messages ──────────────────────────────────────────────
        detectInfoMessage?.let {
            Text(
                text  = it,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        }
        when (val s = state) {
            is BandlockState.DetectError -> Text(
                text  = s.message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
            is BandlockState.ApplyError -> Text(
                text  = "Apply error: ${s.message}",
                style = MiuixTheme.textStyles.body2,
                color = ColorError
            )
            else -> {}
        }

        // ── Band checkboxes ────────────────────────────────────────────────────
        // Shown whenever we have supported band data (Ready, Applying, ApplyError)
        val showBands = supportedLte.isNotEmpty() || supportedNrNsa.isNotEmpty() || supportedNrSa.isNotEmpty()
        val checkboxEnabled = (state is BandlockState.Ready || state is BandlockState.ApplyError) && !isBusy

        if (showBands) {
            val lteBands   = supportedLte.sorted()
            val nrNsaBands = supportedNrNsa.sorted()
            val nrSaBands  = supportedNrSa.sorted()

            // ── Band presets ───────────────────────────────────────────────────
            fun allEntry(label: String, selectAll: Boolean, map: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Boolean>) =
                DropdownItem(
                    text     = label,
                    selected = false,
                    onClick  = { map.keys.forEach { map[it] = selectAll } }
                )

            val presetEntries = listOf(
                DropdownEntry(items = listOf(
                    DropdownItem(text = "Select all bands",   selected = false, onClick = {
                        lteChecked.keys.forEach   { lteChecked[it]   = true }
                        nrNsaChecked.keys.forEach { nrNsaChecked[it] = true }
                        nrSaChecked.keys.forEach  { nrSaChecked[it]  = true }
                    }),
                    DropdownItem(text = "Unselect all bands", selected = false, onClick = {
                        lteChecked.keys.forEach   { lteChecked[it]   = false }
                        nrNsaChecked.keys.forEach { nrNsaChecked[it] = false }
                        nrSaChecked.keys.forEach  { nrSaChecked[it]  = false }
                    }),
                )),
                DropdownEntry(items = listOf(
                    allEntry("Select all 4G bands",   true,  lteChecked),
                    allEntry("Unselect all 4G bands", false, lteChecked),
                )),
                DropdownEntry(items = listOf(
                    allEntry("Select all 5G NSA bands",   true,  nrNsaChecked),
                    allEntry("Unselect all 5G NSA bands", false, nrNsaChecked),
                )),
                DropdownEntry(items = listOf(
                    allEntry("Select all 5G SA bands",   true,  nrSaChecked),
                    allEntry("Unselect all 5G SA bands", false, nrSaChecked),
                )),
            )

            Card {
                OverlayDropdownPreference(
                    title              = "Band Presets",
                    summary            = "Quickly select or unselect groups of bands",
                    entries            = presetEntries,
                    collapseOnSelection = true,
                    enabled            = checkboxEnabled,
                )
            }

            if (lteBands.isNotEmpty()) {
                Text(
                    text     = "4G Bands",
                    style    = MiuixTheme.textStyles.title4,
                    color    = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Card {
                    BandCheckboxGrid(
                        bands           = lteBands,
                        checked         = lteChecked,
                        prefix          = "B",
                        enabled         = checkboxEnabled,
                        onCheckedChange = { band, checked -> lteChecked[band] = checked }
                    )
                }
            }

            if (nrNsaBands.isNotEmpty()) {
                Text(
                    text  = "5G NSA Bands",
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onBackground
                )
                Card {
                    BandCheckboxGrid(
                        bands           = nrNsaBands,
                        checked         = nrNsaChecked,
                        prefix          = "N",
                        enabled         = checkboxEnabled,
                        onCheckedChange = { band, checked -> nrNsaChecked[band] = checked }
                    )
                }
            }

            if (nrSaBands.isNotEmpty()) {
                Text(
                    text  = "5G SA Bands",
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onBackground
                )
                Card {
                    BandCheckboxGrid(
                        bands           = nrSaBands,
                        checked         = nrSaChecked,
                        prefix          = "N",
                        enabled         = checkboxEnabled,
                        onCheckedChange = { band, checked -> nrSaChecked[band] = checked }
                    )
                }
            }

            // ── Apply button ───────────────────────────────────────────────────
            if (state is BandlockState.Ready ||
                state is BandlockState.Applying ||
                state is BandlockState.ApplyError
            ) {
                Button(
                    onClick = {
                        if (!shizukuManager.isReady) {
                            state = BandlockState.ApplyError("Shizuku not ready")
                            return@Button
                        }
                        state = BandlockState.Applying
                        scope.launch {
                            try {
                                val chosenLte   = lteChecked.filterValues  { it }.keys
                                val chosenNrNsa = nrNsaChecked.filterValues { it }.keys
                                val chosenNrSa  = nrSaChecked.filterValues  { it }.keys

                                val ltePrimaryBytes = BandlockManager.buildLtePrimary(chosenLte)
                                shizukuManager.execMtbWithOutput(
                                    arrayOf("4", "5", "0", BandlockManager.PATH_LTE_PRIMARY) +
                                    ltePrimaryBytes.map { it.toString() }.toTypedArray()
                                )

                                val lteExtBytes = BandlockManager.buildLteExtension(chosenLte)
                                shizukuManager.execMtbWithOutput(
                                    arrayOf("4", "5", "0", BandlockManager.PATH_LTE_EXTENSION) +
                                    lteExtBytes.map { it.toString() }.toTypedArray()
                                )

                                val nrNsaBytes = BandlockManager.buildNrNsa(chosenNrNsa)
                                shizukuManager.execMtbWithOutput(
                                    arrayOf("4", "5", "0", BandlockManager.PATH_NR_NSA) +
                                    nrNsaBytes.map { it.toString() }.toTypedArray()
                                )

                                val nrSaBytes = BandlockManager.buildNr(chosenNrSa)
                                shizukuManager.execMtbWithOutput(
                                    arrayOf("4", "5", "0", BandlockManager.PATH_NR) +
                                    nrSaBytes.map { it.toString() }.toTypedArray()
                                )

                                // Re-read NV prefs to reflect what was actually written
                                readCurrentPrefs(supportedLte, supportedNrNsa, supportedNrSa)
                                showReboot = true
                                rebootError = null
                                state = BandlockState.Ready
                            } catch (e: Exception) {
                                state = BandlockState.ApplyError(e.message ?: "Unknown error")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled  = state is BandlockState.Ready || state is BandlockState.ApplyError,
                    colors   = ButtonDefaults.buttonColorsPrimary()
                ) {
                    if (state is BandlockState.Applying) CircularProgressIndicator()
                    else Text("Apply Bandlock")
                }
            }
        }

        // ── Reboot section (shown after a successful apply until next re-detect) ──
        if (showReboot) {
            Text(
                text  = "Bandlock applied. Reboot the modem to activate.",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackground
            )
            rebootError?.let { err ->
                Text(
                    text  = "Reboot error: $err",
                    style = MiuixTheme.textStyles.body2,
                    color = ColorError
                )
            }
            Button(
                onClick = {
                    if (rebootInFlight) return@Button
                    rebootInFlight = true
                    rebootError    = null
                    scope.launch {
                        try {
                            shizukuManager.execMtb(arrayOf("11", "0"))
                        } catch (e: Exception) {
                            rebootError = e.message ?: "Unknown error"
                        } finally {
                            rebootInFlight = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled  = !rebootInFlight
            ) {
                if (rebootInFlight) CircularProgressIndicator()
                else Text("Reboot Modem")
            }
        }

        Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
    }
    } // end else (showConfig == false)

    WindowDialog(
        show             = showDetectFailedDialog,
        title            = "Detection failed",
        summary          = "Supported bands could not be detected automatically. You can configure your device's supported bands manually instead.",
        onDismissRequest = { showDetectFailedDialog = false }
    ) {
        TextButton(
            text     = "Configure manually",
            onClick  = {
                showDetectFailedDialog = false
                showConfig = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.textButtonColorsPrimary()
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            text     = "Dismiss",
            onClick  = { showDetectFailedDialog = false },
            modifier = Modifier.fillMaxWidth()
        )
    }

    WindowDialog(
        show             = showSwitchToDiagDialog,
        title            = "Bands detected",
        summary          = "Hardware-supported bands were detected. Do you want to use them, or keep your manually configured bands?",
        onDismissRequest = { showSwitchToDiagDialog = false; pendingDiagBands = null }
    ) {
        TextButton(
            text     = "Use detected bands",
            onClick  = {
                showSwitchToDiagDialog = false
                pendingDiagBands?.let { bands ->
                    scope.launch {
                        supportedLte   = bands.lte
                        supportedNrNsa = bands.nrNsa
                        supportedNrSa  = bands.nr
                        readCurrentPrefs(bands.lte, bands.nrNsa, bands.nr)
                        showReboot = false
                        bandSource = BandSource.Diag
                        detectInfoMessage = null
                    }
                    pendingDiagBands = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.textButtonColorsPrimary()
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            text     = "Keep my configuration",
            onClick  = { showSwitchToDiagDialog = false; pendingDiagBands = null },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Band checkbox grid ─────────────────────────────────────────────────────────

@Composable
private fun BandCheckboxGrid(
    bands: List<Int>,
    checked: Map<Int, Boolean>,
    prefix: String,
    enabled: Boolean,
    onCheckedChange: (Int, Boolean) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        bands.chunked(4).forEach { group ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                group.forEach { band ->
                    Row(
                        modifier              = Modifier.weight(1f),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val isChecked = checked[band] == true
                        Checkbox(
                            state   = if (isChecked) ToggleableState.On else ToggleableState.Off,
                            onClick = if (enabled) { { onCheckedChange(band, !isChecked) } } else null,
                            enabled = enabled
                        )
                        Text(
                            text  = "$prefix$band",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onBackground
                        )
                    }
                }
                repeat(4 - group.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── BandConfigScreen ───────────────────────────────────────────────────────────

@Composable
private fun BandConfigScreen(
    dataStore: DataStore<Preferences>,
    onSaved: (SavedBands) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    var loaded by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val lteChecked   = remember { mutableStateMapOf<Int, Boolean>() }
    val nrNsaChecked = remember { mutableStateMapOf<Int, Boolean>() }
    val nrChecked    = remember { mutableStateMapOf<Int, Boolean>() }
    val scope        = rememberCoroutineScope()
    val scrollState  = rememberScrollState()

    LaunchedEffect(Unit) {
        val saved = BandlockManager.loadSavedBands(dataStore)
        BandlockManager.ALL_LTE_BANDS.forEach { band ->
            lteChecked[band] = band in saved.lte
        }
        BandlockManager.ALL_NR_BANDS.forEach { band ->
            nrNsaChecked[band] = band in saved.nrNsa
        }
        BandlockManager.ALL_NR_BANDS.forEach { band ->
            nrChecked[band] = band in saved.nr
        }
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                top    = contentPadding.calculateTopPadding() + 16.dp,
                start  = 16.dp,
                end    = 16.dp,
                bottom = 0.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onBack) { Text("← Back") }
            Text(
                text  = "Configure Device Bands",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onBackground
            )
        }

        if (!loaded) {
            CircularProgressIndicator()
        } else {
            Text(
                text  = "4G Bands",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onBackground
            )
            Card {
                BandCheckboxGrid(
                    bands           = BandlockManager.ALL_LTE_BANDS,
                    checked         = lteChecked,
                    prefix          = "B",
                    enabled         = true,
                    onCheckedChange = { band, checked -> lteChecked[band] = checked }
                )
            }

            Text(
                text  = "5G NSA Bands",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onBackground
            )
            Card {
                BandCheckboxGrid(
                    bands           = BandlockManager.ALL_NR_BANDS,
                    checked         = nrNsaChecked,
                    prefix          = "N",
                    enabled         = true,
                    onCheckedChange = { band, checked -> nrNsaChecked[band] = checked }
                )
            }

            Text(
                text  = "5G SA Bands",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onBackground
            )
            Card {
                BandCheckboxGrid(
                    bands           = BandlockManager.ALL_NR_BANDS,
                    checked         = nrChecked,
                    prefix          = "N",
                    enabled         = true,
                    onCheckedChange = { band, checked -> nrChecked[band] = checked }
                )
            }

            saveError?.let { err ->
                Text(
                    text  = "Save failed: $err",
                    style = MiuixTheme.textStyles.body2,
                    color = ColorError
                )
            }

            Button(
                onClick = {
                    saveError = null
                    scope.launch {
                        try {
                            val selectedLte   = lteChecked.filterValues { it }.keys.toSet()
                            val selectedNrNsa = nrNsaChecked.filterValues { it }.keys.toSet()
                            val selectedNr    = nrChecked.filterValues { it }.keys.toSet()
                            BandlockManager.saveBands(dataStore, selectedLte, selectedNrNsa, selectedNr)
                            onSaved(SavedBands(lte = selectedLte, nrNsa = selectedNrNsa, nr = selectedNr))
                        } catch (e: Exception) {
                            saveError = e.message ?: "Unknown error"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColorsPrimary()
            ) { Text("Save") }

            Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }
}
