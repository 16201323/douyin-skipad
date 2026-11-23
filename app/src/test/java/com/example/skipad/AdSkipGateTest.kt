package com.example.skipad

import org.junit.Assert.*
import org.junit.Test

class AdSkipGateTest {
    private fun ad(window: Int = 1, level: AdConfidence = AdConfidence.HIGH) =
        AdObservation("douyin", window, true, level, "test")
    private fun clear(window: Int = 1) = ad(window, AdConfidence.NONE)

    @Test fun highEvidenceRequiresFresh120msConfirmation() {
        val gate = AdSkipGate()
        assertEquals(120L, gate.evaluate(ad(), 0).recheckMs)
        assertFalse(gate.evaluate(ad(), 119).send)
        assertTrue(gate.evaluate(ad(), 120).send)
    }

    @Test fun badgeRequires300msConfirmation() {
        val gate = AdSkipGate()
        assertEquals(300L, gate.evaluate(ad(level = AdConfidence.MEDIUM), 0).recheckMs)
        assertFalse(gate.evaluate(ad(level = AdConfidence.MEDIUM), 299).send)
        assertTrue(gate.evaluate(ad(level = AdConfidence.MEDIUM), 300).send)
    }

    @Test fun changingWindowsOrConfidenceRestartsConfirmation() {
        val gate = AdSkipGate()
        gate.evaluate(ad(), 0)
        assertFalse(gate.evaluate(ad(window = 2), 120).send)
        assertFalse(gate.evaluate(ad(window = 2, level = AdConfidence.MEDIUM), 240).send)
        assertTrue(gate.evaluate(ad(window = 2, level = AdConfidence.MEDIUM), 540).send)
    }

    @Test fun unknownWindowCancelsCandidate() {
        val gate = AdSkipGate()
        gate.evaluate(ad(), 0)
        gate.evaluate(null, 100)
        assertFalse(gate.evaluate(ad(), 200).send)
    }

    @Test fun weakEvidenceNeverSends() {
        val gate = AdSkipGate()
        assertFalse(gate.evaluate(ad(level = AdConfidence.WEAK), 0).send)
        assertFalse(gate.evaluate(ad(level = AdConfidence.WEAK), 10000).send)
    }

    @Test fun sameAdStaysLockedAcrossUnknownAndAppSwitch() {
        val gate = AdSkipGate()
        gate.markSent(ad(), 0)
        assertFalse(gate.evaluate(ad(), 10000).send)
        gate.evaluate(null, 11000)
        gate.evaluate(clear().copy(packageName = "other"), 12000)
        assertFalse(gate.evaluate(ad(), 20000).send)
    }

    @Test fun stableClearRearmsNextAdWithoutFiveSecondDelay() {
        val gate = AdSkipGate()
        gate.markSent(ad(), 0)
        gate.evaluate(clear(), 200)
        gate.evaluate(clear(), 1000)
        assertFalse(gate.evaluate(ad(), 1100).send)
        assertTrue(gate.evaluate(ad(), 1220).send)
    }

    @Test fun unknownBetweenClearSamplesRestartsClearTimer() {
        val gate = AdSkipGate()
        gate.markSent(ad(), 0)
        gate.evaluate(clear(), 200)
        gate.evaluate(null, 700)
        gate.evaluate(clear(), 1000)
        assertFalse(gate.evaluate(ad(), 1100).send)
        assertFalse(gate.evaluate(ad(), 1250).send)
    }

    @Test fun stableClearMustNotSpanDifferentWindows() {
        val gate = AdSkipGate()
        gate.markSent(ad(), 0)
        gate.evaluate(clear(window = 1), 200)
        gate.evaluate(clear(window = 2), 1100)
        assertFalse(gate.evaluate(ad(window = 2), 1200).send)
        assertFalse(gate.evaluate(ad(window = 2), 1320).send)
    }

    @Test fun nonPlayerPageCannotRearmAnAd() {
        val gate = AdSkipGate()
        gate.markSent(ad(), 0)
        gate.evaluate(clear().copy(inPlayer = false), 200)
        gate.evaluate(clear().copy(inPlayer = false), 2000)
        assertFalse(gate.evaluate(ad(), 3000).send)
    }
}
