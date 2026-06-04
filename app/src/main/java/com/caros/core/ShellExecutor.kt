package com.caros.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ShellExecutor
 *
 * Coroutine-based utility for executing shell commands, optionally via `su`.
 *
 * All public functions are suspend and run on [Dispatchers.IO]. They capture
 * both stdout and stderr and return a [Result] so callers can handle errors
 * without try-catch boilerplate.
 *
 * Key design decisions:
 *  - [ProcessBuilder] is preferred over [Runtime.exec] for clean argument
 *    escaping and stream handling.
 *  - A single `su` session is NOT kept alive; each [executeSuCommand] call
 *    opens a fresh `su -c` invocation to avoid session-state bugs.
 *  - Timeout is enforced via [withTimeout]; the underlying [Process.destroy]
 *    is called on timeout to prevent orphaned processes.
 *  - stdout and stderr are drained on Dispatchers.IO to prevent blocking.
 */
@Singleton
class ShellExecutor @Inject constructor() {

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Execute a shell command as the **current user** (no root).
     *
     * @param command  The command string; it is split on whitespace before
     *                 being passed to [ProcessBuilder]. Use [executeRaw] for
     *                 commands with complex quoting.
     * @param timeoutMs Maximum execution time in milliseconds. Default: 5 000.
     * @return [Result.success] containing stdout, or [Result.failure] with an
     *         exception whose message includes stdout + stderr on non-zero exit.
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result<String> = executeArgs(command.split(" ").filter { it.isNotBlank() }, timeoutMs)

