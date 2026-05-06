package dev.henrik.mtbtool

/**
 * Shizuku UserService. Runs as shell UID.
 * Executes /vendor/bin/mtb via ProcessBuilder.
 */
class MtbUserService : IMtbService.Stub() {

    override fun execMtb(args: Array<out String>): Int {
        val cmd = mutableListOf("/vendor/bin/mtb") + args.toList()
        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            process.waitFor()
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
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            "EXIT:$exitCode\n$output"
        } catch (e: Exception) {
            "EXIT:-1\n${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
