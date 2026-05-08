package dev.henrik.mtbtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.henrik.mtbtool.BuildConfig
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun InfoScreen(contentPadding: PaddingValues = PaddingValues()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                start = 24.dp,
                end = 24.dp,
                bottom = 0.dp
            )
            .padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "MTB Tool",
            style = MiuixTheme.textStyles.title1,
            color = MiuixTheme.colorScheme.onBackground
        )
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions
        )
        Text(
            text = "Package: dev.henrik.mtbtool",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions
        )
        Text(
            text = "A modem configuration tool for Qualcomm-based Xiaomi devices. Bulk-import EFS NV item files, read raw EFS data, toggle modem features, and configure band locking for LTE and 5G NR.",
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackground
        )
        Text(
            text = "Backend: root access (preferred) or Shizuku. The app will use root if granted, and fall back to Shizuku automatically. At least one must be available.",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions
        )
        Text(
            text = "Requires a Qualcomm-based Xiaomi device with /vendor/bin/mtb present.",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions
        )
        Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
    }
}
