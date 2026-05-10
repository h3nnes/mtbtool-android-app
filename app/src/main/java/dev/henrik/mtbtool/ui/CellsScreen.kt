package dev.henrik.mtbtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.henrik.mtbtool.LteCellData
import dev.henrik.mtbtool.NrCellData
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val CELL_REFRESH_OPTIONS = listOf(1, 2, 5, 10, 30) // seconds
internal val CELL_REFRESH_LABELS  = listOf("1s", "2s", "5s", "10s", "30s")
private val simOptions = listOf("SIM 0", "SIM 1")

private fun signalColor(fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return lerp(Color(0xFFb50d0dL), Color(0xFF2d8031L), clamped)
}

@Composable
fun CellsScreen(
    isLogging: Boolean,
    refreshIndex: Int,
    errorMessage: String?,
    lteCells: List<LteCellData>,
    nrCells: List<NrCellData>,
    txPower: Int?,
    hasPolled: Boolean,
    simSlot: Int,
    onSimSlotChange: (Int) -> Unit,
    onToggleLogging: () -> Unit,
    onRefreshIndexChange: (Int) -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val scrollState = rememberScrollState()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = "Cell Monitor",
            scrollBehavior = scrollBehavior,
            defaultWindowInsetsPadding = true,
            bottomContent = {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick  = onToggleLogging,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = if (isLogging) ButtonDefaults.buttonColors()
                                   else ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(if (isLogging) "Stop Logging" else "Start Logging")
                    }

                    Card {
                        TabRow(
                            tabs = simOptions,
                            selectedTabIndex = simSlot,
                            onTabSelected = { if (it != simSlot) onSimSlotChange(it) },
                        )
                    }

                    OverlayDropdownPreference(
                        title                 = "Refresh interval",
                        items                 = CELL_REFRESH_LABELS,
                        selectedIndex         = refreshIndex,
                        onSelectedIndexChange = onRefreshIndexChange
                    )

                    errorMessage?.let { err ->
                        Text(
                            text  = err,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasPolled) {
                Text("4G Cell Data", style = MiuixTheme.textStyles.title4,
                     color = MiuixTheme.colorScheme.onBackground)
                if (lteCells.isEmpty()) {
                    Text("No 4G data found", style = MiuixTheme.textStyles.body2,
                         color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                } else {
                    Card {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                               verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            lteCells.forEachIndexed { index, cell ->
                                if (index > 0) Spacer(Modifier.height(4.dp))
                                Text(cell.label, style = MiuixTheme.textStyles.title4,
                                     color = MiuixTheme.colorScheme.onBackground)
                                CellDataRow("EARFCN",     cell.earfcn.toString())
                                CellDataRow("PCI",        cell.pci.toString())
                                SignalBar("RSRP", cell.rsrp, "dBm", -125f, -90f)
                                SignalBar("RSRQ", cell.rsrq, "dB",  -20f,  -3f)
                                SignalBar("RSSI", cell.rssi, "dBm", -110f, -65f)
                                SignalBar("SNR",  cell.snr,  "dB",  -5f,    20f)
                            }
                        }
                    }
                }

                Text("5G Cell Data", style = MiuixTheme.textStyles.title4,
                     color = MiuixTheme.colorScheme.onBackground)
                if (nrCells.isEmpty()) {
                    Text("No 5G data found", style = MiuixTheme.textStyles.body2,
                         color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                } else {
                    Card {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                               verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            nrCells.forEachIndexed { index, cell ->
                                if (index > 0) Spacer(Modifier.height(4.dp))
                                Text(cell.label, style = MiuixTheme.textStyles.title4,
                                     color = MiuixTheme.colorScheme.onBackground)
                                SignalBar("RSRP", cell.rsrp, "dBm", -130f, -80f)
                                SignalBar("RSRQ", cell.rsrq, "dB",  -20f,  -3f)
                            }
                        }
                    }
                }

                txPower?.let { tx ->
                    Text("Tx Power", style = MiuixTheme.textStyles.title4,
                         color = MiuixTheme.colorScheme.onBackground)
                    Card {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                               verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SignalBar("Tx Power", tx.toFloat(), "dBm", 0f, 36f, invertColor = true)
                            if (tx > 36) {
                                Text(
                                    text  = "Values above 36 dBm are abnormal",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }
}

@Composable
private fun CellDataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text     = label,
            style    = MiuixTheme.textStyles.body1,
            color    = MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier.weight(0.6f)
        )
        Text(
            text      = value,
            style     = MiuixTheme.textStyles.body1,
            color     = MiuixTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier  = Modifier.weight(0.4f)
        )
    }
}

@Composable
private fun SignalBar(
    label: String,
    value: Float,
    unit: String,
    min: Float,
    max: Float,
    invertColor: Boolean = false
) {
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    val colorFraction = if (invertColor) 1f - fraction else fraction
    val barColor = signalColor(colorFraction)
    val fillFraction = fraction.coerceAtLeast(0.05f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text     = label,
            style    = MiuixTheme.textStyles.body1,
            color    = MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier.weight(0.6f)
        )
        Box(
            modifier = Modifier
                .weight(0.4f)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(barColor.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fillFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                    .background(barColor)
            )
            Text(
                text      = "%.1f %s".format(value, unit),
                style     = MiuixTheme.textStyles.body2.merge(
                    TextStyle(shadow = Shadow(color = Color(0x88000000L), offset = Offset(0f, 2f), blurRadius = 2f))
                ),
                color     = Color.White,
                textAlign = TextAlign.Center,
                modifier  = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
            )
        }
    }
}
