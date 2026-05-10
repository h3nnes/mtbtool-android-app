package dev.henrik.mtbtool.ui.effect

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.floor

/**
 * OS3-style animated gradient background, ported from the Miuix demo example app.
 *
 * Requires API 33+ (Android 13 / Tiramisu) for [android.graphics.RuntimeShader].
 * The app's minSdk is 33, so no runtime guard is needed.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun BgEffectBackground(
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val isInDarkTheme = isSystemInDarkTheme()
    val animTime = rememberFrameTimeSeconds(playing = true)
    val painter = remember { BgEffectPainter() }

    val preset = remember(isInDarkTheme) {
        BgEffectConfig.get(isInDarkTheme)
    }

    val colorStage = remember { Animatable(0f) }

    LaunchedEffect(preset) {
        val animatesColors = preset.colors1 !== preset.colors2 || preset.colors2 !== preset.colors3
        if (!animatesColors) return@LaunchedEffect

        var targetStage = floor(colorStage.value) + 1f
        while (isActive) {
            delay((preset.colorInterpPeriod * 500).toLong())
            colorStage.animateTo(
                targetValue = targetStage,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
            )
            targetStage += 1f
        }
    }

    Box(modifier = modifier) {
        key(isInDarkTheme) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .then(bgModifier),
            ) {
                val drawHeight = if (isFullSize) size.height else size.height * 0.78f

                painter.updateResolution(size.width, size.height)
                painter.updateBoundIfNeeded(drawHeight, size.height, size.width)
                painter.updatePresetIfNeeded(isInDarkTheme)
                painter.updateColors(preset, colorStage.value)
                painter.updateAnimTime(animTime())

                drawRect(painter.brush)
            }
        }
        content()
    }
}
