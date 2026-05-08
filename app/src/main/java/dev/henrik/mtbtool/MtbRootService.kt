package dev.henrik.mtbtool

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.TimeUnit

/**
 * libsu RootService. Runs as root UID.
 * Implements the same IMtbService AIDL interface as MtbUserService so all
 * callers are unaware of which backend is active.
 */
class MtbRootService : RootService() {

    override fun onBind(intent: Intent): IBinder = object : IMtbService.Stub() {

        override fun execMtb(args: Array<out String>): Int {
            val cmd = mutableListOf("/vendor/bin/mtb") + args.toList()
            return try {
                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.use { /* drain to avoid blocking */ it.readBytes() }
                val finished = process.waitFor(10, TimeUnit.SECONDS)
                if (!finished) { process.destroy(); -1 } else process.exitValue()
            } catch (e: Exception) {
                -1
            }
        }

        override fun execMtbWithOutput(args: Array<out String>): String {
            val cmd = mutableListOf("/vendor/bin/mtb") + args.toList()
            return try {
                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.use { it.bufferedReader().readText() }
                val finished = process.waitFor(10, TimeUnit.SECONDS)
                val exitCode = if (!finished) { process.destroy(); -1 } else process.exitValue()
                "EXIT:$exitCode\n$output"
            } catch (e: Exception) {
                "EXIT:-1\n${e.message}"
            }
        }

        // No-op: libsu RootService manages the process lifecycle itself.
        override fun destroy() = Unit
    }
}
