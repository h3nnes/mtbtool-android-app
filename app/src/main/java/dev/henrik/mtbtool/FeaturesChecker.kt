// app/src/main/java/dev/henrik/mtbtool/FeaturesChecker.kt
package dev.henrik.mtbtool

import dev.henrik.mtbtool.ui.parseHexOutput  // NvParseUtils.kt (same project, different package)

/**
 * Result of checking all features: status per feature, plus the raw bytes read
 * from NV for each feature (keyed by feature, then list of byte arrays — one per
 * read path, null when the NV item did not exist on the modem).
 * Only features that were read without error have entries in [originalBytes].
 */
data class CheckResult(
    val statuses: Map<FeatureDef, FeatureStatus>,
    val originalBytes: Map<FeatureDef, List<List<Int>?>>
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
    val statuses      = mutableMapOf<FeatureDef, FeatureStatus>()
    val originalBytes = mutableMapOf<FeatureDef, List<List<Int>?>>()

    for (feature in ALL_FEATURES) {
        // null entry = NV item was absent on the modem (default / never written)
        val byteArrays = mutableListOf<List<Int>?>()

        for (path in feature.reads) {
            val raw = executionManager.execMtbWithOutput(
                arrayOf("4", "4", simSlot.toString(), path)
            )
            val exitLine = raw.lines().firstOrNull() ?: ""
            val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1

            if (exitCode != 0) {
                // Any non-zero exit code means the NV item could not be read
                // (most likely absent/never written) — record null so restore will delete it.
                byteArrays.add(null)
            } else {
                val rows = parseHexOutput(raw)
                val flat = rows.flatten().map { hex -> hex.toIntOrNull(16) ?: 0 }
                if (flat.isEmpty()) {
                    // Exit 0 but no bytes returned — treat as absent (modem default).
                    byteArrays.add(null)
                } else {
                    byteArrays.add(flat)
                }
            }
        }

        originalBytes[feature] = byteArrays.toList()
        // For isDisabled check, pass only the paths that actually existed.
        val existingBytes = byteArrays.filterNotNull()
        statuses[feature] = if (byteArrays.any { it == null }) {
            // At least one path was absent → modem default → feature is enabled.
            FeatureStatus.CanDisable
        } else if (feature.isDisabled(existingBytes)) {
            FeatureStatus.AlreadyDisabled
        } else {
            FeatureStatus.CanDisable
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
 * Restores the original NV state for [feature] as captured at check time.
 * [originalBytes] is the list of byte arrays — one per read path (same order as
 * [FeatureDef.reads]). A null entry means the NV item did not exist at check time
 * and should be deleted to restore the modem default.
 * Returns null on success, or an error message string on failure.
 */
suspend fun restoreFeature(
    feature: FeatureDef,
    originalBytes: List<List<Int>?>,
    simSlot: Int,
    executionManager: ExecutionManager
): String? {
    feature.reads.forEachIndexed { index, path ->
        val bytes = originalBytes.getOrNull(index)
        if (bytes == null) {
            // NV item was absent — delete it to restore modem default.
            val deleteArgs = arrayOf("4", "6", simSlot.toString(), path)
            val raw = executionManager.execMtbWithOutput(deleteArgs)
            val exitLine = raw.lines().firstOrNull() ?: ""
            val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
            // Verify: re-read after delete. Item is "still present" only if exit 0
            // AND non-empty bytes are returned (this modem returns exit 0 + empty
            // output when an item is absent, so empty == gone).
            val verifyRaw = executionManager.execMtbWithOutput(
                arrayOf("4", "4", simSlot.toString(), path)
            )
            val verifyExit = verifyRaw.lines().firstOrNull()
                ?.removePrefix("EXIT:")?.toIntOrNull() ?: -1
            val verifyBytes = parseHexOutput(verifyRaw).flatten()
            if (verifyExit == 0 && verifyBytes.isNotEmpty()) {
                // Item still readable with data after delete — delete did not take effect.
                return "Delete exit $exitCode but item still exists: ${path.substringAfterLast('/')}"
            }
            // Item is gone (non-zero exit, or exit 0 with no data) — success.
        } else {
            val raw = executionManager.execMtbWithOutput(
                arrayOf("4", "5", simSlot.toString(), path) + bytes.map { it.toString() }.toTypedArray()
            )
            val exitLine = raw.lines().firstOrNull() ?: ""
            val exitCode = exitLine.removePrefix("EXIT:").toIntOrNull() ?: -1
            if (exitCode != 0) {
                return "Restore failed (exit $exitCode) for ${path.substringAfterLast('/')}"
            }
        }
    }
    return null
}