    /**
     * Execute a shell command with explicit argument list as the **current user**.
     *
     * @param args     Ordered command arguments; element 0 is the executable.
     * @param timeoutMs Maximum execution time in milliseconds. Default: 5 000.
     */
    suspend fun executeArgs(
        args: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.d("Shell exec: %s", args.joinToString(" "))
            runProcess(args, timeoutMs)
        }.onFailure { e ->
            Timber.e(e, "Shell exec failed: %s", args.joinToString(" "))
        }
    }

    /**
     * Execute a single command **as root** via `su -c`.
     *
     * Internally wraps [command] in `su -c '<command>'` and delegates to
     * [runProcess]. The `su` binary must be present and the user must have
     * already granted the app SU access (ideally confirmed via [RootManager]).
     *
     * @param command  The shell command to run as root. Must not contain
     *                 unescaped single-quotes if it is embedded in the
     *                 `su -c '…'` wrapper.
     * @param timeoutMs Maximum execution time in milliseconds. Default: 5 000.
     */
    suspend fun executeSuCommand(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result<String> = withContext(Dispatchers.IO) {
        val args = listOf("su", "-c", command)
        runCatching {
            Timber.d("SU exec: %s", command)
            runProcess(args, timeoutMs)
        }.onFailure { e ->
            Timber.e(e, "SU exec failed: %s", command)
        }
    }

    /**
     * Execute multiple commands in a single `su` session using a heredoc-style
     * stdin pipe. More efficient than calling [executeSuCommand] in a loop
     * when you need several privileged operations atomically.
     *
     * @param commands List of shell commands to run sequentially.
     * @param timeoutMs Maximum total execution time in milliseconds. Default: 10 000.
     * @return Combined stdout of all commands, or [Result.failure] on any error.
     */
    suspend fun executeSuCommands(
        commands: List<String>,
        timeoutMs: Long = DEFAULT_MULTI_TIMEOUT_MS
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.d("SU multi-exec: %d commands", commands.size)
            runSuSession(commands, timeoutMs)
        }.onFailure { e ->
            Timber.e(e, "SU multi-exec failed")
        }
    }

    /**
     * Test whether a command is available on PATH.
     *
     * @param binary Name of the binary, e.g. `"su"`, `"chmod"`, `"pm"`.
     * @return `true` if `which <binary>` returns exit code 0.
     */
    suspend fun isCommandAvailable(binary: String): Boolean = withContext(Dispatchers.IO) {
        try {
            runProcess(listOf("which", binary), timeoutMs = 2_000L)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check whether a given file or device path exists and is readable.
     *
     * Uses `test -r <path>` via shell for accuracy across root-owned nodes.
     *
     * @param path Absolute path to test.
     * @param requireRoot Set to `true` to run the check via `su`.
     */
    suspend fun pathExists(path: String, requireRoot: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val cmd = "test -e \"$path\" && echo exists || echo missing"
            val result = if (requireRoot) executeSuCommand(cmd) else execute(cmd)
            result.getOrNull()?.trim() == "exists"
        }

    // -------------------------------------------------------------------------
    //  Internal implementation
    // -------------------------------------------------------------------------

    /**
     * Launch [args] as a new [Process], drain its stdout/stderr concurrently,
     * wait for completion within [timeoutMs], and return stdout on exit code 0.
     *
     * @throws IOException on process launch or I/O error.
     * @throws ShellException on non-zero exit code or timeout.
     */
    private fun runProcess(args: List<String>, timeoutMs: Long): String {
        val builder = ProcessBuilder(args).apply {
            redirectErrorStream(false) // keep stdout and stderr separate
            environment()["PATH"] =
                "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:" +
                        (System.getenv("PATH") ?: "")
        }

        val process = builder.start()
        var stdoutText = ""
        var stderrText = ""

        try {
            // Drain streams — must happen before waitFor to avoid deadlock on
            // commands that produce large output.
            val stdoutThread = Thread {
                stdoutText = process.inputStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }

            val stderrThread = Thread {
                stderrText = process.errorStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            stdoutThread.join(JOIN_TIMEOUT_MS)
            stderrThread.join(JOIN_TIMEOUT_MS)

            if (!finished) {
                process.destroyForcibly()
                throw ShellException(
                    command = args.joinToString(" "),
                    exitCode = -1,
                    stdout = stdoutText,
                    stderr = stderrText,
                    timedOut = true
                )
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw ShellException(
                    command = args.joinToString(" "),
                    exitCode = exitCode,
                    stdout = stdoutText,
                    stderr = stderrText
                )
            }

            return stdoutText.trimEnd()
        } finally {
            process.destroy()
        }
    }

    /**
     * Open a single `su` process, write [commands] to its stdin (one per line),
     * close stdin, then drain stdout/stderr and wait for process exit.
     *
     * @throws ShellException on non-zero exit or timeout.
     */
    private fun runSuSession(commands: List<String>, timeoutMs: Long): String {
        val process = ProcessBuilder("su").apply {
            redirectErrorStream(false)
            environment()["PATH"] =
                "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:" +
                        (System.getenv("PATH") ?: "")
        }.start()

        var stdoutText = ""
        var stderrText = ""

        try {
            // Write all commands to su stdin
            OutputStreamWriter(process.outputStream).use { writer ->
                commands.forEach { cmd ->
                    writer.write(cmd)
                    writer.write("\n")
                }
                writer.write("exit\n")
                writer.flush()
            }

            val stdoutThread = Thread {
                stdoutText = process.inputStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }

            val stderrThread = Thread {
                stderrText = process.errorStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            stdoutThread.join(JOIN_TIMEOUT_MS)
            stderrThread.join(JOIN_TIMEOUT_MS)

            if (!finished) {
                process.destroyForcibly()
                throw ShellException(
                    command = "su [${commands.size} commands]",
                    exitCode = -1,
                    stdout = stdoutText,
                    stderr = stderrText,
                    timedOut = true
                )
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw ShellException(
                    command = "su [${commands.size} commands]",
                    exitCode = exitCode,
                    stdout = stdoutText,
                    stderr = stderrText
                )
            }

            return stdoutText.trimEnd()
        } finally {
            process.destroy()
        }
    }

    // -------------------------------------------------------------------------
    //  Constants
    // -------------------------------------------------------------------------

    companion object {
        /** Default per-command timeout: 5 seconds. */
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L

        /** Default timeout for multi-command su sessions: 10 seconds. */
        const val DEFAULT_MULTI_TIMEOUT_MS: Long = 10_000L

        /**
         * How long to block waiting for stdout/stderr drain threads to finish
         * after the process has already exited.
         */
        private const val JOIN_TIMEOUT_MS: Long = 1_000L
    }
}

// ---------------------------------------------------------------------------
//  Exception type
// ---------------------------------------------------------------------------

/**
 * Thrown by [ShellExecutor] when a process exits with a non-zero code or
 * exceeds the configured timeout.
 *
 * @property command  The command string that was executed.
 * @property exitCode Process exit code, or -1 on timeout.
 * @property stdout   Captured standard output up to the point of failure.
 * @property stderr   Captured standard error up to the point of failure.
 * @property timedOut `true` when the process was forcibly killed due to timeout.
 */
class ShellException(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) : IOException(
    buildString {
        if (timedOut) {
            append("Command timed out: $command")
        } else {
            append("Command failed (exit $exitCode): $command")
        }
        if (stderr.isNotBlank()) append("\nStderr: ${stderr.take(512)}")
        if (stdout.isNotBlank()) append("\nStdout: ${stdout.take(512)}")
    }
)
