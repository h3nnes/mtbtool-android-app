package dev.henrik.mtbtool.ui

import androidx.compose.runtime.compositionLocalOf
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/** CompositionLocal that carries the root [SnackbarHostState] for modem-reboot notifications. */
val LocalRebootSnackbar = compositionLocalOf<SnackbarHostState> {
    error("LocalRebootSnackbar not provided")
}

const val REBOOT_SNACKBAR_MSG =
    "Settings applied and modem is rebooting. Please wait for roughly 10 seconds..."
