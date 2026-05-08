package dev.henrik.mtbtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.henrik.mtbtool.CellMonitor
import dev.henrik.mtbtool.LteCellData
import dev.henrik.mtbtool.NrCellData
import dev.henrik.mtbtool.ExecutionManager
import dev.henrik.mtbtool.StalenessTracker
import dev.henrik.mtbtool.ui.component.FloatingBottomBar
import dev.henrik.mtbtool.ui.component.FloatingBottomBarItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun MainScreen(
    executionManager: ExecutionManager,
    dataStore: DataStore<Preferences>,
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 6 })

    // Nav bar tap → scroll pager
    LaunchedEffect(selectedIndex) {
        if (pagerState.targetPage != selectedIndex) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }
    // Swipe → update nav bar highlight (use targetPage so mid-animation page
    // crossings don't retrigger the nav→pager effect and cancel the animation)
    LaunchedEffect(pagerState.targetPage) {
        selectedIndex = pagerState.targetPage
    }
    var homeState by remember { mutableStateOf<HomeState>(HomeState.Idle) }
    var homeError by remember { mutableStateOf<String?>(null) }
    var binaryCheckDone by remember { mutableStateOf(false) }
    var showIncompatibleDialog by remember { mutableStateOf(false) }

    // ── Cells polling state (hoisted here so it survives pager navigation) ─────
    val cellScope      = rememberCoroutineScope()
    var cellIsLogging  by remember { mutableStateOf(false) }
    var cellRefreshIdx by remember { mutableIntStateOf(1) } // default 2 s
    var cellError      by remember { mutableStateOf<String?>(null) }
    var lteCells       by remember { mutableStateOf<List<LteCellData>>(emptyList()) }
    var nrCells        by remember { mutableStateOf<List<NrCellData>>(emptyList()) }
    var txPower        by remember { mutableStateOf<Int?>(null) }
    var cellHasPolled  by remember { mutableStateOf(false) }
    var cellJob        by remember { mutableStateOf<Job?>(null) }
    val lteTracker     = remember { StalenessTracker<LteCellData>() }
    val nrTracker      = remember { StalenessTracker<NrCellData>() }

    DisposableEffect(Unit) {
        onDispose { cellJob?.cancel() }
    }

    suspend fun cellPoll() {
        try {
            val lte = withContext(Dispatchers.IO) {
                CellMonitor.LTE_OPTS.mapNotNull { (opt, label) ->
                    try {
                        val raw = executionManager.execMtbWithOutput(CellMonitor.lteArgs(opt))
                        CellMonitor.parseLteCell(raw, label)
                    } catch (_: Exception) { null }
                }
            }
            val nr = withContext(Dispatchers.IO) {
                CellMonitor.NR_OPTS.mapNotNull { (opt, label) ->
                    try {
                        val raw = executionManager.execMtbWithOutput(CellMonitor.nrArgs(opt))
                        CellMonitor.parseNrCell(raw, label)
                    } catch (_: Exception) { null }
                }
            }
            val tx = withContext(Dispatchers.IO) {
                try {
                    val raw = executionManager.execMtbWithOutput(CellMonitor.txPowerArgs)
                    CellMonitor.parseTxPower(raw)
                } catch (_: Exception) { null }
            }
            lteCells      = lte.mapNotNull { cell -> lteTracker.observe(cell.label, cell) }
            nrCells       = nr.mapNotNull  { cell -> nrTracker.observe(cell.label, cell) }
            txPower       = tx
            cellHasPolled = true
        } catch (_: Exception) { }
    }

    fun cellStartLogging() {
        if (!executionManager.isReady) { cellError = "Backend not ready"; return }
        cellError     = null
        cellIsLogging = true
        cellJob = cellScope.launch {
            while (isActive) {
                cellPoll()
                delay(CELL_REFRESH_OPTIONS[cellRefreshIdx] * 1000L)
            }
        }
    }

    fun cellStopLogging() {
        cellJob?.cancel(); cellJob = null
        cellIsLogging = false
        lteTracker.reset(); nrTracker.reset()
    }
    // ──────────────────────────────────────────────────────────────────────────

    LaunchedEffect(executionManager.isReady) {
        if (executionManager.isReady && !binaryCheckDone) {
            // Set before the check so that if the check fails, we don't re-run it on
            // every isReady transition. The dialog handles the error case.
            binaryCheckDone = true
            try {
                val raw = withContext(Dispatchers.IO) {
                    executionManager.execMtbWithOutput(arrayOf("-h"))
                }
                val exitLine = raw.lines().firstOrNull() ?: ""
                val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
                val output = raw.lines().drop(1).joinToString("\n")
                val compatible = exitCode == 0 && output.contains("goldencopy")
                if (!compatible) {
                    showIncompatibleDialog = true
                }
            } catch (e: Exception) {
                showIncompatibleDialog = true
            }
        }
    }
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                FloatingBottomBar(
                    selectedIndex = { selectedIndex },
                    onSelected = { selectedIndex = it },
                    backdrop = backdrop,
                    tabsCount = 6
                ) {
                    FloatingBottomBarItem(onClick = { selectedIndex = 0 }) {
                        Icon(
                            imageVector = MiuixIcons.Import,
                            contentDescription = "Import",
                            tint = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Import", style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp))
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 1 }) {
                        Icon(
                            imageVector = MiuixIcons.Search,
                            contentDescription = "Read",
                            tint = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Read", style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp))
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 2 }) {
                        Icon(
                            imageVector = MiuixIcons.Tune,
                            contentDescription = "Feat",
                            tint = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Feat", style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp))
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 3 }) {
                        Icon(
                            imageVector = MiuixIcons.Lock,
                            contentDescription = "Bands",
                            tint = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Bands", style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp))
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 4 }) {
                        Icon(
                            imageVector = MiuixIcons.Phone,
                            contentDescription = "Cells",
                            tint = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Cells", style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp))
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 5 }) {
                        Icon(
                            imageVector = MiuixIcons.Info,
                            contentDescription = "Info",
                            tint = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Info", style = MiuixTheme.textStyles.body2.copy(fontSize = 11.sp))
                    }
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = true,
            modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    executionManager = executionManager,
                    state = homeState,
                    onStateChange = { homeState = it },
                    errorMessage = homeError,
                    onErrorChange = { homeError = it },
                    contentPadding = innerPadding
                )
                1 -> ReadScreen(
                    executionManager = executionManager,
                    contentPadding = innerPadding
                )
                2 -> FeaturesScreen(
                    executionManager = executionManager,
                    contentPadding = innerPadding
                )
                3 -> BandlockScreen(
                    executionManager = executionManager,
                    dataStore = dataStore,
                    contentPadding = innerPadding
                )
                4 -> CellsScreen(
                        isLogging      = cellIsLogging,
                        refreshIndex   = cellRefreshIdx,
                        errorMessage   = cellError,
                        lteCells       = lteCells,
                        nrCells        = nrCells,
                        txPower        = txPower,
                        hasPolled      = cellHasPolled,
                        onToggleLogging = {
                            if (cellIsLogging) cellStopLogging() else cellStartLogging()
                        },
                        onRefreshIndexChange = { i ->
                            val was = cellIsLogging
                            if (was) cellStopLogging()
                            cellRefreshIdx = i
                            if (was) cellStartLogging()
                        },
                        contentPadding = innerPadding
                    )
                else -> InfoScreen(contentPadding = innerPadding)
            }
        }
        WindowDialog(
            show = showIncompatibleDialog,
            title = "Device not compatible",
            summary = "The required mtb binary was not found. This app only works on Qualcomm-based Xiaomi devices with the Qualcomm modem. The app will continue, but nothing will work.",
            onDismissRequest = { showIncompatibleDialog = false },
            content = {
                TextButton(
                    text = "Understood",
                    onClick = { showIncompatibleDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            },
        )
    }
}
