// app/src/main/java/dev/henrik/mtbtool/ui/FeaturesScreen.kt
package dev.henrik.mtbtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.henrik.mtbtool.ALL_FEATURES
import dev.henrik.mtbtool.FeatureDef
import dev.henrik.mtbtool.FeatureStatus
import dev.henrik.mtbtool.ExecutionManager
import dev.henrik.mtbtool.checkAll
import dev.henrik.mtbtool.disableFeature
import dev.henrik.mtbtool.restoreFeature
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

private val simOptions = listOf("SIM 0", "SIM 1")
private val ColorErrorRed = Color(0xFFF44336)
private val ColorErrorOrange = Color(0xFFFF8A65)
private val ColorSuccess = Color(0xFF4CAF50)

sealed class FeaturesState {
    object Idle : FeaturesState()
    object Checking : FeaturesState()
    data class CheckError(val message: String) : FeaturesState()
    data class Checked(
        val results: Map<FeatureDef, FeatureStatus>,
        /** Raw bytes as read from NV at check time, keyed by feature. Null per-path entry
         *  means that NV item did not exist (absent = modem default). */
        val originalBytes: Map<FeatureDef, List<List<Int>?>>
    ) : FeaturesState()
}

private const val PATH_NR5G_MODE = "/nv/item_files/modem/mmode/nr5g_disable_mode"
private val nrModeTabLabels = listOf("SA/NSA", "NSA only", "SA only")

private sealed class NrModeState {
    data object Idle : NrModeState()
    data object Loading : NrModeState()
    data class Loaded(val index: Int) : NrModeState()
    data object Writing : NrModeState()
    data class Error(val message: String) : NrModeState()
}

