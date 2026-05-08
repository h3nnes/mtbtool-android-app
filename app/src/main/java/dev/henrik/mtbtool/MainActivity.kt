package dev.henrik.mtbtool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.datastore.preferences.preferencesDataStore
import dev.henrik.mtbtool.ui.MainScreen
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

private val ComponentActivity.bandDataStore by preferencesDataStore(name = "band_prefs")

class MainActivity : ComponentActivity() {

    private val executionManager by lazy { ExecutionManager(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        executionManager.start()
        setContent {
            MiuixTheme(colors = darkColorScheme()) {
                MainScreen(
                    executionManager = executionManager,
                    dataStore = bandDataStore
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executionManager.stop()
    }
}
