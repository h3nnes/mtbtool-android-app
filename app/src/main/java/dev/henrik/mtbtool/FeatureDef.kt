// app/src/main/java/dev/henrik/mtbtool/FeatureDef.kt
package dev.henrik.mtbtool

private const val NR_BASE = "/nv/item_files/modem/nr5g/RRC/"

/**
 * One NV item to write when disabling a feature.
 * [path] is the full EFS path.
 * [bytes] is the space-separated decimal byte string to write.
 */
data class NvWrite(
    val path: String,
    val bytes: String
)

/**
 * Definition of a single disable-able modem feature.
 * [id]          unique stable identifier (used as map key)
 * [label]       human-readable name shown in the UI
 * [reads]       list of full EFS paths to read for the check
 * [writes]      list of NvWrite operations that fully disable the feature
 * [isDisabled]  given the list of byte arrays (one per read path, in order),
 *               returns true when the feature is already disabled
 */
data class FeatureDef(
    val id: String,
    val label: String,
    val reads: List<String>,
    val writes: List<NvWrite>,
    val isDisabled: (List<List<Int>>) -> Boolean
)

val ALL_FEATURES: List<FeatureDef> = listOf(
    FeatureDef(
        id = "r17_2t2t",
        label = "Disable R17 2T2T UL Tx Switching",
        reads = listOf(NR_BASE + "cap_control_nrca_xf_plus_yt_swul_r17_band_combos_v2"),
        writes = listOf(
            NvWrite(
                path = NR_BASE + "cap_control_nrca_xf_plus_yt_swul_r17_band_combos_v2",
                bytes = "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
            )
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.all { it == 0 }
        }
    ),
    FeatureDef(
        id = "r16_2t1t",
        label = "Disable R16 2T1T UL Tx Switching",
        reads = listOf(
            NR_BASE + "cap_control_nrca_xf_plus_yt_swul_band_combos_v2",
            NR_BASE + "cap_swul_type_control"
        ),
        writes = listOf(
            NvWrite(
                path = NR_BASE + "cap_control_nrca_xf_plus_yt_swul_band_combos_v2",
                bytes = "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
            ),
            NvWrite(
                path = NR_BASE + "cap_swul_type_control",
                bytes = "0 0 0"
            )
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 2) return@FeatureDef false
            val (nrca, swulTypeControl) = byteArrays
            nrca.all { it == 0 } && swulTypeControl.all { it == 0 }
        }
    ),
    FeatureDef(
        id = "ul_mimo",
        label = "Disable UL MIMO",
        reads = listOf(NR_BASE + "cap_limit_rf_mimo"),
        writes = listOf(
            NvWrite(
                path = NR_BASE + "cap_limit_rf_mimo",
                bytes = "1 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
            )
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.size >= 3 && b[0] == 1 && b[1] == 1 && b[2] == 0
        }
    ),
    FeatureDef(
        id = "nr_ulca",
        label = "Disable NR UL-CA",
        reads = listOf(
            NR_BASE + "cap_control_nrca_2x_f_plus_t_band_combos",
            NR_BASE + "cap_control_nrca_3x_f_plus_t_band_combos",
            NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos",
            NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos_v2",
            NR_BASE + "cap_control_nrca_f_plus_f_ulca_band_combos"
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_nrca_2x_f_plus_t_band_combos",    "0"),
            NvWrite(NR_BASE + "cap_control_nrca_3x_f_plus_t_band_combos",    "1 1 1 1 0 0"),
            NvWrite(NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos",    "0 1 1"),
            NvWrite(NR_BASE + "cap_control_nrca_4x_f_plus_t_band_combos_v2", "0 1 1 0 1 1"),
            NvWrite(NR_BASE + "cap_control_nrca_f_plus_f_ulca_band_combos",  "0 0")
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 5) return@FeatureDef false
            val (b0, b1, b2, b3, b4) = byteArrays
            b0.firstOrNull() == 0 &&
            b1.size >= 6 && b1[0] == 1 && b1[1] == 1 && b1[2] == 1 && b1[3] == 1 && b1[4] == 0 && b1[5] == 0 &&
            b2.size >= 3 && b2[0] == 0 && b2[1] == 1 && b2[2] == 1 &&
            b3.size >= 6 && b3[0] == 0 && b3[1] == 1 && b3[2] == 1 && b3[3] == 0 && b3[4] == 1 && b3[5] == 1 &&
            b4.size >= 2 && b4[0] == 0 && b4[1] == 0
        }
    ),
    FeatureDef(
        id = "dl_nrca",
        label = "Disable NR DL-CA",
        reads = listOf(NR_BASE + "cap_nrca_downgrade_1cc"),
        writes = listOf(
            NvWrite(
                path = NR_BASE + "cap_nrca_downgrade_1cc",
                bytes = "1"
            )
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.firstOrNull() == 1
        }
    ),
    FeatureDef(
        id = "lowband_4rx",
        label = "Disable Lowbands 4Rx",
        reads = listOf(NR_BASE + "cap_limit_rf_mimo"),
        writes = listOf(
            NvWrite(
                path = NR_BASE + "cap_limit_rf_mimo",
                bytes = "0 0 0 5 8 0 0 2 20 0 0 2 26 0 0 2 28 0 0 2 71 0 0 2 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
            )
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            val bandBytes = listOf(8, 0, 0, 2, 20, 0, 0, 2, 26, 0, 0, 2, 28, 0, 0, 2, 71, 0, 0, 2)
            b.size >= 24 &&
            b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 5 &&
            bandBytes.indices.all { i -> b[4 + i] == bandBytes[i] }
        }
    ),
    // ── NSA NR-CA features ────────────────────────────────────────────────────────
    FeatureDef(
        id = "nsa_tf_nrca",
        label = "Disable T+F NSA NR-CA",
        reads = listOf(
            NR_BASE + "cap_control_mrdc_f_plus_t_band_combos",
            NR_BASE + "cap_control_t_plus_f_band_combos"
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_mrdc_f_plus_t_band_combos", "0"),
            NvWrite(NR_BASE + "cap_control_t_plus_f_band_combos",      "7")
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 2) return@FeatureDef false
            val (b0, b1) = byteArrays
            b0.firstOrNull() == 0 &&
            b1.firstOrNull() == 7
        }
    ),
    FeatureDef(
        id = "nsa_ff_nrca",
        label = "Disable F+F NSA NR-CA",
        reads = listOf(NR_BASE + "cap_control_mrdc_2x_f_plus_f_band_combos"),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_mrdc_2x_f_plus_f_band_combos", "0")
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.firstOrNull() == 0
        }
    ),
    FeatureDef(
        id = "nsa_tt_nrca",
        label = "Disable T+T NSA NR-CA",
        reads = listOf(
            NR_BASE + "cap_control_mrdc_t_plus_t_band_combos",
            NR_BASE + "cap_control_nr_t_plus_t_band_combos"
        ),
        writes = listOf(
            NvWrite(NR_BASE + "cap_control_mrdc_t_plus_t_band_combos", "0 0 0"),
            NvWrite(NR_BASE + "cap_control_nr_t_plus_t_band_combos",   "0 0")
        ),
        isDisabled = { byteArrays ->
            if (byteArrays.size < 2) return@FeatureDef false
            val (b0, b1) = byteArrays
            b0.size >= 3 && b0[0] == 0 && b0[1] == 0 && b0[2] == 0 &&
            b1.size >= 2 && b1[0] == 0 && b1[1] == 0
        }
    ),
    FeatureDef(
        id = "segmentation",
        label = "Disable Segmentation",
        reads = listOf(NR_BASE + "cap_msg_segmentation"),
        writes = listOf(
            NvWrite(NR_BASE + "cap_msg_segmentation", "0")
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.firstOrNull() == 0
        }
    ),
    FeatureDef(
        id = "dss",
        label = "Disable DSS",
        reads = listOf(NR_BASE + "cap_dss_control"),
        writes = listOf(
            NvWrite(NR_BASE + "cap_dss_control", "0 0")
        ),
        isDisabled = { byteArrays ->
            val b = byteArrays.firstOrNull() ?: return@FeatureDef true
            b.size >= 2 && b[0] == 0 && b[1] == 0
        }
    )
)

sealed class FeatureStatus {
    object AlreadyDisabled : FeatureStatus()
    object CanDisable : FeatureStatus()
    object Writing : FeatureStatus()
    object Restoring : FeatureStatus()
    data class WriteError(val message: String) : FeatureStatus()
    data class ReadError(val message: String) : FeatureStatus()
}
