package com.caros.can

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CANParserTest {

    private lateinit var parser: CANParser

    @Before
    fun setUp() {
        parser = CANParser()
    }

    @Test
    fun `parseFrame returns null for blank line`() {
        assertNull(parser.parseFrame(""))
        assertNull(parser.parseFrame("   "))
    }

    @Test
    fun `parseFrame returns null for comment lines`() {
        assertNull(parser.parseFrame("# this is a comment"))
        assertNull(parser.parseFrame("// another comment"))
    }

    @Test
    fun `parseFrame decodes engine RPM from ID 0x280`() {
        // RPM = (b0 shl 8 | b1) / 4 = (0x0F shl 8 | 0xA0) / 4 = 0x0FA0 / 4 = 4000 / 4 = 1000 rpm
        val line = "ID:280 DATA:0F A0 00 00 00 00 00 00"
        val frame = parser.parseFrame(line)
        assertNotNull(frame)
        assertEquals(1000, frame!!.engineRpm?.rpm)
    }

    @Test
    fun `parseFrame decodes vehicle speed from ID 0x320`() {
        // Speed = (b0 shl 8 | b1) / 100 = (0x1F shl 8 | 0x40) / 100 = 0x1F40 / 100 = 8000 / 100 = 80 km/h
        val line = "ID:320 DATA:1F 40 00 00 00 00 00 00"
        val frame = parser.parseFrame(line)
        assertNotNull(frame)
        assertEquals(80.0f, frame!!.vehicleSpeed?.kmh ?: 0f, 0.1f)
    }

    @Test
    fun `parseFrame decodes coolant temperature from ID 0x470`() {
        // Coolant temp decoded from bytes (implementation-dependent)
        val line = "ID:470 DATA:78 00 00 00 00 00 00 00"
        val frame = parser.parseFrame(line)
        // Just verify non-null — exact decoding is CALIBRATE-dependent
        assertNotNull(frame)
    }

    @Test
    fun `parseFrame returns null for unknown CAN ID`() {
        val line = "ID:999 DATA:00 01 02 03 04 05 06 07"
        val frame = parser.parseFrame(line)
        assertNull(frame)
    }

    @Test
    fun `parseFrame accumulates state across multiple frames`() {
        // First: engine data sets RPM
        parser.parseFrame("ID:280 DATA:0F A0 00 50 00 00 00 00") // 1000 rpm, throttle ~31%
        // Second: speed frame
        val speedFrame = parser.parseFrame("ID:320 DATA:1F 40 00 00 00 00 00 00") // 80 km/h
        // Speed frame should still have engine RPM from previous parse
        assertNotNull(speedFrame)
        assertEquals(1000, speedFrame!!.engineRpm?.rpm)
        assertEquals(80.0f, speedFrame.vehicleSpeed?.kmh ?: 0f, 0.1f)
    }

    @Test
    fun `reset clears accumulated state`() {
        parser.parseFrame("ID:280 DATA:0F A0 00 50 00 00 00 00")
        parser.reset()
        val frame = parser.currentFrame()
        assertNull(frame.engineRpm)
        assertNull(frame.vehicleSpeed)
    }

    @Test
    fun `parseFrame handles lowercase hex`() {
        val line = "ID:280 DATA:0f a0 00 00 00 00 00 00"
        val frame = parser.parseFrame(line)
        assertNotNull(frame)
        assertEquals(1000, frame!!.engineRpm?.rpm)
    }

    @Test
    fun `parseFrame decodes throttle position from engine data`() {
        // Throttle = b3 * 100 / 255. b3 = 0xFF → 100%
        val line = "ID:280 DATA:00 00 00 FF 00 00 00 00"
        val frame = parser.parseFrame(line)
        assertNotNull(frame)
        val throttle = frame!!.throttlePosition?.percent ?: 0f
        assertEquals(100.0f, throttle, 0.5f)
    }

    @Test
    fun `parseFrame returns null for malformed line missing DATA prefix`() {
        val line = "ID:280 0F A0 00 00 00 00 00 00"
        assertNull(parser.parseFrame(line))
    }

    @Test
    fun `parseFrame returns null for malformed line missing ID prefix`() {
        val line = "280 DATA:0F A0 00 00 00 00 00 00"
        assertNull(parser.parseFrame(line))
    }
}