@Composable
fun FeaturesScreen(
    executionManager: ExecutionManager,
    contentPadding: PaddingValues = PaddingValues()
) {
    var simSlot by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<FeaturesState>(FeaturesState.Idle) }
    var nrModeState by remember { mutableStateOf<NrModeState>(NrModeState.Idle) }
    var pendingNrIndex by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val rebootSnackbar = LocalRebootSnackbar.current

    suspend fun readNrMode() {
        nrModeState = NrModeState.Loading
        try {
            // NV item is modem-global; always use slot 0
            val raw = executionManager.execMtbWithOutput(
                arrayOf("4", "4", "0", PATH_NR5G_MODE)
            )
            val exitLine = raw.lines().firstOrNull() ?: ""
            val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
            val output = raw.lines().drop(1).joinToString("\n")
            if (exitCode != 0) {
                nrModeState = NrModeState.Error("Read failed (exit $exitCode)")
            } else {
                val byte = parseHexOutput(output).firstOrNull()?.firstOrNull()
                    ?.toIntOrNull(16) ?: -1
                // EFS byte → dropdown index: 0=SA/NSA (default), 1=NSA only, 2=SA only
                nrModeState = when (byte) {
                    0 -> NrModeState.Loaded(0)
                    1 -> NrModeState.Loaded(1)
                    2 -> NrModeState.Loaded(2)
                    else -> NrModeState.Error("Unexpected value: 0x${byte.toString(16)}")
                }
            }
        } catch (e: Exception) {
            nrModeState = NrModeState.Error(e.message ?: "Unknown error")
        }
    }

    // Auto-read on first composition
    LaunchedEffect(Unit) {
        if (executionManager.isReady) {
            readNrMode()
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = "Features",
            scrollBehavior = scrollBehavior,
            defaultWindowInsetsPadding = true,
            bottomContent = {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ── 5G Mode selector ──────────────────────────────────────────
                    SmallTitle("5G Mode")
                    val nrEnabled = nrModeState is NrModeState.Loaded
                    val nrCurrentIndex = (nrModeState as? NrModeState.Loaded)?.index ?: 0
                    TabRow(
                        modifier = Modifier.alpha(if (nrEnabled) 1f else 0.4f),
                        tabs = nrModeTabLabels,
                        selectedTabIndex = nrCurrentIndex,
                        onTabSelected = { newIndex ->
                            if (!nrEnabled || newIndex == nrCurrentIndex) return@TabRow
                            if (!executionManager.isReady) {
                                nrModeState = NrModeState.Error("Backend not ready")
                                return@TabRow
                            }
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            pendingNrIndex = newIndex
                        }
                    )
                    if (nrModeState is NrModeState.Error) {
                        Text(
                            text = (nrModeState as? NrModeState.Error)?.message ?: "",
                            style = MiuixTheme.textStyles.body2,
                            color = ColorErrorOrange,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    SmallTitle("Disable specific modem features")

                    // ── Check button ──────────────────────────────────────────────
                    Button(
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            if (!executionManager.isReady) {
                                state = FeaturesState.CheckError("Backend not ready")
                                return@Button
                            }
                            state = FeaturesState.Checking
                            scope.launch {
                                readNrMode()
                                try {
                                    val result = checkAll(simSlot, executionManager)
                                    state = FeaturesState.Checked(
                                        results = result.statuses,
                                        originalBytes = result.originalBytes
                                    )
                                } catch (e: Exception) {
                                    state = FeaturesState.CheckError(e.message ?: "Unknown error")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is FeaturesState.Checking,
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) { Text("Read available features") }

                    // ── SIM tab group ─────────────────────────────────────────────
                    TabRowWithContour(
                        tabs = simOptions,
                        selectedTabIndex = simSlot,
                        onTabSelected = { idx ->
                            if (idx != simSlot) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                simSlot = idx
                                state = FeaturesState.Idle
                            }
                        },
                    )
                }
            }
        )

        // ── Scrollable feature list ────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .scrollEndHaptic()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                is FeaturesState.Idle, is FeaturesState.Checking -> {
                    if (s is FeaturesState.Checking) CircularProgressIndicator()
                    Card {
                        ALL_FEATURES.forEach { feature ->
                            SwitchPreference(
                                title = feature.label,
                                checked = false,
                                onCheckedChange = {},
                                enabled = false,
                            )
                        }
                    }
                }
                is FeaturesState.CheckError -> {
                    Text(
                        text = s.message,
                        style = MiuixTheme.textStyles.body2,
                        color = ColorErrorRed
                    )
                }
                is FeaturesState.Checked -> {
                    val anyWriting = s.results.values.any {
                        it is FeatureStatus.Writing || it is FeatureStatus.Restoring
                    }
                    val canRestore = !anyWriting && s.originalBytes.any { (feature, _) ->
                        s.results[feature] is FeatureStatus.AlreadyDisabled
                    }

                    Card {
                        ALL_FEATURES.forEach { feature ->
                            val status = s.results[feature] ?: FeatureStatus.ReadError("No result")
                            FeatureRow(
                                feature = feature,
                                status = status,
                                onDisable = {
                                    val updated = s.results.toMutableMap()
                                    updated[feature] = FeatureStatus.Writing
                                    state = FeaturesState.Checked(updated, s.originalBytes)
                                    scope.launch {
                                        val error = disableFeature(feature, simSlot, executionManager)
                                        val current = (state as? FeaturesState.Checked)?.results?.toMutableMap()
                                            ?: return@launch
                                        current[feature] = if (error == null) FeatureStatus.AlreadyDisabled
                                                           else FeatureStatus.WriteError(error)
                                        state = FeaturesState.Checked(current, s.originalBytes)
                                    }
                                },
                                onRestore = {
                                    val slot = simSlot
                                    val orig = s.originalBytes[feature] ?: return@FeatureRow
                                    val updated = s.results.toMutableMap()
                                    updated[feature] = FeatureStatus.Restoring
                                    state = FeaturesState.Checked(updated, s.originalBytes)
                                    scope.launch {
                                        val error = restoreFeature(feature, orig, slot, executionManager)
                                        val current = (state as? FeaturesState.Checked)?.results?.toMutableMap()
                                            ?: return@launch
                                        current[feature] = if (error == null) {
                                            if (orig.any { it == null }) {
                                                FeatureStatus.CanDisable
                                            } else if (feature.isDisabled(orig.filterNotNull())) {
                                                FeatureStatus.AlreadyDisabled
                                            } else {
                                                FeatureStatus.CanDisable
                                            }
                                        } else {
                                            FeatureStatus.WriteError(error)
                                        }
                                        state = FeaturesState.Checked(current, s.originalBytes)
                                    }
                                }
                            )
                        }
                    }

                    if (s.originalBytes.isNotEmpty()) {
                        Button(
                            onClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                if (!executionManager.isReady) return@Button
                                val updated = s.results.toMutableMap()
                                s.originalBytes.keys.forEach { feature ->
                                    if (updated[feature] is FeatureStatus.AlreadyDisabled) {
                                        updated[feature] = FeatureStatus.Restoring
                                    }
                                }
                                state = FeaturesState.Checked(updated, s.originalBytes)
                                scope.launch {
                                    val orig = s.originalBytes
                                    for ((feature, bytes) in orig) {
                                        // Only restore features that were actually disabled by the user.
                                        val currentStatus = (state as? FeaturesState.Checked)?.results?.get(feature)
                                        if (currentStatus !is FeatureStatus.Restoring) continue
                                        val error = restoreFeature(feature, bytes, simSlot, executionManager)
                                        val current = (state as? FeaturesState.Checked)?.results?.toMutableMap()
                                            ?: return@launch
                                        current[feature] = if (error == null) {
                                            // If any path was originally absent, the feature is
                                            // back to default (enabled). Otherwise check bytes.
                                            val orig = bytes
                                            if (orig.any { it == null }) {
                                                FeatureStatus.CanDisable
                                            } else if (feature.isDisabled(orig.filterNotNull())) {
                                                FeatureStatus.AlreadyDisabled
                                            } else {
                                                FeatureStatus.CanDisable
                                            }
                                        } else {
                                            FeatureStatus.WriteError(error)
                                        }
                                        state = FeaturesState.Checked(current, s.originalBytes)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canRestore,
                        ) { Text("Restore all original states") }
                    }

                    Button(
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            scope.launch {
                                executionManager.execMtb(arrayOf("11", "0"))
                                rebootSnackbar.showSnackbar(
                                    message = REBOOT_SNACKBAR_MSG,
                                    duration = SnackbarDuration.Custom(6000)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !anyWriting,
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) { Text("Reboot Modem") }
                }
            }
            Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }

    // ── 5G Mode confirmation dialog ────────────────────────────────────────────
    val idx = pendingNrIndex
    if (idx != null) {
        WindowDialog(
            show = true,
            title = "Change 5G Mode",
            summary = "Switch to \"${nrModeTabLabels[idx]}\"? The modem will reboot to apply the change.",
            onDismissRequest = { pendingNrIndex = null }
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = "Abort",
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        pendingNrIndex = null
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "Apply",
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        pendingNrIndex = null
                        nrModeState = NrModeState.Writing
                        scope.launch {
                            try {
                                val raw = executionManager.execMtbWithOutput(
                                    arrayOf("4", "5", "0", PATH_NR5G_MODE, idx.toString())
                                )
                                val exitLine = raw.lines().firstOrNull() ?: ""
                                val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
                                nrModeState = if (exitCode == 0) {
                                    NrModeState.Loaded(idx)
                                } else {
                                    NrModeState.Error("Write failed (exit $exitCode)")
                                }
                                if (exitCode == 0) {
                                    executionManager.execMtb(arrayOf("11", "0"))
                                    rebootSnackbar.showSnackbar(
                                        message = REBOOT_SNACKBAR_MSG,
                                        duration = SnackbarDuration.Custom(6000)
                                    )
                                }
                            } catch (e: Exception) {
                                nrModeState = NrModeState.Error(e.message ?: "Unknown error")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(
    feature: FeatureDef,
    status: FeatureStatus,
    onDisable: () -> Unit,
    onRestore: () -> Unit
) {
    when (status) {
        is FeatureStatus.Writing, is FeatureStatus.Restoring -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = feature.label,
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        is FeatureStatus.ReadError -> {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(feature.label, style = MiuixTheme.textStyles.body1)
                Text(
                    text = status.message,
                    style = MiuixTheme.textStyles.body2,
                    color = ColorErrorOrange
                )
            }
        }
        is FeatureStatus.WriteError -> {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(feature.label, style = MiuixTheme.textStyles.body1)
                Text(
                    text = status.message,
                    style = MiuixTheme.textStyles.body2,
                    color = ColorErrorRed
                )
            }
        }
        is FeatureStatus.CanDisable, is FeatureStatus.AlreadyDisabled -> {
            val isChecked = status is FeatureStatus.AlreadyDisabled
            SwitchPreference(
                title = feature.label,
                checked = isChecked,
                onCheckedChange = { nowChecked ->
                    if (nowChecked && status is FeatureStatus.CanDisable) {
                        onDisable()
                    } else if (!nowChecked && status is FeatureStatus.AlreadyDisabled) {
                        onRestore()
                    }
                },
                enabled = true,
            )
        }
    }
}
