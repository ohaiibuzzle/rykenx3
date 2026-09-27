// RYKEN RK-X3 wire format. See PROTOCOL.md at the repository root.
package dev.ohaiibuzzle.rykenx3.protocol

import java.nio.ByteBuffer
import java.time.LocalDateTime

object Ryken {
    const val VID = 0x2e3c
    const val PID = 0xaf03
    const val REPORT_SIZE = 64
    const val HEARTBEAT_MS = 1000L
    private const val TEMP_ABSENT = 10000

    object Cmd {
        const val HANDSHAKE = 0x00
        const val DISCONNECT = 0x05
        const val HEARTBEAT = 0x06
        const val CLAIM = 0xff
        const val MEASURE = 0x20
    }

    fun crc16(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var r = 0x0011
        for (k in from until to) {
            r = r xor ((bytes[k].toInt() and 0xff) shl 8)
            repeat(8) {
                r = if (r and 0x8000 != 0) (r shl 1) xor 0x2507 else r shl 1
                r = r and 0xffff
            }
        }
        return (r + 1) and 0xffff
    }

    fun buildFrame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val f = ByteArray(payload.size + 5)
        f[0] = 0xaa.toByte()
        f[1] = f.size.toByte()
        f[2] = cmd.toByte()
        payload.copyInto(f, 3)
        val c = crc16(f, 0, f.size - 2)
        f[f.size - 2] = (c shr 8).toByte()
        f[f.size - 1] = c.toByte()
        return f
    }

    class Frame(val cmd: Int, val bytes: ByteArray)

    /** Device frames start with 0x55; returns null if the report holds no valid frame. */
    fun splitFrame(report: ByteArray, length: Int = report.size): Frame? {
        if (length < 5 || report[0] != 0x55.toByte()) return null
        val n = report[1].toInt() and 0xff
        if (n < 5 || n > length) return null
        val crc = ((report[n - 2].toInt() and 0xff) shl 8) or (report[n - 1].toInt() and 0xff)
        if (crc16(report, 0, n - 2) != crc) return null
        return Frame(report[2].toInt() and 0xff, report.copyOf(n))
    }

    fun parseMeasurement(f: ByteArray): Sample {
        val b = ByteBuffer.wrap(f) // big-endian
        fun u16(at: Int) = b.getShort(at).toInt() and 0xffff
        val voltage = b.getFloat(3).toDouble()
        val current = b.getFloat(7).toDouble()
        val temp = u16(19)
        return Sample(
            voltage = voltage,
            current = current,
            power = voltage * current,
            dplus = u16(11) / 1000.0,
            dminus = u16(13) / 1000.0,
            cc1 = u16(15) / 1000.0,
            cc2 = u16(17) / 1000.0,
            temperature = if (temp == TEMP_ABSENT) null else temp,
            phonePower = u16(21) / 10.0,
        )
    }

    /** Local-time challenge for the handshake: YY MM DD hh mm. */
    fun timestamp(t: LocalDateTime): ByteArray = byteArrayOf(
        (t.year % 100).toByte(), t.monthValue.toByte(), t.dayOfMonth.toByte(),
        t.hour.toByte(), t.minute.toByte(),
    )

    /** Parses a 0x00 reply to [timestamp]; [HandshakeReply.valid] checks its signature. */
    fun parseHandshake(f: ByteArray, timestamp: ByteArray): HandshakeReply {
        val status = f[3].toInt() and 0xff
        val mcu = f.copyOfRange(4, 16)
        val sn = f.copyOfRange(16, 20)
        val sig = ((f[22].toInt() and 0xff) shl 8) or (f[23].toInt() and 0xff)
        val hw = f[20].toInt() and 0xff
        val fw = f[21].toInt() and 0xff
        val expected = crc16(byteArrayOf(status.toByte()) + mcu + sn + timestamp)
        return HandshakeReply(
            busy = status == 1,
            signature = sig,
            valid = sig == expected,
            info = DeviceInfo(
                serial = sn.hex(),
                mcuId = mcu.hex(),
                hardware = "V${hw / 10}.${hw % 10}",
                firmware = "V${fw / 100}.${(fw % 100).toString().padStart(2, '0')}",
            ),
        )
    }

    fun claimPayload(signature: Int): ByteArray =
        "ready".toByteArray(Charsets.US_ASCII) + byteArrayOf((signature shr 8).toByte(), signature.toByte())

    fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

data class Sample(
    val voltage: Double,
    val current: Double,
    val power: Double,
    val dplus: Double,
    val dminus: Double,
    val cc1: Double,
    val cc2: Double,
    val temperature: Int?,
    val phonePower: Double,
)

data class DeviceInfo(val serial: String, val mcuId: String, val hardware: String, val firmware: String)

class HandshakeReply(val busy: Boolean, val signature: Int, val valid: Boolean, val info: DeviceInfo)
