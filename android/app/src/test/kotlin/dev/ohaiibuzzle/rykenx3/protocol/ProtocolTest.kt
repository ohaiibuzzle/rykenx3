package dev.ohaiibuzzle.rykenx3.protocol

import dev.ohaiibuzzle.rykenx3.protocol.Ryken.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Vectors from PROTOCOL.md
class ProtocolTest {
    private fun bytes(hex: String): ByteArray =
        hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun heartbeatFrame() {
        assertEquals("aa060601a697", Ryken.buildFrame(Ryken.Cmd.HEARTBEAT, byteArrayOf(1)).hex())
    }

    @Test fun disconnectFrame() {
        assertEquals("aa06050104b9", Ryken.buildFrame(Ryken.Cmd.DISCONNECT, byteArrayOf(1)).hex())
    }

    @Test fun handshakeAndClaim() {
        val ts = bytes("1a 09 1b 02 00")
        assertEquals("aa0a001a091b0200fa5e", Ryken.buildFrame(Ryken.Cmd.HANDSHAKE, ts).hex())

        val rx = bytes("55 1a 00 00 20 98 7b d8 74 80 40 00 07 5c 14 16 25 06 03 d6 0d 7a 0f 8a 24 d1")
        val frame = Ryken.splitFrame(rx + ByteArray(38)) // padded like a 64-byte report
        assertNotNull(frame)
        assertEquals(Ryken.Cmd.HANDSHAKE, frame!!.cmd)
        val reply = Ryken.parseHandshake(frame.bytes, ts)
        assertTrue(reply.valid)
        assertFalse(reply.busy)
        assertEquals(0x0f8a, reply.signature)
        assertEquals("250603d6", reply.info.serial)
        assertEquals("V1.3", reply.info.hardware)
        assertEquals("V1.22", reply.info.firmware)

        assertEquals(
            "aa0cff72656164790f8af636",
            Ryken.buildFrame(Ryken.Cmd.CLAIM, Ryken.claimPayload(reply.signature)).hex(),
        )
        // a different challenge must not verify
        assertFalse(Ryken.parseHandshake(frame.bytes, bytes("1a 09 1b 02 01")).valid)
    }

    @Test fun idleMeasurement() {
        val rx = bytes("55 1b 20 00000000 00000000 0c86 0c19 0001 0000 2710 0000 0000 6286")
        val frame = Ryken.splitFrame(rx)!!
        assertEquals(Ryken.Cmd.MEASURE, frame.cmd)
        val s = Ryken.parseMeasurement(frame.bytes)
        assertEquals(0.0, s.voltage, 0.0)
        assertEquals(3.206, s.dplus, 1e-9)
        assertEquals(3.097, s.dminus, 1e-9)
        assertEquals(0.001, s.cc1, 1e-9)
        assertNull(s.temperature)
    }

    @Test fun rejectsBadCrcAndHeader() {
        val rx = bytes("55 1b 20 00000000 00000000 0c86 0c19 0001 0000 2710 0000 0000 6287")
        assertNull(Ryken.splitFrame(rx))
        rx[0] = 0xaa.toByte()
        assertNull(Ryken.splitFrame(rx))
    }
}
