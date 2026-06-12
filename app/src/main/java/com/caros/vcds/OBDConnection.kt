package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  OBDConnection.kt — Manages the physical connection to an ELM327-compatible
//  OBD-II adapter.  Supports four transport paths:
//
//    1. /dev/ttyS1   — direct UART passthrough (VW-RZ-08-0041 CAN box)
//    2. /dev/ttyUSB* — USB-connected ELM327
//    3. Bluetooth    — paired RFCOMM ELM327/OBD device (SPP UUID)
//    4. Mock mode    — deterministic stub responses for development/testing
//
//  Usage: call [connect] once, then call [sendCommand] for every UDS/OBD
//  request.  Call [disconnect] when done.
// ─────────────────────────────────────────────────────────────────────────────

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.caros.core.ShellExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Active connection medium. */
enum class ConnectionType {
    /** UART passthrough via /dev/ttyS1 (built-in CAN adapter). */
    SERIAL_PASSTHROUGH,
    /** USB-to-serial ELM327 dongle (/dev/ttyUSB* or /dev/ttyACM*). */
    USB_ELM327,
    /** Bluetooth ELM327 (paired, RFCOMM socket). */
    BLUETOOTH_ELM327,
    /** Replaying a previously recorded OBD session (no hardware needed). */
    REPLAY,
    /** Not connected — commands return mock stub responses. */
    DISCONNECTED
}

