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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.henrik.mtbtool.LteCellData
import dev.henrik.mtbtool.NrCellData
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val CELL_REFRESH_OPTIONS = listOf(1, 2, 5, 10, 30) // seconds
internal val CELL_REFRESH_LABELS  = listOf("1s", "2s", "5s", "10s", "30s")

@Composable
fun CellsScreen(
    isLogging: Boolean,
    refreshIndex: Int,
    errorMessage: String?,
    lteCells: List<LteCellData>,
    nrCells: List<NrCellData>,
    txPower: Int?,
    hasPolled: Boolean,
    onToggleLogging: () -> Unit,
    onRefreshIndexChange: (Int) -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val scrollState = rememberScrollState()

    // Outer column: fills screen, not scrollable — pins controls at top
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top    = contentPadding.calculateTopPadding() + 16.dp,
                start  = 16.dp,
                end    = 16.dp,
                bottom = 0.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Pinned controls ────────────────────────────────────────────────────
        Column(
            modifier = Modifier.wrapContentHeight(),
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

        // ── Scrollable data area ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
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
                                CellDataRow("RSRP (dBm)", "%.1f".format(cell.rsrp))
                                CellDataRow("RSRQ (dB)",  "%.1f".format(cell.rsrq))
                                CellDataRow("RSSI (dBm)", "%.1f".format(cell.rssi))
                                CellDataRow("SNR (dB)",   "%.1f".format(cell.snr))
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
                                CellDataRow("RSRP (dBm)", "%.1f".format(cell.rsrp))
                                CellDataRow("RSRQ (dB)",  "%.1f".format(cell.rsrq))
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
                            CellDataRow("Tx Power (dBm)", tx.toString())
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
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MiuixTheme.textStyles.body1,
             color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        Text(text = value, style = MiuixTheme.textStyles.body1,
             color = MiuixTheme.colorScheme.onBackground)
    }
}
