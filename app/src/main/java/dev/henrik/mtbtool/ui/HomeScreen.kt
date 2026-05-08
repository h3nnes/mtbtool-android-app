package dev.henrik.mtbtool.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.henrik.mtbtool.BulkImporter
import dev.henrik.mtbtool.ImportCommand
import dev.henrik.mtbtool.ImportEvent
import dev.henrik.mtbtool.ExecutionManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

sealed class HomeState {
    object Idle : HomeState()
    data class FileSelected(val uri: Uri, val name: String, val commands: List<ImportCommand>) : HomeState()
    data class Importing(val commands: List<ImportCommand>, val done: Int, val results: List<Pair<ImportCommand, Int>>) : HomeState()
    data class Done(val ok: Int, val fail: Int, val results: List<Pair<ImportCommand, Int>>) : HomeState()
}

@Composable
fun HomeScreen(
    executionManager: ExecutionManager,
    state: HomeState,
    onStateChange: (HomeState) -> Unit,
    errorMessage: String?,
    onErrorChange: (String?) -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val commands = BulkImporter.parseFile(context.contentResolver, uri)
            val name = uri.lastPathSegment ?: uri.toString()
            onStateChange(HomeState.FileSelected(uri = uri, name = name, commands = commands))
            onErrorChange(null)
        } catch (e: Exception) {
            onErrorChange("Failed to read file: ${e.message}")
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
            // ── Shizuku banner ────────────────────────────────────────────
            if (!executionManager.isReady) {
                BackendBanner(onGrantClick = { executionManager.requestPermission() })
                Spacer(Modifier.height(8.dp))
            }

            // ── Error message ─────────────────────────────────────────────
            errorMessage?.let {
                Text(text = it, color = Color.Red, style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(8.dp))
            }

            // ── State-dependent header ────────────────────────────────────
            when (val s = state) {
                is HomeState.Idle -> {
                    Text(
                        "Select an EFS NV item configuration file to import.",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { filePicker.launch(arrayOf("text/plain")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select .txt file")
                    }
                }

                is HomeState.FileSelected -> {
                    Text("File: ${s.name}", style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onBackground)
                    Text(
                        "${s.commands.size} commands parsed",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { filePicker.launch(arrayOf("text/plain")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Change file")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (!executionManager.isReady) {
                                onErrorChange("Backend not ready")
                                return@Button
                            }
                            onStateChange(HomeState.Importing(s.commands, 0, emptyList()))
                            scope.launch {
                                val results = mutableListOf<Pair<ImportCommand, Int>>()
                                BulkImporter.import(s.commands, executionManager).collect { event ->
                                    when (event) {
                                        is ImportEvent.Progress -> {
                                            results.add(event.command to event.exitCode)
                                            onStateChange(HomeState.Importing(s.commands, event.done, results.toList()))
                                        }
                                        is ImportEvent.Done -> {
                                            onStateChange(HomeState.Done(event.ok, event.fail, results.toList()))
                                        }
                                        is ImportEvent.Error -> {
                                            onErrorChange(event.message)
                                            onStateChange(HomeState.Done(
                                                results.count { it.second == 0 },
                                                results.count { it.second != 0 },
                                                results.toList()
                                            ))
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start import")
                    }
                }

                is HomeState.Importing -> {
                    val progress = if (s.commands.isEmpty()) 0f else s.done.toFloat() / s.commands.size
                    Text(
                        "Importing… ${s.done} / ${s.commands.size}",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                }

                is HomeState.Done -> {
                    Text(
                        "Done — ${s.ok} OK, ${s.fail} FAIL",
                        style = MiuixTheme.textStyles.title3,
                        color = if (s.fail == 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                    Spacer(Modifier.height(8.dp))
                    if (s.fail == 0) {
                        Button(
                            onClick = {
                                scope.launch {
                                    executionManager.execMtb(arrayOf("11", "0"))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reboot Modem")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { onStateChange(HomeState.Idle); onErrorChange(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start over")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Result log ────────────────────────────────────────────────
            val results: List<Pair<ImportCommand, Int>> = when (val s = state) {
                is HomeState.Importing -> s.results
                is HomeState.Done      -> s.results
                else                   -> emptyList()
            }
            ResultList(
                results = results,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())
            )
        }
    }
}

@Composable
private fun BackendBanner(onGrantClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Root or Shizuku access required. Grant permission to use this app.",
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = onGrantClick) { Text("Grant") }
    }
}

@Composable
private fun ResultList(
    results: List<Pair<ImportCommand, Int>>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val listState = rememberLazyListState()

    LaunchedEffect(results.size) {
        if (results.isNotEmpty()) {
            listState.animateScrollToItem(results.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = contentPadding
    ) {
        items(results) { (cmd, exitCode) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // args: [cmd="4", op, sim_slot, /path, value...]
                // op: 5 = write, 6 = delete
                val isWrite  = cmd.args.getOrNull(1) == "5"
                val isDelete = cmd.args.getOrNull(1) == "6"
                val opLabel = when {
                    isWrite  -> "W"
                    isDelete -> "D"
                    else     -> "?"
                }
                val opColor = when {
                    isWrite  -> Color(0xFF4FC3F7)
                    isDelete -> Color(0xFFFF8A65)
                    else     -> MiuixTheme.colorScheme.onSurfaceVariantActions
                }
                val simSlot = cmd.args.getOrNull(2) ?: "?"
                val simColor = when (simSlot) {
                    "0"  -> Color(0xFFCE93D8)
                    "1"  -> Color(0xFF80CBC4)
                    else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                }
                val nvName = cmd.args.firstOrNull { it.startsWith("/") }
                    ?.substringAfterLast('/') ?: cmd.rawLine
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text("[",           style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    Text(opLabel,       style = MiuixTheme.textStyles.body2, color = opColor)
                    Text("|",           style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    Text("SIM$simSlot", style = MiuixTheme.textStyles.body2, color = simColor)
                    Text("] $nvName",   style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
                }
                Text(
                    text  = if (exitCode == 0) "OK" else "FAIL($exitCode)",
                    color = if (exitCode == 0) Color(0xFF4CAF50) else Color(0xFFF44336),
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
    }
}
