package ee.alfred.cell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellLogicTest {
    @Test
    fun radarStrengthScaleClampsAndMapsSignalRange() {
        assertEquals(0f, SignalMetrics.strengthFraction(-120), 0f)
        assertEquals(0.5f, SignalMetrics.strengthFraction(-85), 0.001f)
        assertEquals(1f, SignalMetrics.strengthFraction(-50), 0f)
        assertEquals(0f, SignalMetrics.strengthFraction(-140), 0f)
        assertEquals(1f, SignalMetrics.strengthFraction(-30), 0f)
    }

    @Test
    fun radarStrengthAndBarsNeverDecreaseAsSignalImproves() {
        var previousStrength = SignalMetrics.strengthFraction(-140)
        var previousBars = SignalMetrics.bars(-140).count { it == '|' }

        for (dbm in -139..-30) {
            val strength = SignalMetrics.strengthFraction(dbm)
            val bars = SignalMetrics.bars(dbm).count { it == '|' }

            assertTrue("Radar strength decreased at $dbm dBm", strength >= previousStrength)
            assertTrue("Signal bars decreased at $dbm dBm", bars >= previousBars)
            assertTrue("Radar strength outside 0..1 at $dbm dBm", strength in 0f..1f)
            assertTrue("Signal bars outside 0..5 at $dbm dBm", bars in 0..5)
            previousStrength = strength
            previousBars = bars
        }
    }

    @Test
    fun scanGateAllowsOnlyOneActiveScan() {
        val gate = CellScanGate()

        val scanId = gate.begin()

        assertNotNull(scanId)
        assertTrue(gate.isScanning)
        assertNull(gate.begin())
        assertTrue(gate.finish(scanId!!))
        assertFalse(gate.isScanning)
        assertFalse(gate.finish(scanId))
    }

    @Test
    fun invalidatedScanCannotFinishOrSupersedeNewScan() {
        val gate = CellScanGate()
        val oldScanId = gate.begin()!!

        gate.invalidate()
        val newScanId = gate.begin()!!

        assertFalse(gate.finish(oldScanId))
        assertTrue(gate.isScanning)
        assertTrue(gate.finish(newScanId))
        assertFalse(gate.isScanning)
    }

    @Test
    fun scanGateRemainsConsistentAcrossLongSoak() {
        val gate = CellScanGate()

        repeat(250_000) { iteration ->
            val scanId = gate.begin()
            assertNotNull(scanId)
            assertTrue(gate.isScanning)
            assertNull(gate.begin())

            if (iteration % 3 == 0) {
                gate.invalidate()
                assertFalse(gate.finish(scanId!!))
            } else {
                assertTrue(gate.finish(scanId!!))
                assertFalse(gate.finish(scanId))
            }
            assertFalse("Scan gate remained active at iteration $iteration", gate.isScanning)
        }
    }

    @Test
    fun signalBarsUseExpectedThresholds() {
        val expected = mapOf(
            -116 to "[.....]",
            -115 to "[|....]",
            -105 to "[||...]",
            -95 to "[|||..]",
            -85 to "[||||.]",
            -75 to "[|||||]",
            -50 to "[|||||]"
        )

        expected.forEach { (dbm, bars) ->
            assertEquals("Unexpected bars at $dbm dBm", bars, SignalMetrics.bars(dbm))
        }
    }

    @Test
    fun signalQualityChangesAtDocumentedBoundaries() {
        assertEquals("SIGNAL QUALITY  ·  MARGINAL", SignalMetrics.quality(-111))
        assertEquals("SIGNAL QUALITY  ·  WEAK", SignalMetrics.quality(-110))
        assertEquals("SIGNAL QUALITY  ·  FAIR", SignalMetrics.quality(-100))
        assertEquals("SIGNAL QUALITY  ·  GOOD", SignalMetrics.quality(-90))
        assertEquals("SIGNAL QUALITY  ·  STRONG", SignalMetrics.quality(-80))
    }

    @Test
    fun gpsFixBecomesStaleOnlyAfterThreshold() {
        val fixTime = 100_000L
        val staleAfter = 60_000L

        assertFalse(SignalMetrics.gpsFixIsStale(fixTime, fixTime + staleAfter, staleAfter))
        assertTrue(SignalMetrics.gpsFixIsStale(fixTime, fixTime + staleAfter + 1, staleAfter))
        assertFalse(SignalMetrics.gpsFixIsStale(fixTime, fixTime - 1, staleAfter))
    }
}
