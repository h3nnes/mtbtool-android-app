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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.henrik.mtbtool.ExecutionManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val techOptions = listOf("4G LTE EFS", "5G NR RRC", "Custom Path")
private const val PATH_4G = "/nv/item_files/modem/lte/rrc/efs/"

private const val PATH_5G = "/nv/item_files/modem/nr5g/RRC/"

sealed class ReadState {
    object Idle : ReadState()
    object Loading : ReadState()
    data class Success(val hexRows: List<List<String>>) : ReadState()
    data class Error(val message: String) : ReadState()
}


@Composable
fun ReadScreen(
    executionManager: ExecutionManager,
    contentPadding: PaddingValues = PaddingValues()
) {
    var itemName by remember { mutableStateOf("") }
    var techIndex by remember { mutableIntStateOf(0) }
    var customPath by remember { mutableStateOf("") }
    val basePath = when (techIndex) {
        0 -> PATH_4G
        1 -> PATH_5G
        else -> customPath.trim().trimEnd('/') + "/"
    }
    var readState by remember { mutableStateOf<ReadState>(ReadState.Idle) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    fun doQuery() {
        val name = itemName.trim()
        if (name.isEmpty()) return
        if (!executionManager.isReady) {
            readState = ReadState.Error("Backend not ready")
            return
        }
        val path = basePath + name
        readState = ReadState.Loading
        scope.launch {
            val raw = executionManager.execMtbWithOutput(arrayOf("4", "4", "0", path))
            val exitLine = raw.lines().firstOrNull() ?: ""
            val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
            val output = raw.lines().drop(1).joinToString("\n")
            val rows = parseHexOutput(output)
            readState = if (rows.isNotEmpty()) {
                ReadState.Success(rows)
            } else {
                ReadState.Error(
                    if (exitCode != 0) "mtb exited with code $exitCode — item may not exist"
                    else "No hex data returned"
                )
            }
        }
    }

    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding() + 16.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 0.dp
                ),
        ) {
            SmallTitle("Read from EFS")
            // ── Input row ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = itemName,
                    onValueChange = { itemName = it },
                    label = "NV item name",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { doQuery() },
                    enabled = itemName.trim().isNotEmpty()
                        && readState !is ReadState.Loading
                        && !(techIndex == 2 && customPath.trim().isEmpty())
                ) {
                    Text("Send")
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Tech selector (4G / 5G) ───────────────────────────────────────
            Card {
                OverlayDropdownPreference(
                    title = "Base EFS Path",
                    items = techOptions,
                    selectedIndex = techIndex,
                    onSelectedIndexChange = { techIndex = it },
                )
            }

            if (techIndex == 2) {
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    label = "Custom base path",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Path preview ──────────────────────────────────────────────────
            Text(
                text = basePath + (itemName.trim().ifEmpty { "<name>" }),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
            Text(
                text = "Note: readout is limited to 365 bytes (mtb binary buffer limit)",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )

            Spacer(Modifier.height(16.dp))

            // ── Result area ───────────────────────────────────────────────────
            when (val s = readState) {
                is ReadState.Idle -> {
                    Text(
                        "Enter an item name above and tap Send.",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                    Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
                }
                is ReadState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
                }
                is ReadState.Success -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "Data:",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                        Spacer(Modifier.height(4.dp))
                        s.hexRows.forEachIndexed { idx, tokens ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "%04X  ".format(idx * 16),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    fontFamily = FontFamily.Monospace
                                )
                                tokens.forEachIndexed { tokenIdx, token ->
                                    if (tokenIdx > 0) {
                                        Text(
                                            text = " ",
                                            style = MiuixTheme.textStyles.body2,
                                            fontFamily = FontFamily.Monospace,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                                        )
                                    }
                                    Text(
                                        text = token,
                                        style = MiuixTheme.textStyles.body2,
                                        color = hexByteColor(token),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
                    }
                }
                is ReadState.Error -> {
                    Text(
                        text = s.message,
                        style = MiuixTheme.textStyles.body2,
                        color = Color(0xFFF44336)
                    )
                    Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
                }
            }
        }
    }
}
