package com.agent.app.shell

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * ADB Shell Bridge - executes shell commands on the device
 * via ADB or directly using Runtime.exec()
 */
class ShellBridge {
    companion object {
        private const val TAG = "ShellBridge"
    }

    /**
     * Execute a shell command and return the output
     */
    fun execute(command: String): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            ShellResult(exitCode, output, error)
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }

    /**
     * Execute with timeout (kills process on timeout)
     */
    fun executeWithTimeout(command: String, timeoutSec: Int): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val outThread = Thread { }
            // read streams in separate threads to avoid pipe deadlock
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread {
                try { out.append(process.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
            }
            val tErr = Thread {
                try { err.append(process.errorStream.bufferedReader().readText()) } catch (_: Exception) {}
            }
            tOut.start(); tErr.start()

            val finished = process.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                tOut.join(1000); tErr.join(1000)
                return ShellResult(-9, out.toString(), err.toString(), timedOut = true)
            }
            tOut.join(2000); tErr.join(2000)
            ShellResult(process.exitValue(), out.toString(), err.toString(), timedOut = false)
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Unknown error", timedOut = false)
        }
    }

    /**
     * Execute a command in background
     */
    fun executeBackground(command: String, onOutput: (String) -> Unit): Process {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onOutput(line!!)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading background output", e)
            }
        }.start()
        return process
    }

    /**
     * Check if ADB is available
     */
    fun isAdbAvailable(): Boolean {
        return try {
            val result = execute("adb devices")
            result.exitCode == 0 && result.output.contains("device")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get device info
     */
    fun getDeviceInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        val result = execute("getprop")
        result.output.lines().forEach { line ->
            if (line.contains(":")) {
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    info[parts[0].trim()] = parts[1].trim()
                }
            }
        }
        return info
    }

    /**
     * Run a command as root (if available)
     */
    fun executeRoot(command: String): ShellResult {
        return execute("su -c '$command'")
    }

    /**
     * Check if device is rooted
     */
    fun isRooted(): Boolean {
        return try {
            val result = execute("su -c 'id'")
            result.exitCode == 0 && result.output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }
}

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val error: String,
    val timedOut: Boolean = false
) {
    val isSuccess: Boolean get() = exitCode == 0
}
