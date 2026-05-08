package dev.henrik.mtbtool

import android.content.Context
import com.topjohnwu.superuser.Shell

/**
 * Unified execution backend. Tries root first; falls back to Shizuku if root
 * is denied or unavailable. Exposes the same [isReady] / [execMtbWithOutput]
 * surface as ShizukuManager so all call sites are unchanged.
 */
class ExecutionManager(context: Context) {

    enum class Backend { ROOT, SHIZUKU, NONE }

    private val rootManager    = RootManager(context)
    private val shizukuManager = ShizukuManager()

    /** Which backend is currently selected (may be NONE before startup completes). */
    @Volatile
    var activeBackend: Backend = Backend.NONE
        private set

    val isReady: Boolean get() = rootManager.isReady || shizukuManager.isReady

    /**
     * Starts the priority negotiation:
     * 1. If root was previously granted → bind MtbRootService directly.
     * 2. If root grant status is unknown → request (shows su prompt); on denial fall to Shizuku.
     * 3. If root explicitly not available → start Shizuku immediately.
     */
    fun start() {
        when (Shell.isAppGrantedRoot()) {
            true -> {
                // Root was already granted in a previous session — bind immediately.
                activeBackend = Backend.ROOT
                rootManager.start(onDenied = ::startShizuku)
            }
            false -> {
                // Root is definitively unavailable or was denied.
                startShizuku()
            }
            null -> {
                // First run — we don't know yet. Request root; fall back on denial.
                activeBackend = Backend.ROOT // tentative; reset to SHIZUKU on denial
                rootManager.start(onDenied = ::startShizuku)
            }
        }
    }

    fun stop() {
        rootManager.stop()
        shizukuManager.stop()
    }

    /**
     * Executes mtb and returns combined output prefixed with "EXIT:<n>\n".
     * Delegates to whichever backend is ready.
     */
    fun execMtbWithOutput(args: Array<String>): String {
        if (rootManager.isReady)    return rootManager.execMtbWithOutput(args)
        if (shizukuManager.isReady) return shizukuManager.execMtbWithOutput(args)
        return "EXIT:-1\nNo backend available"
    }

    fun execMtb(args: Array<String>): Int {
        if (rootManager.isReady)    return rootManager.execMtb(args)
        if (shizukuManager.isReady) return shizukuManager.execMtb(args)
        return -1
    }

    /**
     * Called from UI when the user taps "Grant" on the Shizuku banner.
     * Only meaningful when the active backend is SHIZUKU.
     */
    fun requestPermission() {
        if (activeBackend == Backend.SHIZUKU) shizukuManager.requestPermission()
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun startShizuku() {
        activeBackend = Backend.SHIZUKU
        shizukuManager.start()
    }
}
