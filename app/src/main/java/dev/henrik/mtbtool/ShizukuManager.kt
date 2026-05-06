package dev.henrik.mtbtool

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

/**
 * Manages Shizuku permission and MtbUserService binding lifecycle.
 * Exposes observable [isReady] state for UI.
 */
class ShizukuManager {

    var isReady by mutableStateOf(false)
        private set

    private var service: IMtbService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            service = IMtbService.Stub.asInterface(binder)
            isReady = true
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            service = null
            isReady = false
            bindService()
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            bindService()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndRequestPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        isReady = false
    }

    private val userServiceArgs = UserServiceArgs(
        ComponentName("dev.henrik.mtbtool", MtbUserService::class.java.name)
    ).daemon(false).processNameSuffix("user_service").debuggable(false).version(1)

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        service = null
        isReady = false
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) return
        Shizuku.requestPermission(0)
    }

    fun execMtb(args: Array<String>): Int {
        val svc = service ?: return -1
        return svc.execMtb(args)
    }

    /**
     * Runs mtb and returns the raw combined output string prefixed with "EXIT:<n>\n".
     * Returns "EXIT:-1\nShizuku not ready" if service is unavailable.
     */
    fun execMtbWithOutput(args: Array<String>): String {
        val svc = service ?: return "EXIT:-1\nShizuku not ready"
        return svc.execMtbWithOutput(args)
    }

    private fun checkAndRequestPermission() {
        if (!Shizuku.pingBinder()) return
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindService()
            Shizuku.shouldShowRequestPermissionRationale() -> { /* user denied once */ }
            else -> Shizuku.requestPermission(0)
        }
    }

    private fun bindService() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }
    }
}
