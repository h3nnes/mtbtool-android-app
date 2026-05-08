package dev.henrik.mtbtool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService

/**
 * Manages the libsu RootService binding lifecycle.
 * Mirrors the structure of ShizukuManager: exposes observable [isReady]
 * and [execMtbWithOutput] with the same contract.
 */
class RootManager(private val context: Context) {

    var isReady by mutableStateOf(false)
        private set

    @Volatile private var service: IMtbService? = null
    @Volatile private var stopped = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IMtbService.Stub.asInterface(binder)
            isReady = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            isReady = false
            // Auto-retry unless stop() was called intentionally.
            if (!stopped) bindService()
        }
    }

    /**
     * Opens a root shell (triggering the su grant prompt if needed), then
     * binds [MtbRootService] on success. Calls [onDenied] if root access
     * is denied by the user or unavailable.
     */
    fun start(onDenied: () -> Unit) {
        stopped = false
        Shell.getShell { shell ->
            if (shell.isRoot) {
                Handler(Looper.getMainLooper()).post { bindService() }
            } else {
                Handler(Looper.getMainLooper()).post { onDenied() }
            }
        }
    }

    fun stop() {
        stopped = true
        RootService.unbind(serviceConnection)
        service = null
        isReady = false
    }

    fun execMtbWithOutput(args: Array<String>): String {
        val svc = service ?: return "EXIT:-1\nRoot service not ready"
        return svc.execMtbWithOutput(args)
    }

    fun execMtb(args: Array<String>): Int {
        val svc = service ?: return -1
        return svc.execMtb(args)
    }

    private fun bindService() {
        val intent = Intent(context, MtbRootService::class.java)
        RootService.bind(intent, serviceConnection)
    }
}
