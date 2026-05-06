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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import dev.henrik.mtbtool.ALL_FEATURES
import dev.henrik.mtbtool.FeatureDef
import dev.henrik.mtbtool.FeatureStatus
import dev.henrik.mtbtool.ShizukuManager
import dev.henrik.mtbtool.checkAll
import dev.henrik.mtbtool.disableFeature
import dev.henrik.mtbtool.restoreFeature
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        /** Raw bytes as read from NV at check time, keyed by feature. Only present for features that read successfully. */
        val originalBytes: Map<FeatureDef, List<List<Int>>>
    ) : FeaturesState()
}

private const val PATH_NR5G_MODE = "/nv/item_files/modem/mmode/nr5g_disable_mode"
private val nrModeOptions = listOf("SA/NSA (default)", "NSA only", "SA only")

private sealed class NrModeState {
    data object Idle : NrModeState()
    data object Loading : NrModeState()
    data class Loaded(val index: Int) : NrModeState()
    data object Writing : NrModeState()
    data class Error(val message: String) : NrModeState()
}

@Composable
fun FeaturesScreen(
    shizukuManager: ShizukuManager,
    contentPadding: PaddingValues = PaddingValues()
) {
    var simSlot by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<FeaturesState>(FeaturesState.Idle) }
    var nrModeState by remember { mutableStateOf<NrModeState>(NrModeState.Idle) }
    var writeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    suspend fun readNrMode() {
        nrModeState = NrModeState.Loading
        try {
            // NV item is modem-global; always use slot 0
            val raw = shizukuManager.execMtbWithOutput(
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
        if (shizukuManager.isReady) {
            readNrMode()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding() + 16.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = 0.dp
            )
    ) {
        // ── 5G Mode selector ──────────────────────────────────────────────────
        SmallTitle("5G Mode")
        Card {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                when (val m = nrModeState) {
                    is NrModeState.Loading, is NrModeState.Writing -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "5G Mode",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onBackground
                            )
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    is NrModeState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "5G Mode",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onBackground
                            )
                            Text(
                                text = m.message,
                                style = MiuixTheme.textStyles.body2,
                                color = ColorErrorOrange
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { scope.launch { readNrMode() } },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = nrModeState !is NrModeState.Loading,
                            ) { Text("Retry") }
                        }
                    }
                    is NrModeState.Idle, is NrModeState.Loaded -> {
                        val currentIndex = if (m is NrModeState.Loaded) m.index else 0
                        val isEnabled = m is NrModeState.Loaded
                        OverlayDropdownPreference(
                            title = "5G Mode",
                            items = nrModeOptions,
                            selectedIndex = currentIndex,
                            enabled = isEnabled,
                            onSelectedIndexChange = { newIndex ->
                                if (newIndex == currentIndex) return@OverlayDropdownPreference
                                if (!shizukuManager.isReady) {
                                    nrModeState = NrModeState.Error("Shizuku not ready")
                                    return@OverlayDropdownPreference
                                }
                                nrModeState = NrModeState.Writing
                                writeJob = scope.launch {
                                    try {
                                        // NV item is modem-global; always use slot 0
                                        val raw = shizukuManager.execMtbWithOutput(
                                            arrayOf("4", "5", "0", PATH_NR5G_MODE, newIndex.toString())
                                        )
                                        val exitLine = raw.lines().firstOrNull() ?: ""
                                        val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
                                        nrModeState = if (exitCode == 0) {
                                            NrModeState.Loaded(newIndex)
                                        } else {
                                            NrModeState.Error("Write failed (exit $exitCode)")
                                        }
                                    } catch (e: Exception) {
                                        nrModeState = NrModeState.Error(e.message ?: "Unknown error")
                                    }
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        scope.launch {
                            shizukuManager.execMtb(arrayOf("11", "0"))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    enabled = nrModeState is NrModeState.Loaded,
                ) { Text("Reboot Modem (5G Mode)") }
            }
        }

        Spacer(Modifier.height(12.dp))
        SmallTitle("Disable specific modem features")

        // ── SIM slot selector ─────────────────────────────────────────────────
        Card {
            OverlayDropdownPreference(
                title = "SIM Slot",
                items = simOptions,
                selectedIndex = simSlot,
                onSelectedIndexChange = { idx ->
                    if (idx != simSlot) {
                        writeJob?.cancel()
                        simSlot = idx
                        state = FeaturesState.Idle
                        nrModeState = NrModeState.Idle
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Check button ──────────────────────────────────────────────────────
        Button(
            onClick = {
                if (!shizukuManager.isReady) {
                    state = FeaturesState.CheckError("Shizuku not ready")
                    nrModeState = NrModeState.Error("Shizuku not ready")
                    return@Button
                }
                state = FeaturesState.Checking
                scope.launch {
                    // Read 5G mode
                    readNrMode()
                    // Check features
                    try {
                        val result = checkAll(simSlot, shizukuManager)
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
        ) { Text("Check") }

        Spacer(Modifier.height(16.dp))

        // ── State area ────────────────────────────────────────────────────────
        when (val s = state) {
            is FeaturesState.Idle, is FeaturesState.Checking -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                ) {
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
                    Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
                }
            }
            is FeaturesState.CheckError -> {
                Text(
                    text = s.message,
                    style = MiuixTheme.textStyles.body2,
                    color = ColorErrorRed
                )
                Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
            }
            is FeaturesState.Checked -> {
                val anyWriting = s.results.values.any {
                    it is FeatureStatus.Writing || it is FeatureStatus.Restoring
                }
                // Restore is available when at least one feature has original bytes stored
                // and at least one of those features is currently disabled (i.e. was changed)
                val canRestore = !anyWriting && s.originalBytes.any { (feature, _) ->
                    s.results[feature] is FeatureStatus.AlreadyDisabled
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                                        val error = disableFeature(feature, simSlot, shizukuManager)
                                        val current = (state as? FeaturesState.Checked)?.results?.toMutableMap()
                                            ?: return@launch
                                        current[feature] = if (error == null) FeatureStatus.AlreadyDisabled
                                                           else FeatureStatus.WriteError(error)
                                        state = FeaturesState.Checked(current, s.originalBytes)
                                    }
                                }
                            )
                        }
                    }

                    // ── Restore all button ─────────────────────────────────────
                    if (s.originalBytes.isNotEmpty()) {
                        Button(
                            onClick = {
                                if (!shizukuManager.isReady) return@Button
                                // Mark all restorable features as Restoring
                                val updated = s.results.toMutableMap()
                                // Mark only AlreadyDisabled features as Restoring (CanDisable ones are unmodified)
                                s.originalBytes.keys.forEach { feature ->
                                    if (updated[feature] is FeatureStatus.AlreadyDisabled) {
                                        updated[feature] = FeatureStatus.Restoring
                                    }
                                }
                                state = FeaturesState.Checked(updated, s.originalBytes)
                                scope.launch {
                                    val orig = s.originalBytes
                                    for ((feature, bytes) in orig) {
                                        val error = restoreFeature(feature, bytes, simSlot, shizukuManager)
                                        val current = (state as? FeaturesState.Checked)?.results?.toMutableMap()
                                            ?: return@launch
                                        current[feature] = if (error == null) {
                                            // Re-evaluate disabled status from original bytes
                                            if (feature.isDisabled(bytes)) FeatureStatus.AlreadyDisabled
                                            else FeatureStatus.CanDisable
                                        } else {
                                            FeatureStatus.WriteError(error)
                                        }
                                        state = FeaturesState.Checked(current, s.originalBytes)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canRestore,
                        ) { Text("Restore original values") }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                shizukuManager.execMtb(arrayOf("11", "0"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !anyWriting,
                    ) { Text("Reboot Modem") }
                    Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    feature: FeatureDef,
    status: FeatureStatus,
    onDisable: () -> Unit
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
            val isChecked = status is FeatureStatus.CanDisable
            val isEnabled = status is FeatureStatus.CanDisable
            SwitchPreference(
                title = feature.label,
                checked = isChecked,
                onCheckedChange = { nowChecked ->
                    if (!nowChecked && status is FeatureStatus.CanDisable) {
                        onDisable()
                    }
                },
                enabled = isEnabled,
            )
        }
    }
}
