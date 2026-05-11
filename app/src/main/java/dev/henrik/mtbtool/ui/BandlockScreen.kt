package dev.henrik.mtbtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import dev.henrik.mtbtool.ExecutionManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

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
    executionManager: ExecutionManager,
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
        val rawLtePrimary = executionManager.execMtbWithOutput(
            arrayOf("4", "4", "0", BandlockManager.PATH_LTE_PRIMARY)
        )
        val rawLteExt = executionManager.execMtbWithOutput(
            arrayOf("4", "4", "0", BandlockManager.PATH_LTE_EXTENSION)
        )
        val rawNrNsa = executionManager.execMtbWithOutput(
            arrayOf("4", "4", "0", BandlockManager.PATH_NR_NSA)
        )
        val rawNr = executionManager.execMtbWithOutput(
            arrayOf("4", "4", "0", BandlockManager.PATH_NR)
        )

        val enabledLte = BandlockManager.parseLtePrimary(BandlockManager.parseBytesOrEmpty(rawLtePrimary)) +
                         BandlockManager.parseLteExtension(BandlockManager.parseBytesOrEmpty(rawLteExt))
        val enabledNrNsa = BandlockManager.parseNrNsa(BandlockManager.parseBytesOrEmpty(rawNrNsa))
        val enabledNr    = BandlockManager.parseNr(BandlockManager.parseBytesOrEmpty(rawNr))

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
        if (!executionManager.isReady) {
            if (supportedLte.isNotEmpty() || supportedNrNsa.isNotEmpty() || supportedNrSa.isNotEmpty()) {
                detectInfoMessage = "Backend not ready — your configured bands are still active."
            } else {
                state = BandlockState.DetectError("Backend not ready")
                showDetectFailedDialog = true
            }
            return
        }
        state = BandlockState.Detecting
        scope.launch {
            try {
                executionManager.execMtbWithOutput(BandlockManager.DIAG_OPEN_ARGS)
                val raw = executionManager.execMtbWithOutput(BandlockManager.DIAG_READ_ARGS)
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

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val isBusy = state is BandlockState.Detecting || state is BandlockState.Applying

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = "Bandlock",
            scrollBehavior = scrollBehavior,
            defaultWindowInsetsPadding = true,
            bottomContent = {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmallTitle("Detect bands")
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
                        if (bandSource == BandSource.Diag) {
                            Text(
                                text  = "Band detection uses automatic offset guessing and may not be fully accurate on all devices.",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        }
                    }

                    if (state is BandlockState.Idle) {
                        Text(
                            text  = "Reads hardware-supported bands directly from the modem via DIAG, then shows which are currently enabled.",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
            }
        )

        // ── Pinned section (does not scroll) ──────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Always-visible manual config entry
            Card {
                ArrowPreference(
                    title   = "Configure bands manually",
                    summary = "Set supported bands for your device",
                    onClick = { if (!isBusy) showConfig = true }
                )
            }

            // ── Error / info messages ──────────────────────────────────────────
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

            // ── Band checkboxes ────────────────────────────────────────────────
            val showBands = supportedLte.isNotEmpty() || supportedNrNsa.isNotEmpty() || supportedNrSa.isNotEmpty()
            val checkboxEnabled = (state is BandlockState.Ready || state is BandlockState.ApplyError) && !isBusy

            if (showBands) {
                val lteBands   = supportedLte.sorted()
                val nrNsaBands = supportedNrNsa.sorted()
                val nrSaBands  = supportedNrSa.sorted()

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

                // Band Presets — pinned above the scrollable bands
                Card {
                    OverlayDropdownPreference(
                        title              = "Band Presets",
                        summary            = "Quickly select or unselect groups of bands",
                        entries            = presetEntries,
                        collapseOnSelection = true,
                        enabled            = checkboxEnabled,
                    )
                }

                // ── Scrollable bands + apply + reboot ─────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(bottom = contentPadding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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

                    if (state is BandlockState.Ready ||
                        state is BandlockState.Applying ||
                        state is BandlockState.ApplyError
                    ) {
                        Button(
                            onClick = {
                                if (!executionManager.isReady) {
                                    state = BandlockState.ApplyError("Backend not ready")
                                    return@Button
                                }
                                state = BandlockState.Applying
                                scope.launch {
                                    try {
                                        val chosenLte   = lteChecked.filterValues  { it }.keys
                                        val chosenNrNsa = nrNsaChecked.filterValues { it }.keys
                                        val chosenNrSa  = nrSaChecked.filterValues  { it }.keys

                                        val ltePrimaryBytes = BandlockManager.buildLtePrimary(chosenLte)
                                        executionManager.execMtbWithOutput(
                                            arrayOf("4", "5", "0", BandlockManager.PATH_LTE_PRIMARY) +
                                            ltePrimaryBytes.map { it.toString() }.toTypedArray()
                                        )

                                        val lteExtBytes = BandlockManager.buildLteExtension(chosenLte)
                                        executionManager.execMtbWithOutput(
                                            arrayOf("4", "5", "0", BandlockManager.PATH_LTE_EXTENSION) +
                                            lteExtBytes.map { it.toString() }.toTypedArray()
                                        )

                                        val nrNsaBytes = BandlockManager.buildNrNsa(chosenNrNsa)
                                        executionManager.execMtbWithOutput(
                                            arrayOf("4", "5", "0", BandlockManager.PATH_NR_NSA) +
                                            nrNsaBytes.map { it.toString() }.toTypedArray()
                                        )

                                        val nrSaBytes = BandlockManager.buildNr(chosenNrSa)
                                        executionManager.execMtbWithOutput(
                                            arrayOf("4", "5", "0", BandlockManager.PATH_NR) +
                                            nrSaBytes.map { it.toString() }.toTypedArray()
                                        )

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

                    // ── Reboot section ─────────────────────────────────────────
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
                                        executionManager.execMtb(arrayOf("11", "0"))
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
                }
            }
        }
    }

    BandConfigBottomSheet(
        show      = showConfig,
        dataStore = dataStore,
        onSaved   = { saved ->
            supportedLte   = saved.lte
            supportedNrNsa = saved.nrNsa
            supportedNrSa  = saved.nr
            bandSource     = BandSource.Manual
            scope.launch {
                readCurrentPrefs(saved.lte, saved.nrNsa, saved.nr)
                state = BandlockState.Ready
            }
        },
        onDismiss = { showConfig = false },
    )

    WindowDialog(
        show             = showDetectFailedDialog,
        title            = "Detection failed",
        summary          = "Supported bands could not be detected automatically. You can configure your device's supported bands manually instead.",
        onDismissRequest = { showDetectFailedDialog = false }
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text     = "Dismiss",
                onClick  = { showDetectFailedDialog = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text     = "Configure manually",
                onClick  = {
                    showDetectFailedDialog = false
                    showConfig = true
                },
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }

    WindowDialog(
        show             = showSwitchToDiagDialog,
        title            = "Bands detected",
        summary          = "Hardware-supported bands were detected. Do you want to use them, or keep your manually configured bands?",
        onDismissRequest = { showSwitchToDiagDialog = false; pendingDiagBands = null }
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text     = "Keep my configuration",
                onClick  = { showSwitchToDiagDialog = false; pendingDiagBands = null },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
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
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.textButtonColorsPrimary()
            )
        }
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

// ── BandConfigBottomSheet ──────────────────────────────────────────────────────

@Composable
private fun BandConfigBottomSheet(
    show: Boolean,
    dataStore: DataStore<Preferences>,
    onSaved: (SavedBands) -> Unit,
    onDismiss: () -> Unit,
) {
    val lteChecked   = remember { mutableStateMapOf<Int, Boolean>() }
    val nrNsaChecked = remember { mutableStateMapOf<Int, Boolean>() }
    val nrChecked    = remember { mutableStateMapOf<Int, Boolean>() }
    var saveError    by remember { mutableStateOf<String?>(null) }
    val scope        = rememberCoroutineScope()

    // Load saved bands whenever the sheet opens
    LaunchedEffect(show) {
        if (show) {
            saveError = null
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
        }
    }

    val sheetScrollState = rememberScrollState()

    // Prevent the sheet from being dragged down / dismissed while the inner scrollable
    // content is being scrolled upward.
    //
    // Problem: when the user drags upward and the inner verticalScroll reaches the top
    // mid-gesture, the remaining delta leaks as available.y > 0 to the sheet's
    // NestedScrollConnection which then drags the sheet down and may dismiss it.
    //
    // Strategy:
    //   • onPreScroll (upward, i.e. delta < 0): record whether the content was scrolled
    //     at all during this gesture by checking if the scroll position was > 0.
    //   • onPostScroll: always consume upward available (available.y > 0) so the sheet
    //     never gets "leftover" drag from a content-scroll gesture.
    //   • onPostFling: only consume if the content actually scrolled (gestureHadScroll).
    //     If the user starts a fresh upward fling from the very top (no content to
    //     scroll), we let it through so the sheet can still be flung away.
    val consumeUpwardWhenScrolled = remember(sheetScrollState) {
        object : NestedScrollConnection {
            // True when the current gesture scrolled content (scroll position was > 0
            // at some point during the gesture).
            var gestureHadScroll = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && sheetScrollState.value > 0) {
                    // Upward drag while not at top — mark that this gesture scrolled content.
                    gestureHadScroll = true
                }
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    // Downward drag — reset flag (new gesture direction).
                    gestureHadScroll = false
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Consume all upward leftover scroll so the sheet never drags down
                // mid-content-scroll (including the transition moment when scroll
                // position hits 0 within a single drag event).
                return if (available.y > 0f) available else Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val hadScroll = gestureHadScroll
                gestureHadScroll = false // reset for next gesture
                // If this fling scrolled content, consume any leftover upward velocity
                // so the sheet isn't dismissed by residual fling from a scroll gesture.
                return if (available.y > 0f && hadScroll) available else Velocity.Zero
            }
        }
    }

    OverlayBottomSheet(
        show             = show,
        title            = "Configure bands manually",
        onDismissRequest = onDismiss,
        startAction      = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector        = MiuixIcons.Close,
                    contentDescription = "Discard",
                    tint               = MiuixTheme.colorScheme.onBackground
                )
            }
        },
        endAction = {
            IconButton(
                onClick = {
                    saveError = null
                    scope.launch {
                        try {
                            val selectedLte   = lteChecked.filterValues { it }.keys.toSet()
                            val selectedNrNsa = nrNsaChecked.filterValues { it }.keys.toSet()
                            val selectedNr    = nrChecked.filterValues { it }.keys.toSet()
                            BandlockManager.saveBands(dataStore, selectedLte, selectedNrNsa, selectedNr)
                            onSaved(SavedBands(lte = selectedLte, nrNsa = selectedNrNsa, nr = selectedNr))
                            onDismiss()
                        } catch (e: Exception) {
                            saveError = e.message ?: "Unknown error"
                        }
                    }
                }
            ) {
                Icon(
                    imageVector        = MiuixIcons.Ok,
                    contentDescription = "Save",
                    tint               = MiuixTheme.colorScheme.onBackground
                )
            }
        },
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .nestedScroll(consumeUpwardWhenScrolled)
                .verticalScroll(sheetScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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

            Spacer(Modifier.height(16.dp))
        }
    }
}
