package dev.henrik.mtbtool

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

data class SavedBands(
    val lte: Set<Int>,
    val nrNsa: Set<Int>,
    val nr: Set<Int>
)

object BandlockManager {

    // ── Spec band lists ────────────────────────────────────────────────────────

    /** All 3GPP-defined LTE bands that this app exposes (B1–B64 range + B66, B71). */
    val ALL_LTE_BANDS: List<Int> = listOf(
        1, 2, 3, 4, 5, 7, 8, 12, 13, 14, 17, 18, 19, 20, 21,
        25, 26, 28, 29, 30, 32, 34, 38, 39, 40, 41, 42, 43, 46, 48,
        66, 71
    )

    /** All 3GPP-defined NR bands exposed by this app. Used for both NR SA and NR NSA — same band number space, different EFS paths. */
    val ALL_NR_BANDS: List<Int> = listOf(
        1, 2, 3, 5, 7, 8, 12, 14, 18, 20, 25, 26, 28, 29, 30,
        34, 38, 39, 40, 41, 46, 48, 50, 51, 53, 65, 66, 70, 71, 74,
        75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 86, 89, 90, 91, 92,
        93, 94, 95, 96, 97, 100, 101, 102, 104,
        257, 258, 260, 261
    )

    // ── EFS NV paths ───────────────────────────────────────────────────────────

    const val PATH_LTE_PRIMARY   = "/nv/item_files/modem/mmode/lte_bandpref"
    const val PATH_LTE_EXTENSION = "/nv/item_files/modem/mmode/lte_bandpref_extn_65_256"
    const val PATH_NR            = "/nv/item_files/modem/mmode/nr_band_pref"
    const val PATH_NR_NSA        = "/nv/item_files/modem/mmode/nr_nsa_band_pref"

    // ── DIAG supported-bands commands ─────────────────────────────────────────

    val DIAG_OPEN_ARGS: Array<String> = arrayOf(
        "5", "0", "0", "0", "1000", "75", "19", "2", "0", "0", "0", "0",
        "0", "0", "0", "0", "0",
        "47", "112", "111", "108", "105", "99", "121", "109", "97", "110",
        "47", "112", "101", "114", "115", "105", "115", "116", "101", "100",
        "95", "105", "116", "101", "109", "115", "47", "108", "105", "109",
        "105", "116", "101", "100", "95", "98", "97", "110", "100", "115", "0"
    )

    val DIAG_READ_ARGS: Array<String> = arrayOf(
        "5", "0", "0", "0", "1000", "75", "19", "4",
        "0", "0", "0", "0", "0", "0", "1", "0", "0", "0", "0", "0", "0"
    )

    // Byte offsets within the 279-byte DIAG response
    private const val DIAG_OFFSET_LTE    = 36
    private const val DIAG_OFFSET_NR_SA  = 108
    private const val DIAG_OFFSET_NR_NSA = 172

    // ── Bitmask parsing ────────────────────────────────────────────────────────

    fun parseLtePrimary(bytes: List<Int>): Set<Int> {
        val result = mutableSetOf<Int>()
        for (band in 1..64) {
            val bitIndex = band - 1
            val byteIndex = bitIndex / 8
            val bitInByte = bitIndex % 8
            val byte = bytes.getOrElse(byteIndex) { 0 }
            if ((byte shr bitInByte) and 1 == 1) result.add(band)
        }
        return result
    }

    fun parseLteExtension(bytes: List<Int>): Set<Int> {
        val result = mutableSetOf<Int>()
        for (band in listOf(66, 71)) {
            val bitIndex = band - 65
            val byteIndex = bitIndex / 8
            val bitInByte = bitIndex % 8
            val byte = bytes.getOrElse(byteIndex) { 0 }
            if ((byte shr bitInByte) and 1 == 1) result.add(band)
        }
        return result
    }

    fun parseNr(bytes: List<Int>): Set<Int>    = parseNrBitmask(bytes)
    fun parseNrNsa(bytes: List<Int>): Set<Int> = parseNrBitmask(bytes)

    private fun parseNrBitmask(bytes: List<Int>): Set<Int> {
        val result = mutableSetOf<Int>()
        for (band in ALL_NR_BANDS) {
            val bitIndex  = band - 1
            val byteIndex = bitIndex / 8
            val bitInByte = bitIndex % 8
            val byte      = bytes.getOrElse(byteIndex) { 0 }
            if ((byte shr bitInByte) and 1 == 1) result.add(band)
        }
        return result
    }

    // ── Bitmask construction ───────────────────────────────────────────────────

    fun buildLtePrimary(enabledBands: Set<Int>): IntArray {
        val bytes = IntArray(8)
        for (band in enabledBands) {
            if (band < 1 || band > 64) continue
            val bitIndex = band - 1
            val byteIndex = bitIndex / 8
            val bitInByte = bitIndex % 8
            bytes[byteIndex] = bytes[byteIndex] or (1 shl bitInByte)
        }
        return bytes
    }

    fun buildLteExtension(enabledBands: Set<Int>): IntArray {
        val bytes = IntArray(24)
        for (band in listOf(66, 71)) {
            if (band !in enabledBands) continue
            val bitIndex = band - 65
            val byteIndex = bitIndex / 8
            val bitInByte = bitIndex % 8
            bytes[byteIndex] = bytes[byteIndex] or (1 shl bitInByte)
        }
        return bytes
    }

