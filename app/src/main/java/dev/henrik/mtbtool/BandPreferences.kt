package dev.henrik.mtbtool

import androidx.datastore.preferences.core.stringPreferencesKey

object BandPreferences {
    /** Comma-separated LTE band numbers saved by the user, e.g. "1,3,7,8,20,28,32,66,71". Empty = not configured. */
    val LTE_BANDS = stringPreferencesKey("lte_bands")

    /** Comma-separated NR NSA band numbers saved by the user, e.g. "1,3,28,41,78". Empty = not configured. */
    val NR_NSA_BANDS = stringPreferencesKey("nr_nsa_bands")

    /** Comma-separated NR SA band numbers saved by the user, e.g. "1,3,28,41,78". Empty = not configured. */
    val NR_BANDS = stringPreferencesKey("nr_bands")
}
