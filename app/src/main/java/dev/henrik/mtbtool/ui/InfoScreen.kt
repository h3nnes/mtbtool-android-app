package dev.henrik.mtbtool.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import dev.henrik.mtbtool.BuildConfig
import dev.henrik.mtbtool.ui.effect.BgEffectBackground
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CardShape = RoundedCornerShape(28.dp)

@Composable
fun InfoScreen(contentPadding: PaddingValues = PaddingValues()) {
    val isLightTheme = !isSystemInDarkTheme()

    // Only use the animated shader on API 33+ (always true for this app, minSdk=33).
    // Guard retained for clarity.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val backdrop = rememberLayerBackdrop()

        Box(modifier = Modifier.fillMaxSize()) {
            // ── Animated OS3 background ───────────────────────────────────────
            BgEffectBackground(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
                isFullSize = true,
            ) {}

            // ── Frosted glass card, centred ───────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = contentPadding.calculateTopPadding() + 48.dp,
                        start = 24.dp,
                        end = 24.dp,
                        bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    )
                    .align(Alignment.TopCenter),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // App name + version — main frosted card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { CardShape },
                            effects = { blur(80f) },
                            highlight = {
                                Highlight.Default.copy(
                                    alpha = if (isLightTheme) 0.6f else 0.25f,
                                )
                            },
                            shadow = {
                                Shadow.Default.copy(
                                    color = Color.Black.copy(
                                        alpha = if (isLightTheme) 0.08f else 0.3f,
                                    ),
                                )
                            },
                            onDrawSurface = {
                                drawRect(
                                    if (isLightTheme) Color.White.copy(alpha = 0.55f)
                                    else Color.Black.copy(alpha = 0.35f),
                                )
                            },
                        )
                        .padding(horizontal = 28.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Logo blend: background texture shows through the text shape
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawPlainBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(0.dp) },
                                effects = { blur(150f) },
                                onDrawSurface = {
                                    // slight white tint so text isn't fully transparent
                                    drawRect(Color.White.copy(alpha = 0.25f))
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "MTB Tool",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            color = if (isLightTheme) Color(0xFF222222) else Color(0xFFDDDDDD),
                            modifier = Modifier.graphicsLayer {
                                blendMode = BlendMode.DstIn
                            },
                        )
                    }
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MiuixTheme.textStyles.title4,
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }

                // Description card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { CardShape },
                            effects = { blur(60f) },
                            highlight = {
                                Highlight.Default.copy(
                                    alpha = if (isLightTheme) 0.5f else 0.2f,
                                )
                            },
                            shadow = {
                                Shadow.Default.copy(
                                    color = Color.Black.copy(
                                        alpha = if (isLightTheme) 0.06f else 0.25f,
                                    ),
                                )
                            },
                            onDrawSurface = {
                                drawRect(
                                    if (isLightTheme) Color.White.copy(alpha = 0.45f)
                                    else Color.Black.copy(alpha = 0.28f),
                                )
                            },
                        )
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "A modem configuration tool for Qualcomm-based Xiaomi devices. Bulk-import EFS NV item files, read raw EFS data, toggle modem features, and configure band locking for LTE and 5G NR.",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Backend: root access (preferred) or Shizuku. The app will use root if granted, and fall back to Shizuku automatically. At least one must be available.",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                    Text(
                        text = "Requires a Qualcomm-based Xiaomi device with /vendor/bin/mtb present.",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                    Text(
                        text = "Package: dev.henrik.mtbtool",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }
    } else {
        // Fallback for hypothetical pre-API-33 (shouldn't happen with minSdk=33)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding(),
                    start = 24.dp,
                    end = 24.dp,
                    bottom = contentPadding.calculateBottomPadding(),
                )
                .padding(top = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "MTB Tool",
                style = MiuixTheme.textStyles.title1,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
