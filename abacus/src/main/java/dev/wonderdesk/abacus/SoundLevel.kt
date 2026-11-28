package dev.wonderdesk.abacus

import kotlin.math.*

/**
 * Sound level the way a real sound level meter works it out: A-weighting (the ear hears low rumble and very high hiss much less, so
 * meters count them less), "fast" time weighting (a 125 ms running average of the sound's power), and an average over the whole
 * session that averages the power rather than the decibels. The old meter skipped all three, so fans, wind and handling noise
 * pushed it far too high, and its average was wrong.
 */
class SoundLevel(private val rate: Int) {
    /** One second-order filter section (a biquad), Direct Form II transposed. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0; var z2 = 0.0
        fun step(x: Double): Double { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y }
        /** Complex gain at angular frequency [w] (radians per sample), as magnitude. */
        fun gain(w: Double): Double {
            fun mag(c0: Double, c1: Double, c2: Double): Double {
                val re = c0 + c1 * cos(-w) + c2 * cos(-2 * w); val im = c1 * sin(-w) + c2 * sin(-2 * w); return hypot(re, im)
            }
            return mag(b0, b1, b2) / mag(1.0, a1, a2)
        }
    }

    private val sections: List<Biquad>
    private val norm: Double

    init {
        // The analogue A-weighting curve (IEC 61672) has poles at these four frequencies; each is pre-warped and moved to the
        // digital domain with the bilinear transform. Zeros: four at 0 Hz (z = 1) and the two left over at Nyquist (z = -1).
        val t = 1.0 / rate
        fun pole(f: Double): Double { val w = 2.0 / t * tan(PI * f * t); return (2.0 / t - w) / (2.0 / t + w) }
        val p1 = pole(20.598997); val p2 = pole(107.65265); val p3 = pole(737.86223); val p4 = pole(12194.217)
        sections = listOf(
            Biquad(1.0, -2.0, 1.0, -2 * p1, p1 * p1),
            Biquad(1.0, -2.0, 1.0, -(p2 + p3), p2 * p3),
            Biquad(1.0, 2.0, 1.0, -2 * p4, p4 * p4),
        )
        val w = 2 * PI * 1000.0 / rate
        norm = 1.0 / sections.fold(1.0) { g, s -> g * s.gain(w) }
    }

    /** The filter's effect at [hz] in dB (0 at 1 kHz). For the tests. */
    fun weightingDb(hz: Double): Double { val w = 2 * PI * hz / rate; return 20 * log10(norm * sections.fold(1.0) { g, s -> g * s.gain(w) }) }

    private val fastCoef = 1.0 - exp(-1.0 / (rate * .125))
    private var meanSquare = 0.0
    private var energy = 0.0
    private var samples = 0L

    /** Feeds [n] 16-bit samples and returns the fast A-weighted level in dB below full scale. */
    fun feed(buf: ShortArray, n: Int): Double {
        for (i in 0 until n) {
            var x = buf[i] / 32768.0 * norm
            for (s in sections) x = s.step(x)
            val sq = x * x
            meanSquare += (sq - meanSquare) * fastCoef
            energy += sq
        }
        samples += n
        return dbfs(meanSquare)
    }

    /** The equivalent continuous level (Leq) since the last [reset], in dB below full scale. */
    fun average(): Double? = if (samples < rate / 4) null else dbfs(energy / samples)

    fun reset() { energy = 0.0; samples = 0 }

    private fun dbfs(ms: Double) = 10 * log10(ms.coerceAtLeast(1e-13))

    companion object {
        /**
         * Android's compatibility rules say what level each microphone input gives for a 1 kHz tone of known loudness, so a full-scale
         * offset turns dBFS into dB SPL. Unprocessed: 94 dB gives -36 dBFS (RMS 520). Voice recognition: 90 dB gives RMS 2500 (-22.4 dBFS).
         * Real phones stray from these by a few dB, which is what the calibration setting is for.
         */
        const val OFFSET_UNPROCESSED = 130.0
        const val OFFSET_VOICE = 112.4
    }
}
