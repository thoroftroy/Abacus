package dev.wonderdesk.abacus

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.*

class SoundLevelTest {
    private val s = SoundLevel(48000)

    /** The IEC 61672 A-weighting table, within the tolerance a class 1 meter is allowed near these points. */
    @Test fun weightingMatchesTheStandard() {
        assertEquals(0.0, s.weightingDb(1000.0), 0.05)
        assertEquals(-19.1, s.weightingDb(100.0), 0.5)
        assertEquals(-50.5, s.weightingDb(20.0), 1.0)
        assertEquals(-3.2, s.weightingDb(500.0), 0.3)
        assertEquals(1.2, s.weightingDb(2000.0), 0.3)
        assertEquals(-2.5, s.weightingDb(10000.0), 1.0)
    }

    /** A full-scale 1 kHz sine is -3 dBFS of power; the fast level and the average both settle there. */
    @Test fun sineLevel() {
        val m = SoundLevel(48000)
        val buf = ShortArray(48000) { (32767 * sin(2 * PI * 1000 * it / 48000.0)).toInt().toShort() }
        val fast = m.feed(buf, buf.size)
        assertEquals(-3.0, fast, 0.2)
        assertEquals(-3.0, m.average()!!, 0.3)
    }

    /** Low rumble counts far less than the same power at 1 kHz. */
    @Test fun rumbleIsDiscounted() {
        val m = SoundLevel(48000)
        val buf = ShortArray(96000) { (16000 * sin(2 * PI * 50 * it / 48000.0)).toInt().toShort() }
        val lvl = m.feed(buf, buf.size)
        assertEquals(-9.0 - 30.2, lvl, 1.5)
    }
}
