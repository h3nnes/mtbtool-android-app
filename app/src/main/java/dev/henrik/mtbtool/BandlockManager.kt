package dev.henrik.mtbtool

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlin.math.abs

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

    const val PATH_LTE_PRIMARY_SIM1   = "/nv/item_files/modem/mmode/lte_bandpref_Subscription01"
    const val PATH_LTE_EXTENSION_SIM1 = "/nv/item_files/modem/mmode/lte_bandpref_extn_65_256_Subscription01"
    const val PATH_NR_SIM1            = "/nv/item_files/modem/mmode/nr_band_pref_Subscription01"
    const val PATH_NR_NSA_SIM1        = "/nv/item_files/modem/mmode/nr_nsa_band_pref_Subscription01"

    /** Returns the four EFS paths to use for the given SIM slot index (0 or 1). */
    data class SlotPaths(
        val ltePrimary: String,
        val lteExtension: String,
        val nr: String,
        val nrNsa: String
    )

    fun pathsForSlot(slot: Int): SlotPaths = when (slot) {
        1    -> SlotPaths(PATH_LTE_PRIMARY_SIM1, PATH_LTE_EXTENSION_SIM1, PATH_NR_SIM1, PATH_NR_NSA_SIM1)
        else -> SlotPaths(PATH_LTE_PRIMARY,      PATH_LTE_EXTENSION,      PATH_NR,      PATH_NR_NSA)
    }

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

    // Fallback byte offsets used when auto-detection fails
    private const val DIAG_OFFSET_LTE_DEFAULT    = 36
    private const val DIAG_OFFSET_NR_SA_DEFAULT  = 108
    private const val DIAG_OFFSET_NR_NSA_DEFAULT = 172

    // NR bands that fit within a 10-byte bitmask (bit index = band-1 < 80).
    // mmWave bands (257+) are excluded — they would never appear here.
    private val NR_BANDS_SUB80: List<Int> = ALL_NR_BANDS.filter { it <= 80 }

    /**
     * Scans [diagBytes] to find the most likely byte offsets for the LTE, NR SA,
     * and NR NSA bitmasks, accommodating devices where the DIAG response layout
     * differs from the baseline offsets.
     *
     * A candidate offset is accepted when decoding it as a bitmask against the
     * known-bands list produces **zero spurious set bits** (no set bit lands on a
     * band number outside the known list) and **at least [minBands] known bands**.
     * The highest-scoring (most known bands) such offset wins.
     *
     * For NR, two non-overlapping offsets (≥ 10 bytes apart) are selected; the
     * lower one is assigned to SA and the higher to NSA, matching the layout
     * convention of the baseline DIAG response.
     *
     * Falls back to the hardcoded defaults if no valid offset is found.
     */
    internal data class BandOffsets(val lte: Int, val nrSa: Int, val nrNsa: Int)

    internal fun detectBandOffsets(
        diagBytes: List<Int>,
        minBands: Int = 5
    ): BandOffsets {
        fun scanCandidates(length: Int, knownBands: List<Int>): List<Pair<Int, Int>> {
            // Returns list of (score, offset) sorted best-first
            val results = mutableListOf<Pair<Int, Int>>()
            val maxOffset = diagBytes.size - length
            for (offset in 0..maxOffset) {
                var found = 0
                var spurious = 0
                for (bit in 0 until length * 8) {
                    val band = bit + 1
                    val byteIndex = offset + bit / 8
                    val bitInByte = bit % 8
                    if ((diagBytes[byteIndex] shr bitInByte) and 1 == 1) {
                        if (band in knownBands) found++ else spurious++
                    }
                }
                if (spurious == 0 && found >= minBands) {
                    results.add(found to offset)
                }
            }
            results.sortByDescending { it.first }
            return results
        }

        // ── LTE ───────────────────────────────────────────────────────────────
        val lteCandidates = scanCandidates(9, ALL_LTE_BANDS)
        val lteOffset = lteCandidates.firstOrNull()?.second ?: DIAG_OFFSET_LTE_DEFAULT

        // ── NR: pick two non-overlapping offsets, lower=SA, higher=NSA ────────
        val nrCandidates = scanCandidates(10, NR_BANDS_SUB80)
        val nrOffsets = mutableListOf<Int>()
        for ((_, offset) in nrCandidates) {
            if (nrOffsets.all { abs(it - offset) >= 10 }) {
                nrOffsets.add(offset)
            }
            if (nrOffsets.size == 2) break
        }
        nrOffsets.sort()
        val nrSaOffset  = nrOffsets.getOrElse(0) { DIAG_OFFSET_NR_SA_DEFAULT }
        // If only one NR region was found, reuse it for NSA rather than falling back
        // to the hardcoded default (which would be wrong on a device with a shifted layout).
        val nrNsaOffset = nrOffsets.getOrElse(1) { nrOffsets.getOrElse(0) { DIAG_OFFSET_NR_NSA_DEFAULT } }

        return BandOffsets(lte = lteOffset, nrSa = nrSaOffset, nrNsa = nrNsaOffset)
    }

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
     * Like [parseBytes] but returns an empty list instead of throwing when the NV file
     * does not exist or returns unrecognised output. Use this whenever a missing file
     * should be treated as "all zeros" (all bands disabled) rather than an error.
     */
    fun parseBytesOrEmpty(raw: String): List<Int> = try {
        parseBytes(raw)
    } catch (_: IllegalStateException) {
        emptyList()
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
     * Automatically detects the byte offsets for LTE (9 bytes), NR SA (10 bytes),
     * and NR NSA (10 bytes) using [detectBandOffsets], falling back to the baseline
     * offsets (LTE=36, NR SA=108, NR NSA=172) if detection finds no valid bitmask.
     */
    fun parseSupportedBands(diagBytes: List<Int>): DetectedBands {
        val offsets = detectBandOffsets(diagBytes)
        val lte   = parseBitmaskBands(diagBytes, offsets.lte,   9,  ALL_LTE_BANDS)
        val nrSa  = parseBitmaskBands(diagBytes, offsets.nrSa,  10, ALL_NR_BANDS)
        val nrNsa = parseBitmaskBands(diagBytes, offsets.nrNsa, 10, ALL_NR_BANDS)
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
