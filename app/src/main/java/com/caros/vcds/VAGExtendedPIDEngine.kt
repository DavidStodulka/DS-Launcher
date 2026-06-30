package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  VAGExtendedPIDEngine.kt — Polls OBD-II Mode 01 and VAG UDS Mode 22 PIDs
//  via ELM327-compatible adapter (vLinker MC+ / OBDLink / ELM327 BT).
//
//  All PIDs are calibrated for 1.6 TDI EA288 on Seat León 5F.
//  Mark // CALIBRATE where ECU address / PID must be confirmed on a live vehicle.
//
//  Protocol:
//    AT SH XXX  — set 11-bit CAN header (ECU physical address)
//    22 XX XX   — UDS ReadDataByIdentifier (DID)
//    01 XX      — OBD-II Mode 01 PID
//
//  Response:
//    62 XX XX [data...]  — positive Mode 22 response
//    41 XX [data...]     — positive Mode 01 response
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.can.FuelLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VAGExtendedPIDEngine @Inject constructor(
    private val obdConnection: OBDConnection
) {
    private val _diagnostics = MutableStateFlow(TDIDiagnostics())
    val diagnostics: StateFlow<TDIDiagnostics> = _diagnostics.asStateFlow()

    private val _fuelLevel = MutableStateFlow<FuelLevel?>(null)
    val fuelLevel: StateFlow<FuelLevel?> = _fuelLevel.asStateFlow()

    companion object {
        // Engine ECU physical CAN address (VAG gateway — // CALIBRATE)
        private const val ENGINE_ECU = "7E0"
        private const val TANK_LITRES = 55f

        // ── VAG Mode 22 DIDs for EA288 1.6 TDI ── // CALIBRATE all PIDs
        private const val DID_INJECTOR_CYL1   = "F43C"
        private const val DID_INJECTOR_CYL2   = "F43D"
        private const val DID_INJECTOR_CYL3   = "F43E"
        private const val DID_INJECTOR_CYL4   = "F43F"
        private const val DID_EGT1            = "F447"
        private const val DID_EGT2            = "F448"
        private const val DID_EGT3            = "F44F"
        private const val DID_EGT4            = "F450"
        private const val DID_VNT_ACTUAL      = "F409"
        private const val DID_VNT_TARGET      = "F40A"
        private const val DID_EGR_ACTUAL      = "F412"
        private const val DID_EGR_TARGET      = "F413"
        private const val DID_SWIRL           = "F41B"
        private const val DID_DPF_TEMP_UP     = "F460"
        private const val DID_DPF_TEMP_DN     = "F461"
        private const val DID_RAIL_PRESSURE   = "F402"
        private const val DID_GLOW_CYL1       = "F4C0"
        private const val DID_GLOW_CYL2       = "F4C1"
        private const val DID_GLOW_CYL3       = "F4C2"
        private const val DID_GLOW_CYL4       = "F4C3"
    }

    /**
     * Run one complete poll cycle — call this from a coroutine loop.
     * Returns false if the adapter is not connected (skip until it is).
     */
    suspend fun pollCycle(): Boolean {
        if (obdConnection.connectionType == ConnectionType.DISCONNECTED) return false

        // Switch to Engine ECU address
        val headerOk = obdConnection.sendCommand("AT SH $ENGINE_ECU")
        if (headerOk?.contains("OK", ignoreCase = true) != true) {
            Timber.w("VAGExtendedPIDEngine: AT SH failed, skipping cycle")
            return false
        }

        val current = _diagnostics.value

        val injectors = pollInjectorCorrections()
        val egt       = pollEGT()
        val turbo     = pollTurbo()
        val egr       = pollEGR()
        val swirl     = pollSwirl()
        val dpfTherm  = pollDPFThermal()
        val rail      = pollRailPressure()
        val glows     = pollGlowPlugs()

        _diagnostics.value = current.copy(
            injectorCorrection = injectors ?: current.injectorCorrection,
            egt                = egt       ?: current.egt,
            turbo              = turbo     ?: current.turbo,
            egr                = egr       ?: current.egr,
            swirlPct           = swirl     ?: current.swirlPct,
            dpfThermal         = dpfTherm  ?: current.dpfThermal,
            fuelRailBar        = rail      ?: current.fuelRailBar,
            glowPlugs          = glows     ?: current.glowPlugs,
            timestampMs        = System.currentTimeMillis()
        )

        pollFuelLevel()
        return true
    }

    // ── Per-group pollers ─────────────────────────────────────────────────────

    private suspend fun pollInjectorCorrections(): InjectorCorrection? {
        val c1 = mode22SignedByte(DID_INJECTOR_CYL1) ?: return null
        val c2 = mode22SignedByte(DID_INJECTOR_CYL2) ?: return null
        val c3 = mode22SignedByte(DID_INJECTOR_CYL3) ?: return null
        val c4 = mode22SignedByte(DID_INJECTOR_CYL4) ?: return null
        return InjectorCorrection(c1, c2, c3, c4)
    }

    private suspend fun pollEGT(): EGTData? {
        val t1 = mode22U16(DID_EGT1) ?: return null
        val t2 = mode22U16(DID_EGT2) ?: return null
        val t3 = mode22U16(DID_EGT3)
        val t4 = mode22U16(DID_EGT4)
        return EGTData(t1, t2, t3, t4)
    }

    private suspend fun pollTurbo(): TurboData? {
        val actual = mode22U8Pct(DID_VNT_ACTUAL) ?: return null
        val target = mode22U8Pct(DID_VNT_TARGET) ?: return null
        return TurboData(actual, target)
    }

    private suspend fun pollEGR(): EGRData? {
        val actual = mode22U8Pct(DID_EGR_ACTUAL) ?: return null
        val target = mode22U8Pct(DID_EGR_TARGET) ?: return null
        return EGRData(actual, target)
    }

    private suspend fun pollSwirl(): Float? = mode22U8Pct(DID_SWIRL)

    private suspend fun pollDPFThermal(): DPFThermal? {
        val up   = mode22U16(DID_DPF_TEMP_UP) ?: return null
        val down = mode22U16(DID_DPF_TEMP_DN) ?: return null
        return DPFThermal(up, down)
    }

    private suspend fun pollRailPressure(): Float? {
        val raw = mode22U16Raw(DID_RAIL_PRESSURE) ?: return null
        return raw / 100.0f   // raw unit: 0.01 bar → bar
    }

    private suspend fun pollGlowPlugs(): GlowPlugData? {
        val g1 = mode22U8Raw(DID_GLOW_CYL1)?.let { it * 10f } ?: return null
        val g2 = mode22U8Raw(DID_GLOW_CYL2)?.let { it * 10f } ?: return null
        val g3 = mode22U8Raw(DID_GLOW_CYL3)?.let { it * 10f } ?: return null
        val g4 = mode22U8Raw(DID_GLOW_CYL4)?.let { it * 10f } ?: return null
        return GlowPlugData(g1, g2, g3, g4)
    }

    private suspend fun pollFuelLevel() {
        // Mode 01, PID 0x2F — Fuel Tank Level Input
        val response = obdConnection.sendCommand("012F") ?: return
        val bytes = parseResponseBytes(response, serviceId = 0x41, pid = 0x2F, expectedData = 1) ?: return
        val pct = bytes[0].toInt().and(0xFF) / 2.55f
        _fuelLevel.value = FuelLevel(
            percent         = pct.coerceIn(0f, 100f),
            estimatedLitres = (pct / 100f * TANK_LITRES).coerceIn(0f, TANK_LITRES)
        )
    }

    // ── ELM327 helpers ────────────────────────────────────────────────────────

    /** Mode 22 request returning a signed byte scaled to mg (injector correction). */
    private suspend fun mode22SignedByte(did: String): Float? {
        val bytes = mode22(did, 1) ?: return null
        return bytes[0].toFloat()   // already signed byte
    }

    /** Mode 22 request returning an unsigned byte scaled to percent [0–100]. */
    private suspend fun mode22U8Pct(did: String): Float? {
        val raw = mode22U8Raw(did) ?: return null
        return (raw / 255.0f * 100.0f).coerceIn(0f, 100f)
    }

    /** Mode 22 request returning raw unsigned byte. */
    private suspend fun mode22U8Raw(did: String): Int? {
        val bytes = mode22(did, 1) ?: return null
        return bytes[0].toInt().and(0xFF)
    }

    /** Mode 22 request returning a temperature in °C from unsigned 16-bit big-endian. */
    private suspend fun mode22U16(did: String): Float? {
        val raw = mode22U16Raw(did) ?: return null
        return raw.toFloat().coerceIn(-40f, 1000f)
    }

    private suspend fun mode22U16Raw(did: String): Int? {
        val bytes = mode22(did, 2) ?: return null
        return (bytes[0].toInt().and(0xFF) shl 8) or bytes[1].toInt().and(0xFF)
    }

    /**
     * Send a Mode 22 UDS request and return the data bytes (after the 62 XX XX header).
     * @param did            4-char hex DID, e.g. "F43C"
     * @param expectedData   Expected number of data bytes after the response header
     */
    private suspend fun mode22(did: String, expectedData: Int): ByteArray? {
        val cmd = "22${did.uppercase()}"
        val response = obdConnection.sendCommand(cmd) ?: return null
        val hi = did.substring(0, 2).toInt(16)
        val lo = did.substring(2, 4).toInt(16)
        return parseResponseBytes(response, serviceId = 0x62, pid = hi, pid2 = lo, expectedData = expectedData)
    }

    /**
     * Parse a hex-encoded ELM327 response string into a byte array, stripping
     * the service ID and PID bytes and returning only the payload.
     */
    private fun parseResponseBytes(
        response: String,
        serviceId: Int,
        pid: Int,
        pid2: Int? = null,
        expectedData: Int
    ): ByteArray? {
        return try {
            val tokens = response.replace("\r", " ").replace("\n", " ")
                .trim().split(Regex("\\s+"))
                .filter { it.isNotEmpty() && it != "NO" && it != "DATA" && it != "SEARCHING" }
            if (tokens.size < (if (pid2 != null) 3 else 2) + expectedData) return null
            val idx = tokens.indexOfFirst {
                it.toIntOrNull(16) == serviceId
            }
            if (idx < 0) return null
            val headerLen = if (pid2 != null) 3 else 2
            if (tokens.size < idx + headerLen + expectedData) return null
            val data = tokens.subList(idx + headerLen, idx + headerLen + expectedData)
            ByteArray(data.size) { data[it].toInt(16).toByte() }
        } catch (e: Exception) {
            Timber.v("VAGExtendedPIDEngine: parse error: $response → ${e.message}")
            null
        }
    }

    /** Reset diagnostics state (call on new session). */
    fun reset() {
        _diagnostics.value = TDIDiagnostics()
        _fuelLevel.value   = null
    }
}
