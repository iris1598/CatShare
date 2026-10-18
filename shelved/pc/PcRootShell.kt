package moe.reimu.catshare.pc

import android.util.Log

/** Tiny helper to run commands through a root shell. */
object PcRootShell {
    private const val TAG = "CatSharePc"

    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    @Synchronized
    fun run(command: String, timeoutMs: Long = 15000): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "root command timed out: $command")
                Result(-1, output)
            } else {
                Result(process.exitValue(), output)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "root command failed: $command", e)
            Result(-1, e.toString())
        }
    }

    fun isRootAvailable(): Boolean {
        val r = run("id", 4000)
        return r.ok && r.output.contains("uid=0")
    }
}
