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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.henrik.mtbtool.ShizukuManager
import dev.henrik.mtbtool.ui.component.FloatingBottomBar
import dev.henrik.mtbtool.ui.component.FloatingBottomBarItem
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun MainScreen(
    shizukuManager: ShizukuManager,
    dataStore: DataStore<Preferences>,
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 5 })

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

    LaunchedEffect(shizukuManager.isReady) {
        if (shizukuManager.isReady && !binaryCheckDone) {
            // Set before the check so that if the check fails, we don't re-run it on
            // every isReady transition. The dialog handles the error case.
            binaryCheckDone = true
            try {
                val raw = shizukuManager.execMtbWithOutput(arrayOf("-h"))
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
                    tabsCount = 5
                ) {
                    FloatingBottomBarItem(onClick = { selectedIndex = 0 }) {
                        Icon(
                            imageVector = MiuixIcons.Import,
                            contentDescription = "Import",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                        Text("Import", style = MiuixTheme.textStyles.body2)
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 1 }) {
                        Icon(
                            imageVector = MiuixIcons.Search,
                            contentDescription = "Read",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                        Text("Read", style = MiuixTheme.textStyles.body2)
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 2 }) {
                        Icon(
                            imageVector = MiuixIcons.Tune,
                            contentDescription = "Features",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                        Text("Features", style = MiuixTheme.textStyles.body2)
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 3 }) {
                        Icon(
                            imageVector = MiuixIcons.Lock,
                            contentDescription = "Bandlock",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                        Text("Bandlock", style = MiuixTheme.textStyles.body2)
                    }
                    FloatingBottomBarItem(onClick = { selectedIndex = 4 }) {
                        Icon(
                            imageVector = MiuixIcons.Info,
                            contentDescription = "Info",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                        Text("Info", style = MiuixTheme.textStyles.body2)
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
                    shizukuManager = shizukuManager,
                    state = homeState,
                    onStateChange = { homeState = it },
                    errorMessage = homeError,
                    onErrorChange = { homeError = it },
                    contentPadding = innerPadding
                )
                1 -> ReadScreen(
                    shizukuManager = shizukuManager,
                    contentPadding = innerPadding
                )
                2 -> FeaturesScreen(
                    shizukuManager = shizukuManager,
                    contentPadding = innerPadding
                )
                3 -> BandlockScreen(
                    shizukuManager = shizukuManager,
                    dataStore = dataStore,
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
