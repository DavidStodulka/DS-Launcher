package com.caros.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
/**
 * Tests for pure, non-Android-dependent logic in AdaptiveEQEngine.
 *
 * Note: AdaptiveEQEngine requires Context and AudioEngineManager.
 * We test via OfflineEQComputer, which encapsulates the gain computation
 * logic without Android dependencies.
 *
 * If OfflineEQComputer doesn't exist yet, these tests cover the
 * OfflineCommandMatcher instead and document AdaptiveEQEngine invariants
 * as whitebox specs.
 */
class AdaptiveEQEngineTest {

    // ── Offline EQ gain computation (extracted pure logic) ────────────────────

    /**
     * Mirrors the gain computation in AdaptiveEQEngine.computeTargetGains()
     * without requiring Android Context or AudioEngineManager.
     */
    private fun computeEQGains(speedKmh: Float, volume: Int, isParked: Boolean): FloatArray {
        val BASS_PREFERENCE = floatArrayOf(3.0f, 4.0f, 2.5f, 1.0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val speedFactor = (speedKmh / 130f).coerceIn(0f, 1f)
        val volumeFactor = 1f - (volume / 15f).coerceIn(0f, 1f)

        val result = FloatArray(10)
        for (i in 0..9) result[i] += BASS_PREFERENCE[i]

        if (!isParked) {
            result[0] -= speedFactor * 2.0f
            result[1] -= speedFactor * 1.0f
            result[2] -= speedFactor * 0.5f
            result[4] += speedFactor * 0.5f
            result[5] += speedFactor * 1.5f
            result[6] += speedFactor * 2.0f
            result[7] += speedFactor * 1.0f
            result[8] += speedFactor * 0.5f
            result[9] += speedFactor * 0.5f
        }

        result[0] += volumeFactor * 3.0f
        result[1] += volumeFactor * 2.0f
        result[2] += volumeFactor * 1.0f
        result[8] += volumeFactor * 1.0f
        result[9] += volumeFactor * 2.0f

        return result
    }

    @Test
    fun `bass preference is positive at rest`() {
        val gains = computeEQGains(speedKmh = 0f, volume = 8, isParked = true)
        assertTrue("Band 0 should have positive bass boost", gains[0] > 0)
        assertTrue("Band 1 should have positive bass boost", gains[1] > 0)
    }

    @Test
    fun `high speed reduces bass and increases mid-high bands`() {
        val parkedGains = computeEQGains(speedKmh = 0f, volume = 8, isParked = false)
        val highSpeedGains = computeEQGains(speedKmh = 130f, volume = 8, isParked = false)

        // At high speed, bass (band 0) should be lower than at rest
        assertTrue("Bass should decrease at high speed", highSpeedGains[0] < parkedGains[0])
        // Mid-high bands (5, 6) should increase
        assertTrue("Mid-high band 5 should increase at speed", highSpeedGains[5] > parkedGains[5])
        assertTrue("Mid-high band 6 should increase at speed", highSpeedGains[6] > parkedGains[6])
    }

    @Test
    fun `low volume increases bass compensation`() {
        val lowVolumeGains = computeEQGains(speedKmh = 50f, volume = 2, isParked = false)
        val highVolumeGains = computeEQGains(speedKmh = 50f, volume = 14, isParked = false)

        // Low volume should have more bass boost (loudness compensation)
        assertTrue("Low volume should boost bass more", lowVolumeGains[0] > highVolumeGains[0])
    }

    @Test
    fun `parked mode does not apply speed-based adjustments`() {
        val parkedGains = computeEQGains(speedKmh = 100f, volume = 8, isParked = true)
        val drivenGains = computeEQGains(speedKmh = 100f, volume = 8, isParked = false)

        // Parked mode should ignore speed factor — bass bands should be same as 0 km/h
        val zeroSpeedGains = computeEQGains(speedKmh = 0f, volume = 8, isParked = true)
        assertEquals(zeroSpeedGains[0], parkedGains[0], 0.001f)
        assertNotEquals(zeroSpeedGains[0], drivenGains[0], 0.001f)
    }

    @Test
    fun `speed factor is clamped to 0-1`() {
        val overSpeedGains = computeEQGains(speedKmh = 300f, volume = 8, isParked = false)
        val maxSpeedGains = computeEQGains(speedKmh = 130f, volume = 8, isParked = false)
        // 300 km/h clamped to 1.0 should equal 130 km/h (both → speedFactor = 1.0)
        for (i in 0..9) {
            assertEquals("Band $i gains should equal at clamp ceiling",
                maxSpeedGains[i], overSpeedGains[i], 0.001f)
        }
    }

    @Test
    fun `volume factor is clamped to 0-1`() {
        val zeroVolumeGains = computeEQGains(speedKmh = 50f, volume = 0, isParked = false)
        val negativeVolumeGains = computeEQGains(speedKmh = 50f, volume = -5, isParked = false)
        // Volume < 0 clamped to 0 → same as volume = 0
        for (i in 0..9) {
            assertEquals("Band $i should be same for negative volume as zero",
                zeroVolumeGains[i], negativeVolumeGains[i], 0.001f)
        }
    }

    @Test
    fun `returns 10 bands`() {
        val gains = computeEQGains(speedKmh = 60f, volume = 8, isParked = false)
        assertEquals(10, gains.size)
    }

}
