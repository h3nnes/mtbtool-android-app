package dev.henrik.mtbtool

data class LteCellData(
    val label: String,   // "PCC", "SCC1", "SCC2", "SCC3"
    val earfcn: Int,
    val pci: Int,
    val rsrp: Float,     // dBm
    val rsrq: Float,     // dB
    val rssi: Float,     // dBm
    val snr: Float       // dB
)

data class NrCellData(
    val label: String,   // "PCC", "SCC1", "SCC2"
    val rsrp: Float,     // dBm
    val rsrq: Float      // dB
)

/**
 * Tracks whether a per-label cell value has been unchanged for [threshold] consecutive
 * polls. Call [observe] with each new value (or null if absent); it returns the value
 * if it should be shown, or null if it should be suppressed (stale or absent).
 *
 * Rules:
 * - null input → resets the counter, returns null (cell is absent)
 * - value changed → resets counter to 1, returns the value immediately
 * - value unchanged, count < threshold → increments count, returns the value
 * - value unchanged, count >= threshold → returns null (stale, suppress)
 */
class StalenessTracker<T>(private val threshold: Int = 3) {
    private val lastValue = mutableMapOf<String, T?>()
    private val sameCount = mutableMapOf<String, Int>()

    fun observe(label: String, value: T?): T? {
        if (value == null) {
            lastValue[label] = null
            sameCount[label] = 0
            return null
        }
        val prev  = lastValue[label]
        val count = if (value == prev) (sameCount[label] ?: 0) + 1 else 1
        lastValue[label] = value
        sameCount[label] = count
        return if (count >= threshold) null else value
    }

    fun reset() {
        lastValue.clear()
        sameCount.clear()
    }
}

object CellMonitor {

    // ── mtb command args ───────────────────────────────────────────────────────

    fun lteArgs(opt: Int, slot: Int = 0) = arrayOf("9", opt.toString(), slot.toString())
    fun nrArgs(opt: Int, slot: Int = 0)  = arrayOf("9", opt.toString(), slot.toString())
    fun txPowerArgs(slot: Int = 0)        = arrayOf("9", "31", slot.toString())

    // LTE opts: PCC=0, SCC1=1, SCC2=2, SCC3=3
    val LTE_OPTS = listOf(0 to "PCC", 1 to "SCC1", 2 to "SCC2", 3 to "SCC3")
    // NR opts:  PCC=10, SCC1=11, SCC2=12
    val NR_OPTS  = listOf(10 to "PCC", 11 to "SCC1", 12 to "SCC2")

    // ── Internal helpers ───────────────────────────────────────────────────────

    fun parseAsdivLine(raw: String): Map<String, String> {
        val line = raw.lines().firstOrNull { it.trimStart().startsWith("ASDIV DATA:") }
            ?: return emptyMap()
        return line.substringAfter("ASDIV DATA:").trim()
            .split(", ")
            .mapNotNull { pair ->
                val idx = pair.indexOf(": ")
                if (idx < 0) null else pair.substring(0, idx).trim() to pair.substring(idx + 2).trim()
            }
            .toMap()
    }

    private fun Float.isInvalid() = this >= 65534.5f

    fun parseLteCell(raw: String, label: String): LteCellData? {
        val map = parseAsdivLine(raw)
        if (map.isEmpty()) return null
        val earfcn = map["earfcn"]?.toIntOrNull()     ?: return null
        val pci    = map["pci"]?.toIntOrNull()        ?: return null
        val rsrp   = map["rsrp_rx0"]?.toFloatOrNull() ?: return null
        val rsrq   = map["rsrq_rx0"]?.toFloatOrNull() ?: return null
        val rssi   = map["rssi_rx0"]?.toFloatOrNull() ?: return null
        val snr    = map["snr_rx0"]?.toFloatOrNull()  ?: return null
        if (rsrp.isInvalid() || rsrq.isInvalid() || rssi.isInvalid() || snr.isInvalid()) return null
        return LteCellData(label, earfcn, pci, rsrp, rsrq, rssi, snr)
    }

    fun parseNrCell(raw: String, label: String): NrCellData? {
        val map = parseAsdivLine(raw)
        if (map.isEmpty()) return null
        val rsrp = map["rsrp_rx0"]?.toFloatOrNull() ?: return null
        val rsrq = map["rsrq"]?.toFloatOrNull()     ?: return null
        if (rsrp.isInvalid() || rsrq.isInvalid()) return null
        return NrCellData(label, rsrp, rsrq)
    }

    fun parseTxPower(raw: String): Int? {
        val line = raw.lines().firstOrNull { it.trimStart().startsWith("TX INFO:") }
            ?: return null
        val value = line.substringAfter("TX INFO:").trim()
            .split(", ")
            .mapNotNull { pair ->
                val idx = pair.indexOf(" = ")
                if (idx < 0) null else pair.substring(0, idx).trim() to pair.substring(idx + 3).trim()
            }
            .toMap()["tx_power"]?.toIntOrNull()
        // 65535 is a sentinel meaning "no signal" (e.g. flight mode) — suppress it
        return if (value == 65535) null else value
    }
}
