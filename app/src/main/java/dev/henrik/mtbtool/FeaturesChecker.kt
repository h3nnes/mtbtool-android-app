// app/src/main/java/dev/henrik/mtbtool/FeaturesChecker.kt
package dev.henrik.mtbtool

import dev.henrik.mtbtool.ui.parseHexOutput  // NvParseUtils.kt (same project, different package)

/**
 * Result of checking all features: status per feature, plus the raw bytes read
 * from NV for each feature (keyed by feature, then list of byte arrays — one per
 * read path). Only features that were read without error have entries in [originalBytes].
 */
data class CheckResult(
    val statuses: Map<FeatureDef, FeatureStatus>,
    val originalBytes: Map<FeatureDef, List<List<Int>>>
)

/**
 * Reads all NV items for all features in ALL_FEATURES for the given SIM slot.
 * Returns a [CheckResult] containing status per feature and the raw bytes as read.
 * Failures on individual reads are surfaced as FeatureStatus.ReadError.
 */
suspend fun checkAll(
    simSlot: Int,
    executionManager: ExecutionManager
): CheckResult {
    val statuses     = mutableMapOf<FeatureDef, FeatureStatus>()
    val originalBytes = mutableMapOf<FeatureDef, List<List<Int>>>()

    for (feature in ALL_FEATURES) {
        var readError: FeatureStatus.ReadError? = null
        val byteArrays = mutableListOf<List<Int>>()

        for (path in feature.reads) {
            val raw = executionManager.execMtbWithOutput(
                arrayOf("4", "4", simSlot.toString(), path)
            )
            val exitLine = raw.lines().firstOrNull() ?: ""
            val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
            val output = raw.lines().drop(1).joinToString("\n")
            if (exitCode != 0) {
                readError = FeatureStatus.ReadError("Exit $exitCode for ${path.substringAfterLast('/')}")
                break
            }
            val rows = parseHexOutput(output)
            byteArrays.add(rows.flatten().map { hex -> hex.toIntOrNull(16) ?: 0 })
        }

        if (readError != null) {
            statuses[feature] = readError
        } else {
            originalBytes[feature] = byteArrays.toList()
            statuses[feature] = if (feature.isDisabled(byteArrays)) {
                FeatureStatus.AlreadyDisabled
            } else {
                FeatureStatus.CanDisable
            }
        }
    }

    return CheckResult(statuses = statuses, originalBytes = originalBytes)
}

/**
 * Writes all NvWrite entries for [feature] using [simSlot].
 * Returns null on success, or an error message string on failure.
 */
suspend fun disableFeature(
    feature: FeatureDef,
    simSlot: Int,
    executionManager: ExecutionManager
): String? {
    for (write in feature.writes) {
        val args = arrayOf("4", "5", simSlot.toString(), write.path) + write.bytes.split(" ").toTypedArray()
        val raw = executionManager.execMtbWithOutput(args)
        val exitLine = raw.lines().firstOrNull() ?: ""
        val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
        if (exitCode != 0) {
            return "Write failed (exit $exitCode) for ${write.path.substringAfterLast('/')}"
        }
    }
    return null
}

/**
 * Restores the original NV bytes for [feature] as captured at check time.
 * [originalBytes] is the list of byte arrays — one per read path (same order as [FeatureDef.reads]).
 * Each path from [FeatureDef.reads] is written back with its corresponding original bytes.
 * Returns null on success, or an error message string on failure.
 */
suspend fun restoreFeature(
    feature: FeatureDef,
    originalBytes: List<List<Int>>,
    simSlot: Int,
    executionManager: ExecutionManager
): String? {
    feature.reads.forEachIndexed { index, path ->
        val bytes = originalBytes.getOrNull(index) ?: return "No original data for ${path.substringAfterLast('/')}"
        val byteArgs = bytes.map { it.toString() }.toTypedArray()
        val args = arrayOf("4", "5", simSlot.toString(), path) + byteArgs
        val raw = executionManager.execMtbWithOutput(args)
        val exitLine = raw.lines().firstOrNull() ?: ""
        val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
        if (exitCode != 0) {
            return "Restore failed (exit $exitCode) for ${path.substringAfterLast('/')}"
        }
    }
    return null
}
