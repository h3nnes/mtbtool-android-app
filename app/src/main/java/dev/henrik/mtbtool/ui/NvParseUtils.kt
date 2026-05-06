// app/src/main/java/dev/henrik/mtbtool/ui/NvParseUtils.kt
package dev.henrik.mtbtool.ui

import androidx.compose.ui.graphics.Color

/**
 * Parses mtb read output: extracts 2-char hex tokens from lines containing
 * "xiaomi_nvefs_test_efs_read:" (last field per line), groups into rows of 16 tokens.
 */
fun parseHexOutput(raw: String): List<List<String>> {
    val tokens = raw.lines()
        .filter { it.contains("xiaomi_nvefs_test_efs_read:") }
        .mapNotNull { line ->
            val last = line.trim().split(Regex("\\s+")).lastOrNull()
            if (last != null && last.matches(Regex("[0-9A-Fa-f]{2}"))) last.uppercase() else null
        }
    return tokens.chunked(16)
}

/**
 * Deterministic per-value color for hex byte display.
 * 00 → dim; FF → near-white; others → one of 7 vivid hues by value bucket.
 */
fun hexByteColor(token: String): Color {
    if (token == "00") return Color(0xFF4A4A4A)
    if (token == "FF") return Color(0xFFE0E0E0)
    val v = token.toIntOrNull(16) ?: return Color(0xFF4CAF50)
    return when (v % 7) {
        0 -> Color(0xFF4FC3F7) // light blue
        1 -> Color(0xFF81C784) // green
        2 -> Color(0xFFFFB74D) // amber
        3 -> Color(0xFFBA68C8) // purple
        4 -> Color(0xFF4DB6AC) // teal
        5 -> Color(0xFFF06292) // pink
        else -> Color(0xFFFFD54F) // yellow
    }
}