    fun buildNr(enabledBands: Set<Int>): IntArray    = buildNrBitmask(enabledBands)
    fun buildNrNsa(enabledBands: Set<Int>): IntArray = buildNrBitmask(enabledBands)

    private fun buildNrBitmask(enabledBands: Set<Int>): IntArray {
        val bytes = IntArray(64)
        for (band in enabledBands) {
            val bitIndex  = band - 1
            val byteIndex = bitIndex / 8
            val bitInByte = bitIndex % 8
            if (byteIndex >= bytes.size) continue
            bytes[byteIndex] = bytes[byteIndex] or (1 shl bitInByte)
        }
        return bytes
    }

    // ── DataStore helpers ──────────────────────────────────────────────────────

    /**
     * Parses EFS read output into a list of byte values (decimal integers).
     * Throws [IllegalStateException] if the output is non-empty but no tagged lines
     * are found, which indicates an unexpected output format rather than an empty NV item.
     */
    fun parseBytes(raw: String): List<Int> {
        val tagged = raw.lines()
            .filter { it.contains("xiaomi_nvefs_test_efs_read:") }
            .mapNotNull { line ->
                val last = line.trim().split(Regex("\\s+")).lastOrNull()
                if (last != null && last.matches(Regex("[0-9A-Fa-f]{2}")))
                    last.toInt(16)
                else null
            }
        if (tagged.isEmpty() && raw.isNotBlank()) {
            throw IllegalStateException("Unexpected EFS output format — could not parse bytes.\nRaw: $raw")
        }
        return tagged
    }

    /**
     * Parses the raw stdout of the DIAG read command into a list of byte values.
     * Looks for a line starting with "rsp data:" and parses "0xNN" tokens.
     * Returns an empty list if no such line is found.
     */
    fun parseDiagResponse(raw: String): List<Int> {
        val line = raw.lines().firstOrNull { it.trimStart().startsWith("rsp data:") }
            ?: return emptyList()
        return line.substringAfter("rsp data:").trim()
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                if (token.startsWith("0x") || token.startsWith("0X"))
                    token.substring(2).toIntOrNull(16)
                else null
            }
    }

    /**
     * Parses a flat bitmask byte list into a set of band numbers.
     * Bit (band-1) in the bitmask → that band is supported.
     * Only returns bands that are present in [knownBands].
     */
    fun parseBitmaskBands(bytes: List<Int>, offset: Int, length: Int, knownBands: List<Int>): Set<Int> {
        val result = mutableSetOf<Int>()
        for (band in knownBands) {
            val bitIndex  = band - 1
            val byteIndex = offset + bitIndex / 8
            val bitInByte = bitIndex % 8
            if (byteIndex >= offset + length) continue
            val byte = bytes.getOrElse(byteIndex) { 0 }
            if ((byte shr bitInByte) and 1 == 1) result.add(band)
        }
        return result
    }

    data class DetectedBands(
        val lte: Set<Int>,
        val nrSa: Set<Int>,
        val nrNsa: Set<Int>
    )

    /**
     * Interprets a parsed DIAG response byte list as hardware-supported bands.
     * LTE uses 9 bytes at [DIAG_OFFSET_LTE], NR SA 10 bytes at [DIAG_OFFSET_NR_SA],
     * NR NSA 10 bytes at [DIAG_OFFSET_NR_NSA].
     */
    fun parseSupportedBands(diagBytes: List<Int>): DetectedBands {
        val lte   = parseBitmaskBands(diagBytes, DIAG_OFFSET_LTE,    9,  ALL_LTE_BANDS)
        val nrSa  = parseBitmaskBands(diagBytes, DIAG_OFFSET_NR_SA,  10, ALL_NR_BANDS)
        val nrNsa = parseBitmaskBands(diagBytes, DIAG_OFFSET_NR_NSA, 10, ALL_NR_BANDS)
        return DetectedBands(lte = lte, nrSa = nrSa, nrNsa = nrNsa)
    }

    suspend fun loadSavedBands(dataStore: DataStore<Preferences>): SavedBands {
        val prefs = dataStore.data.first()
        val lte = prefs[BandPreferences.LTE_BANDS]
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
            ?: emptySet()
        val nrNsa = prefs[BandPreferences.NR_NSA_BANDS]
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
            ?: emptySet()
        val nr = prefs[BandPreferences.NR_BANDS]
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
            ?: emptySet()
        return SavedBands(lte = lte, nrNsa = nrNsa, nr = nr)
    }

    suspend fun saveBands(
        dataStore: DataStore<Preferences>,
        lteBands: Set<Int>,
        nrNsaBands: Set<Int>,
        nrBands: Set<Int>
    ) {
        dataStore.edit { prefs ->
            prefs[BandPreferences.LTE_BANDS]    = lteBands.sorted().joinToString(",")
            prefs[BandPreferences.NR_NSA_BANDS] = nrNsaBands.sorted().joinToString(",")
            prefs[BandPreferences.NR_BANDS]     = nrBands.sorted().joinToString(",")
        }
    }

    fun isConfigured(lteBands: Set<Int>, nrNsaBands: Set<Int>, nrBands: Set<Int>): Boolean =
        lteBands.isNotEmpty() || nrNsaBands.isNotEmpty() || nrBands.isNotEmpty()
}
