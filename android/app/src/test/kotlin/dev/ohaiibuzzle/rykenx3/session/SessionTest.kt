package dev.ohaiibuzzle.rykenx3.session

import dev.ohaiibuzzle.rykenx3.protocol.Sample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SessionTest {
    private fun sample(v: Double, i: Double) =
        Sample(v, i, v * i, 0.6, 0.6, 0.4, 0.0, null, 0.0)

    @Test fun g6MatchesJsAndPython() {
        assertEquals("5.1", g6(5.1f.toDouble()))
        assertEquals("0", g6(0.0))
        assertEquals("0.000123457", g6(0.0001234567))
        assertEquals("123457000", g6(123456789.0))
        assertEquals("-1.5", g6(-1.5))
    }

    @Test fun clockFormat() {
        assertEquals("00:05", clock(5.9))
        assertEquals("01:02.5", clock(62.5, true))
        assertEquals("1:00:00", clock(3600.0))
    }

    @Test fun integratesAndSkipsGaps() {
        val f = File.createTempFile("ryken", ".csv")
        try {
            val s = Session(f)
            s.intervalS = 0.0
            s.reset(0.0, "test")
            s.recording = true
            // 10 W for 1 s in 10 ms steps, then a 5 s gap that must not count
            var t = 0.0
            repeat(101) { s.onSample(t, sample(5.0, 2.0)); t += 10.0 }
            s.onSample(t + 5000, sample(5.0, 2.0))
            assertEquals(10.0 / 3600, s.energy, 1e-9)
            assertEquals(2.0 / 3600, s.charge, 1e-9)
            assertEquals(1000.0, s.activeMs, 1e-9)
            assertEquals(10.0, s.avgPower, 1e-9)
            assertEquals(102L, s.rows)

            s.flush()
            val lines = f.readLines()
            assertEquals(Session.CSV_HEADER, lines[0])
            assertEquals(103, lines.size)
            val c = lines[2].split(",")
            assertEquals(13, c.size)
            assertEquals("0.010", c[1])
            assertEquals("10", c[4])
            assertEquals("", c[9])
            s.close()
        } finally {
            f.delete()
        }
    }

    @Test fun rowInterval() {
        val f = File.createTempFile("ryken", ".csv")
        try {
            val s = Session(f)
            s.intervalS = 0.2
            s.reset(0.0, "test")
            s.recording = true
            var t = 0.0
            repeat(167) { s.onSample(t, sample(5.0, 1.0)); t += 6.0 }
            assertEquals(5L, s.rows) // 0, 204, 408, 612, 816 ms
            s.close()
        } finally {
            f.delete()
        }
    }

    @Test fun pausedRecordingKeepsStats() {
        val f = File.createTempFile("ryken", ".csv")
        try {
            val s = Session(f)
            s.intervalS = 0.2
            s.reset(0.0, "test")
            assertEquals(false, s.recording) // a new session starts paused
            s.onSample(0.0, sample(5.0, 1.0))
            assertEquals(0L, s.rows)
            s.recording = true
            s.onSample(10.0, sample(5.0, 1.0))
            s.recording = false
            s.onSample(100.0, sample(5.0, 1.0))
            s.onSample(300.0, sample(5.0, 1.0))
            assertEquals(1L, s.rows)
            assertEquals(4L, s.frames)
            assertEquals(5.0 * 0.3 / 3600, s.energy, 1e-9)
            s.recording = true
            s.onSample(350.0, sample(5.0, 1.0)) // logged right away, not 200 ms after the last row
            assertEquals(2L, s.rows)
            s.reset(400.0, "test")
            assertEquals(false, s.recording)
            s.close()
        } finally {
            f.delete()
        }
    }
}