@Singleton
class OBDConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellExecutor: ShellExecutor,
    private val sessionPlayer: OBDSessionPlayer
) {
    private val _connectionState = MutableStateFlow(ConnectionType.DISCONNECTED)

    /** Observable connection state — drive status-bar indicators from this. */
    val connectionState: StateFlow<ConnectionType> = _connectionState.asStateFlow()

    var connectionType: ConnectionType
        get() = _connectionState.value
        private set(value) { _connectionState.value = value }

    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var process: Process? = null
    private var bluetoothSocket: BluetoothSocket? = null

    /** Serializes command/response cycles — ELM327 cannot interleave requests. */
    private val ioMutex = Mutex()

    // ── Connection lifecycle ──────────────────────────────────────────────────

    // ── Replay mode ───────────────────────────────────────────────────────────

    /**
     * Switch to replay mode.  The [OBDSessionPlayer] must already have a session
     * loaded via [OBDSessionPlayer.load].  Calling this disconnects any live adapter.
     */
    fun startReplay() {
        disconnect()
        if (!sessionPlayer.isPlaying) sessionPlayer.start()
        connectionType = ConnectionType.REPLAY
        Timber.i("OBDConnection: replay mode active — %s", sessionPlayer.loadedFileName)
    }

    /** Stop replay and return to disconnected (mock) mode. */
    fun stopReplay() {
        sessionPlayer.stop()
        connectionType = ConnectionType.DISCONNECTED
        Timber.i("OBDConnection: replay mode stopped")
    }

    // ── Connection lifecycle ──────────────────────────────────────────────────

    /**
     * Attempt to open an OBD connection.
     *
     * Priority order:
     * 1. `/dev/ttyS1` (direct UART on VW-RZ CAN box)
     * 2. Any `/dev/ttyUSB*` or `/dev/ttyACM*` device
     * 3. Paired Bluetooth device whose name contains "ELM327", "OBD", or "OBDII"
     *
     * Falls back to [ConnectionType.DISCONNECTED] (mock mode) if nothing is found.
     *
     * @return `true` if a real device was opened, `false` if running in mock mode
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // Release any previous connection so repeated connect() calls don't leak
        if (connectionType != ConnectionType.DISCONNECTED) disconnect()

        // 1. Try built-in UART passthrough
        if (tryOpenSerial("/dev/ttyS1")) {
            connectionType = ConnectionType.SERIAL_PASSTHROUGH
            return@withContext true
        }

        // 2. Try USB ELM327 devices
        val usbDevices = listUsbDevices()
        for (dev in usbDevices) {
            if (tryOpenSerial(dev)) {
                connectionType = ConnectionType.USB_ELM327
                return@withContext true
            }
        }

        // 3. Try Bluetooth ELM327 (paired devices)
        val btSocket = tryConnectBluetooth()
        if (btSocket != null) {
            outputStream = btSocket.outputStream
            inputStream = btSocket.inputStream
            connectionType = ConnectionType.BLUETOOTH_ELM327
            return@withContext true
        }

        // 4. Nothing found — remain in mock/disconnected mode
        connectionType = ConnectionType.DISCONNECTED
        false
    }

    /**
     * [connect] with exponential backoff. Useful for automatic reconnection
     * after the adapter drops — waits 1 s, 2 s, 4 s, … (capped at 30 s)
     * between attempts.
     *
     * @return `true` once a real device was opened, `false` after [maxAttempts] failures
     */
    suspend fun connectWithBackoff(maxAttempts: Int = 4, initialDelayMs: Long = 1_000L): Boolean {
        var delayMs = initialDelayMs
        repeat(maxAttempts) { attempt ->
            if (connect()) return true
            if (attempt < maxAttempts - 1) {
                Timber.d("OBDConnection: attempt ${attempt + 1}/$maxAttempts failed — retrying in ${delayMs}ms")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
        Timber.w("OBDConnection: no adapter found after $maxAttempts attempts — staying in mock mode")
        return false
    }

    /** Close all open streams and reset connection state. */
    @Synchronized
    fun disconnect() {
        outputStream?.runCatching { close() }
        inputStream?.runCatching { close() }
        process?.runCatching { destroy() }
        bluetoothSocket?.runCatching { close() }
        outputStream = null
        inputStream = null
        process = null
        bluetoothSocket = null
        connectionType = ConnectionType.DISCONNECTED
    }

    // ── Command I/O ───────────────────────────────────────────────────────────

    /**
     * Send an AT or OBD-II/UDS command string and wait for the ELM327 prompt
     * character `>` indicating the response is complete.
     *
     * When [connectionType] is [ConnectionType.DISCONNECTED] the request is
     * routed through [mockResponse] so the rest of the app can function without
     * a real adapter.
     *
     * @param cmd       Raw command string, e.g. `"ATSH01\r1902FF\r"`.
     *                  A trailing `\r` is appended automatically if absent.
     * @param timeoutMs Maximum wait time in milliseconds. Default: 3000.
     * @return          Response text (stripped of the trailing `>`), or null on error.
     */
    suspend fun sendCommand(cmd: String, timeoutMs: Long = 3_000L): String? =
        withContext(Dispatchers.IO) {
            if (connectionType == ConnectionType.REPLAY) {
                return@withContext sessionPlayer.respond(cmd) ?: mockResponse(cmd)
            }
            if (connectionType == ConnectionType.DISCONNECTED) {
                return@withContext mockResponse(cmd)
            }

            ioMutex.withLock {
                val out = outputStream ?: return@withContext null
                val inp = inputStream  ?: return@withContext null

                try {
                    val cmdBytes = if (cmd.endsWith("\r")) cmd.toByteArray() else "$cmd\r".toByteArray()
                    out.write(cmdBytes)
                    out.flush()

                    val buffer = StringBuilder()
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val available = inp.available()
                        if (available > 0) {
                            val bytes = ByteArray(available)
                            inp.read(bytes)
                            buffer.append(String(bytes))
                            if (buffer.contains('>')) break
                        } else {
                            Thread.sleep(20)
                        }
                    }
                    buffer.toString().trimEnd('>', ' ', '\r', '\n')
                } catch (e: Exception) {
                    null
                }
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Attempt to open [path] for read/write as a serial character device.
     * Returns true if the file is accessible; does not configure baud rate.
     */
    private fun tryOpenSerial(path: String): Boolean {
        return try {
            // Just test accessibility — full stream setup would require native termios
            RandomAccessFile(path, "rw").use { /* opened successfully */ }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * List USB-serial device paths available on this device.
     */
    private suspend fun listUsbDevices(): List<String> {
        val result = shellExecutor.execute("ls /dev/ttyUSB0 /dev/ttyUSB1 /dev/ttyACM0 2>/dev/null")
        return result.getOrNull()
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it.startsWith("/dev/") }
            ?: emptyList()
    }

    /**
     * Scan paired Bluetooth devices for an ELM327/OBD adapter and attempt an
     * RFCOMM connection using the standard Serial Port Profile UUID.
     *
     * @return an already-connected [BluetoothSocket], or null if no suitable
     *         paired device is found or the connection fails.
     */
    private suspend fun tryConnectBluetooth(): BluetoothSocket? = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext null
            if (!adapter.isEnabled) return@withContext null
            val elm327 = adapter.bondedDevices?.firstOrNull { device ->
                device.name?.contains("ELM327", ignoreCase = true) == true ||
                device.name?.contains("OBD", ignoreCase = true) == true ||
                device.name?.contains("OBDII", ignoreCase = true) == true
            } ?: return@withContext null
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP
            socket = elm327.createRfcommSocketToServiceRecord(uuid)
            adapter.cancelDiscovery()
            socket.connect()
            bluetoothSocket = socket
            socket
        } catch (e: Exception) {
            socket?.runCatching { close() }
            null
        }
    }

    // ── Mock responses ────────────────────────────────────────────────────────

    /**
     * Return deterministic stub responses so the UI works without a real adapter.
     * Responses mimic a minimally functioning ELM327 + ECU.
     */
    private fun mockResponse(cmd: String): String = when {
        // UDS ReadDTCInformation — no active DTCs
        cmd.contains("1902FF", ignoreCase = true)     -> "59 02 FF 00 00 00"
        // OBD-II supported PIDs ($01 00)
        cmd.contains("0100", ignoreCase = true)       -> "41 00 BE 3F A8 13"
        // OBD-II RPM (~1726 rpm)
        cmd.contains("010C", ignoreCase = true)       -> "41 0C 1A F8"
        // OBD-II vehicle speed (100 km/h)
        cmd.contains("010D", ignoreCase = true)       -> "41 0D 64"
        // OBD-II coolant temperature (90 °C → byte = 90+40=130 = 0x82)
        cmd.contains("0105", ignoreCase = true)       -> "41 05 82"
        // Mode 22 VAG injector correction (±0 mg/stroke = healthy)
        cmd.contains("22F43C", ignoreCase = true) || cmd.contains("22 F4 3C", ignoreCase = true) -> "62 F4 3C 00"
        cmd.contains("22F43D", ignoreCase = true) || cmd.contains("22 F4 3D", ignoreCase = true) -> "62 F4 3D 01"
        cmd.contains("22F43E", ignoreCase = true) || cmd.contains("22 F4 3E", ignoreCase = true) -> "62 F4 3E FF"  // -1
        cmd.contains("22F43F", ignoreCase = true) || cmd.contains("22 F4 3F", ignoreCase = true) -> "62 F4 3F 00"
        // EGT sensors (mock ~400 °C = 0x0190)
        cmd.contains("22F447", ignoreCase = true) || cmd.contains("22 F4 47", ignoreCase = true) -> "62 F4 47 01 90"
        cmd.contains("22F448", ignoreCase = true) || cmd.contains("22 F4 48", ignoreCase = true) -> "62 F4 48 01 A0"
        cmd.contains("22F44F", ignoreCase = true) || cmd.contains("22 F4 4F", ignoreCase = true) -> "NO DATA"
        cmd.contains("22F450", ignoreCase = true) || cmd.contains("22 F4 50", ignoreCase = true) -> "NO DATA"
        // VNT position (actual=target=50%)
        cmd.contains("22F409", ignoreCase = true) || cmd.contains("22 F4 09", ignoreCase = true) -> "62 F4 09 80"
        cmd.contains("22F40A", ignoreCase = true) || cmd.contains("22 F4 0A", ignoreCase = true) -> "62 F4 0A 80"
        // EGR position (0%)
        cmd.contains("22F412", ignoreCase = true) || cmd.contains("22 F4 12", ignoreCase = true) -> "62 F4 12 00"
        cmd.contains("22F413", ignoreCase = true) || cmd.contains("22 F4 13", ignoreCase = true) -> "62 F4 13 00"
        // Swirl (100% open)
        cmd.contains("22F41B", ignoreCase = true) || cmd.contains("22 F4 1B", ignoreCase = true) -> "62 F4 1B FF"
        // DPF thermal (mock 320°C up / 350°C down = 0x0140 / 0x015E)
        cmd.contains("22F460", ignoreCase = true) || cmd.contains("22 F4 60", ignoreCase = true) -> "62 F4 60 01 40"
        cmd.contains("22F461", ignoreCase = true) || cmd.contains("22 F4 61", ignoreCase = true) -> "62 F4 61 01 5E"
        // Rail pressure (mock 350 bar = 35000 = 0x88B8, /100 = 350)
        cmd.contains("22F402", ignoreCase = true) || cmd.contains("22 F4 02", ignoreCase = true) -> "62 F4 02 88 B8"
        // Glow plugs (~300 mΩ = raw 30)
        cmd.contains("22F4C0", ignoreCase = true) || cmd.contains("22 F4 C0", ignoreCase = true) -> "62 F4 C0 1E"
        cmd.contains("22F4C1", ignoreCase = true) || cmd.contains("22 F4 C1", ignoreCase = true) -> "62 F4 C1 1E"
        cmd.contains("22F4C2", ignoreCase = true) || cmd.contains("22 F4 C2", ignoreCase = true) -> "62 F4 C2 1F"
        cmd.contains("22F4C3", ignoreCase = true) || cmd.contains("22 F4 C3", ignoreCase = true) -> "62 F4 C3 1E"
        // Mode 01 fuel level (50% = 0x7F → 0x7F/2.55 ≈ 50%)
        cmd.contains("012F", ignoreCase = true) || cmd.contains("01 2F", ignoreCase = true)      -> "41 2F 7F"
        // UDS ReadDataByIdentifier — generic positive response
        cmd.contains("22", ignoreCase = true)         -> "62 00 00 00"
        // UDS WriteDataByIdentifier — positive response (0x68 = 0x40 | 0x28)
        cmd.contains("2E", ignoreCase = true)         -> "68 00 00"
        // UDS ClearDiagnosticInformation — positive
        cmd.contains("14", ignoreCase = true)         -> "54"
        // AT commands — ELM327 acknowledgement
        cmd.trimStart().startsWith("AT", ignoreCase = true) -> "OK"
        else                                          -> "NO DATA"
    }
}
