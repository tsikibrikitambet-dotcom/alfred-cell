package ee.alfred.cell

internal class CellScanGate {
    private var nextScanId = 0
    private var activeScanId: Int? = null

    val isScanning: Boolean
        get() = activeScanId != null

    fun begin(): Int? {
        if (isScanning) return null
        nextScanId = if (nextScanId == Int.MAX_VALUE) 1 else nextScanId + 1
        activeScanId = nextScanId
        return nextScanId
    }

    fun finish(scanId: Int): Boolean {
        if (activeScanId != scanId) return false
        activeScanId = null
        return true
    }

    fun invalidate() {
        activeScanId = null
    }
}

internal object SignalMetrics {
    fun strengthFraction(dbm: Int): Float = ((dbm + 120f) / 70f).coerceIn(0f, 1f)

    fun bars(dbm: Int): String {
        val filled = when {
            dbm >= -75 -> 5
            dbm >= -85 -> 4
            dbm >= -95 -> 3
            dbm >= -105 -> 2
            dbm >= -115 -> 1
            else -> 0
        }
        return "[${"|".repeat(filled)}${".".repeat(5 - filled)}]"
    }

    fun quality(dbm: Int): String = when {
        dbm >= -80 -> "SIGNAL QUALITY  ·  STRONG"
        dbm >= -90 -> "SIGNAL QUALITY  ·  GOOD"
        dbm >= -100 -> "SIGNAL QUALITY  ·  FAIR"
        dbm >= -110 -> "SIGNAL QUALITY  ·  WEAK"
        else -> "SIGNAL QUALITY  ·  MARGINAL"
    }

    fun gpsFixIsStale(fixTimeMs: Long, nowMs: Long, staleAfterMs: Long): Boolean =
        (nowMs - fixTimeMs).coerceAtLeast(0L) > staleAfterMs
}
