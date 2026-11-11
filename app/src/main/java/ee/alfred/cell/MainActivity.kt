package ee.alfred.cell

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.Date
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class Cell(
    val radio: String,
    val mcc: Int?,
    val mnc: Int?,
    val area: Int,
    val id: Long,
    val signalDbm: Int?,
    val isRegistered: Boolean
)

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var networkReadout: TextView
    private lateinit var cellReadout: TextView
    private lateinit var signalReadout: TextView
    private lateinit var distanceReadout: TextView
    private lateinit var accuracyReadout: TextView
    private lateinit var telemetryReadout: TextView
    private lateinit var coordinateReadout: TextView
    private lateinit var gpsUtcReadout: TextView
    private lateinit var nearbyCells: LinearLayout
    private lateinit var radar: RadarView
    private val cellScanGate = CellScanGate()
    private var offlineMode = false
    private var lastScanAt: Long? = null
    private var pendingScanTimeout: Runnable? = null
    private var gpsTracking = false
    private val scanTimeoutHandler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private var lastGpsFix: Location? = null
    private val utcFormatter = java.text.SimpleDateFormat("HH:mm:ss 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val gpsFixFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcTicker = object : Runnable {
        override fun run() {
            updateUtcReadout()
            updateTelemetryReadout()
            updateGpsFreshness()
            scanTimeoutHandler.postDelayed(this, UTC_REFRESH_INTERVAL_MS)
        }
    }

    private val gpsListener = LocationListener { location ->
        showCoordinates(location)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        window.statusBarColor = 0xff100606.toInt()
        window.navigationBarColor = 0xff100606.toInt()
        buildScreen()
        if (hasPermissions()) {
            refreshCell()
        }
        else requestPermissions(
            requiredPermissions(),
            PERMISSION_REQUEST
        )
    }

    override fun onResume() {
        super.onResume()
        scanTimeoutHandler.removeCallbacks(utcTicker)
        scanTimeoutHandler.post(utcTicker)
        if (::radar.isInitialized) radar.setAnimating(true)
        if (hasPermissions() && ::coordinateReadout.isInitialized) {
            startGpsTracking()
            refreshCell()
        }
    }

    override fun onPause() {
        scanTimeoutHandler.removeCallbacks(utcTicker)
        invalidateCellScan()
        if (::radar.isInitialized) radar.setAnimating(false)
        if (::locationManager.isInitialized) {
            try {
                locationManager.removeUpdates(gpsListener)
            } catch (_: SecurityException) {
                updateStatus("LOCATION PERMISSION REVOKED")
            }
            gpsTracking = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        invalidateCellScan()
        scanTimeoutHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(0xff100606.toInt())
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xff100606.toInt())
            addView(page)
        }
        setContentView(scroll)

        page.addView(label("A L F R E D  //  FIELD TERMINAL", 11, 0xffff5555.toInt(), true))
        page.addView(label("CELL RADAR", 31, 0xffffdddd.toInt(), true))
        page.addView(label("GPS COORDINATES  ·  OFFLINE MODE", 10, 0xffb94a4a.toInt(), true))
        status = label("WAITING FOR PHONE PERMISSIONS", 11, 0xffff7777.toInt(), true).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = panelBackground(0xff210909.toInt(), 0xff872323.toInt())
        }
        page.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        telemetryReadout = label("LAST UPDATE  ·  WAITING FOR FIRST SCAN", 10, 0xffc57d7d.toInt(), true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = panelBackground(0xff180707.toInt(), 0xff5b2424.toInt())
        }
        page.addView(telemetryReadout, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })

        radar = RadarView(this)
        val radarFrame = LinearLayout(this).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = panelBackground(0xff1a0808.toInt(), 0xff602020.toInt())
            addView(radar, LinearLayout.LayoutParams(-1, dp(276)))
        }
        page.addView(radarFrame, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })

        distanceReadout = label("-- dBm", 38, 0xffff4545.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(1))
        }
        page.addView(distanceReadout)
        accuracyReadout = label("CELL SIGNAL STRENGTH", 10, 0xffb65d5d.toInt(), true).apply {
            gravity = Gravity.CENTER
        }
        page.addView(accuracyReadout)

        page.addView(section("CONNECTED CELL"))
        networkReadout = label("NETWORK · SEARCHING", 16, 0xffffdddd.toInt(), true)
        page.addView(networkReadout)
        cellReadout = label("MCC / MNC / CELL ID · --", 12, 0xffb98181.toInt(), false)
        page.addView(cellReadout)
        signalReadout = label("SIGNAL · --", 12, 0xffff5555.toInt(), true)
        page.addView(signalReadout)

        page.addView(section("GPS POSITION"))
        coordinateReadout = label("ACQUIRING GPS FIX...", 13, 0xffff7777.toInt(), true).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = panelBackground(0xff190707.toInt(), 0xff602020.toInt())
        }
        page.addView(coordinateReadout, LinearLayout.LayoutParams(-1, -2))
        gpsUtcReadout = label("UTC CLOCK  --:--:--\nGPS FIX  ·  WAITING", 18, 0xffff5555.toInt(), true).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = panelBackground(0xff210909.toInt(), 0xff872323.toInt())
        }
        page.addView(gpsUtcReadout, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        })
        updateUtcReadout()

        page.addView(section("NEARBY CELL SIGNALS"))
        nearbyCells = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(0xff1a0808.toInt(), 0xff602020.toInt())
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        page.addView(nearbyCells, LinearLayout.LayoutParams(-1, -2))
        setNearbyCells(emptyList())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("⟳  SCAN CELL") { refreshCell() }, weightParams())
        val offlineButton = button("●  OFFLINE")
        offlineButton.setOnClickListener {
            offlineMode = !offlineMode
            if (offlineMode) {
                invalidateCellScan()
                updateStatus("OFFLINE MODE · CELL SCAN PAUSED")
                networkReadout.text = "OFFLINE MODE"
                cellReadout.text = "SCAN PAUSED · LAST SIGNAL IS RETAINED"
                offlineButton.text = "◉  ONLINE"
                offlineButton.background = panelBackground(0xff1f0c0c.toInt(), 0xff7a3535.toInt())
            } else {
                updateStatus("OFFLINE MODE DISABLED · READY TO SCAN")
                offlineButton.text = "●  OFFLINE"
                offlineButton.background = panelBackground(0xff2a0d0d.toInt(), 0xff8e3a3a.toInt())
                refreshCell()
            }
        }
        actions.addView(offlineButton, weightParams().apply {
            marginStart = dp(8)
        })
        page.addView(actions, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(18)
        })
        page.addView(label("LOCAL TELEMETRY  //  NETWORK ACCESS DISABLED", 10, 0xffb94a4a.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        page.addView(section("ABOUT ALFRED CELL"))
        page.addView(label(
            "Offline GPS and cellular signal monitor. Alfred is my cat. GPS coordinates and UTC fix time come from the phone; cell data depends on device and carrier support.",
            11,
            0xffcc9292.toInt(),
            false
        ))
        page.addView(label("DEVELOPER  //  TAMB\u0045T SARAPU", 10, 0xffff7777.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        })
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create("monospace", if (bold) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = 0f
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun section(value: String) = label("//  $value  ─────────────────", 10, 0xffff5555.toInt(), true).apply {
        setPadding(0, dp(18), 0, dp(5))
    }

    private fun button(value: String, action: (() -> Unit)? = null) = Button(this).apply {
        text = value
        textSize = 11f
        isAllCaps = false
        letterSpacing = 0f
        minHeight = dp(42)
        typeface = Typeface.create("monospace", Typeface.BOLD)
        setTextColor(0xffffd0d0.toInt())
        backgroundTintList = null
        stateListAnimator = null
        background = panelBackground(0xff2a0d0d.toInt(), 0xff8e3a3a.toInt())
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isFocusable = true
        if (action != null) {
            setOnClickListener { action() }
        }
    }

    private fun panelBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(3).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, -1, 1f)

    private fun hasPermissions() = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions() = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (hasPermissions()) {
                startGpsTracking()
                refreshCell()
            } else {
                networkReadout.text = "PERMISSION REQUIRED"
                cellReadout.text = "ALLOW PRECISE LOCATION AND PHONE ACCESS"
                updateStatus("GRANT BOTH PERMISSIONS, THEN TAP SCAN CELL")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsTracking() {
        if (!hasPermissions() || gpsTracking) return
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsTracking = false
                coordinateReadout.text = "GPS IS OFF  //  ENABLE LOCATION IN SETTINGS"
                return
            }
            val lastFix = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastFix != null) showCoordinates(lastFix)
            else coordinateReadout.text = "ACQUIRING SATELLITES...  //  MOVE OUTDOORS"
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                GPS_UPDATE_INTERVAL_MS,
                GPS_MIN_DISTANCE_METERS,
                gpsListener,
                mainLooper
            )
            gpsTracking = true
        } catch (_: SecurityException) {
            gpsTracking = false
            coordinateReadout.text = "GPS PERMISSION REQUIRED"
        } catch (_: IllegalArgumentException) {
            gpsTracking = false
            coordinateReadout.text = "GPS PROVIDER UNAVAILABLE"
        }
    }

    private fun showCoordinates(location: Location) {
        runOnUiThread {
            lastGpsFix = Location(location)
            renderCoordinates(location)
            updateTelemetryReadout()
            updateUtcReadout()
        }
    }

    private fun renderCoordinates(location: Location) {
        val accuracy = if (location.hasAccuracy()) {
            String.format(Locale.US, "±%.0f M", location.accuracy)
        } else "ACCURACY UNKNOWN"
        val fixState = if (
            SignalMetrics.gpsFixIsStale(location.time, System.currentTimeMillis(), GPS_STALE_AFTER_MS)
        ) "GPS FIX STALE" else "GPS LOCKED"
        coordinateReadout.text = String.format(
            Locale.US,
            "LAT  %.6f° %s\nLON  %.6f° %s\nFIX  %s  //  %s",
            kotlin.math.abs(location.latitude),
            if (location.latitude >= 0) "N" else "S",
            kotlin.math.abs(location.longitude),
            if (location.longitude >= 0) "E" else "W",
            accuracy,
            fixState
        )
    }

    private fun updateGpsFreshness() {
        if (gpsTracking) lastGpsFix?.let(::renderCoordinates)
    }

    private fun updateUtcReadout() {
        if (!::gpsUtcReadout.isInitialized) return
        val fix = lastGpsFix
        gpsUtcReadout.text = buildString {
            append("UTC CLOCK  ")
            append(utcFormatter.format(Date()))
            append("\nGPS FIX  ")
            if (fix == null) {
                append("WAITING FOR SATELLITES")
            } else {
                append(gpsFixFormatter.format(Date(fix.time)))
                if (fix.hasAccuracy()) append(String.format(Locale.US, "  ±%.0f M", fix.accuracy))
            }
        }
    }

    private fun updateTelemetryReadout() {
        if (!::telemetryReadout.isInitialized) return
        val scanTime = lastScanAt
        val gpsFix = lastGpsFix
        val scanString = if (scanTime == null) {
            "LAST UPDATE  ·  WAITING FOR FIRST SCAN"
        } else {
            val deltaSeconds = ((System.currentTimeMillis() - scanTime).coerceAtLeast(0L) / 1000L)
            val elapsed = if (deltaSeconds < 60L) "$deltaSeconds s ago" else {
                val minutes = deltaSeconds / 60L
                if (minutes < 60L) "$minutes min ago" else "${minutes / 60L} h ago"
            }
            "LAST UPDATE  ·  ${utcFormatter.format(Date(scanTime))}  //  $elapsed"
        }
        val gpsString = if (gpsFix == null) "GPS  ·  WAITING" else {
            val gpsAgeSecs = ((System.currentTimeMillis() - gpsFix.time).coerceAtLeast(0L) / 1000L)
            val age = if (gpsAgeSecs < 60L) "$gpsAgeSecs s" else if (gpsAgeSecs < 3600L) "${gpsAgeSecs / 60L} min" else "${gpsAgeSecs / 3600L} h"
            "GPS  ·  $age ago"
        }
        telemetryReadout.text = "$scanString\n$gpsString"
    }

    private fun invalidateCellScan() {
        cellScanGate.invalidate()
        pendingScanTimeout?.let(scanTimeoutHandler::removeCallbacks)
        pendingScanTimeout = null
    }

    private fun finishCellScan(scanId: Int): Boolean {
        if (!cellScanGate.finish(scanId)) return false
        pendingScanTimeout?.let(scanTimeoutHandler::removeCallbacks)
        pendingScanTimeout = null
        return true
    }

    private fun clearCellSignal() {
        distanceReadout.text = "-- dBm"
        accuracyReadout.text = "CELL SIGNAL STRENGTH"
        signalReadout.text = "SIGNAL · --"
        radar.signalDbm = null
        radar.invalidate()
    }

    private fun clearCellReadings() {
        clearCellSignal()
        setNearbyCells(emptyList())
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun refreshCell() {
        if (offlineMode) {
            updateStatus("OFFLINE MODE · CELL SCAN PAUSED")
            networkReadout.text = "OFFLINE MODE"
            cellReadout.text = "ENABLE SCANNING TO REFRESH CELL DATA"
            return
        }
        if (!hasPermissions()) {
            invalidateCellScan()
            clearCellReadings()
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST)
            return
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!locationEnabled) {
            invalidateCellScan()
            clearCellReadings()
            updateStatus("LOCATION OFF · ENABLE IT FOR CELL AND GPS DATA")
            networkReadout.text = "LOCATION OFF"
            cellReadout.text = "ENABLE LOCATION TO SCAN CELL AND GPS SIGNALS"
            return
        }
        val manager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (manager.simState == TelephonyManager.SIM_STATE_ABSENT) {
            invalidateCellScan()
            clearCellReadings()
            networkReadout.text = "NO SIM CARD"
            cellReadout.text = "INSERT AN ACTIVE SIM AND RESCAN"
            updateStatus("CELL SCAN NEEDS AN ACTIVE SIM")
            return
        }
        val scanId = cellScanGate.begin() ?: return
        val scanStartedAt = SystemClock.elapsedRealtime()
        networkReadout.text = "CELL · SCANNING"
        cellReadout.text = "READING RADIO INFORMATION..."
        updateStatus("SCANNING · KEEP MOBILE SERVICE ENABLED")
        pendingScanTimeout = Runnable {
            if (finishCellScan(scanId)) {
                val cachedCells = readCachedCellInfo(manager) ?: return@Runnable
                if (cachedCells.isNotEmpty()) {
                    displayCellResult(cachedCells, scanStartedAt)
                    updateStatus("CACHED CELL DATA · LIVE SCAN TIMED OUT")
                } else {
                    clearCellReadings()
                    networkReadout.text = "SCAN DID NOT RESPOND"
                    cellReadout.text = "CHECK SIM, SIGNAL AND LOCATION, THEN RESCAN"
                    updateStatus("PHONE RADIO TIMED OUT · TAP SCAN CELL TO RETRY")
                }
            }
        }.also { scanTimeoutHandler.postDelayed(it, CELL_SCAN_TIMEOUT_MS) }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                manager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        if (!finishCellScan(scanId)) return
                        displayCellResult(cellInfo, scanStartedAt)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (!finishCellScan(scanId)) return
                        val cachedCells = readCachedCellInfo(manager) ?: return
                        if (cachedCells.isNotEmpty()) {
                            displayCellResult(cachedCells, scanStartedAt)
                            updateStatus("CACHED CELL DATA · LIVE SCAN ERROR $errorCode")
                        } else {
                            clearCellReadings()
                            networkReadout.text = "CELL SCAN FAILED"
                            cellReadout.text = "ANDROID ERROR $errorCode · ${detail?.javaClass?.simpleName ?: "NO DETAILS"}"
                            updateStatus("SCAN FAILED · CHECK PHONE RADIO AND TRY AGAIN")
                        }
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                if (finishCellScan(scanId)) {
                    displayCellResult(manager.allCellInfo, scanStartedAt)
                }
            }
        } catch (_: SecurityException) {
            finishCellScan(scanId)
            clearCellReadings()
            networkReadout.text = "PERMISSION REQUIRED"
            cellReadout.text = "ALLOW PRECISE LOCATION AND PHONE ACCESS"
            updateStatus("GRANT BOTH PERMISSIONS, THEN TAP SCAN CELL")
        } catch (error: RuntimeException) {
            finishCellScan(scanId)
            clearCellReadings()
            networkReadout.text = "CELL SCAN FAILED"
            cellReadout.text = "${error.javaClass.simpleName} · TAP SCAN CELL TO RETRY"
            updateStatus("CELL SCAN FAILED · ${error.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCachedCellInfo(manager: TelephonyManager): List<CellInfo>? {
        return try {
            manager.allCellInfo.orEmpty()
        } catch (_: SecurityException) {
            clearCellReadings()
            networkReadout.text = "PERMISSION REQUIRED"
            cellReadout.text = "ALLOW PHONE AND LOCATION ACCESS, THEN RESCAN"
            updateStatus("PERMISSION REVOKED WHILE READING CELL DATA")
            null
        } catch (error: RuntimeException) {
            clearCellReadings()
            networkReadout.text = "CELL DATA UNAVAILABLE"
            cellReadout.text = "${error.javaClass.simpleName} · TAP SCAN CELL TO RETRY"
            updateStatus("CACHED CELL READ FAILED · ${error.javaClass.simpleName}")
            null
        }
    }

    private fun displayCellResult(cellInfo: List<CellInfo>, scanStartedAt: Long) {
        val cells = cellInfo.mapNotNull { it.toCell() }
            .sortedWith(compareByDescending<Cell> { it.signalDbm ?: Int.MIN_VALUE })
        val cell = cells.firstOrNull { it.isRegistered }
        runOnUiThread {
            lastScanAt = System.currentTimeMillis()
            setNearbyCells(cells)
            updateTelemetryReadout()
            if (cell == null) {
                clearCellSignal()
                networkReadout.text = if (cells.isEmpty()) "NO CELL DATA" else "NO REGISTERED CELL"
                cellReadout.text = if (cells.isEmpty()) {
                    "CHECK SIM, AIRPLANE MODE AND MOBILE SERVICE"
                } else "NEIGHBOR SIGNALS DETECTED · WAIT FOR NETWORK REGISTRATION"
                val scanDuration = SystemClock.elapsedRealtime() - scanStartedAt
                updateStatus(if (cells.isEmpty()) {
                    if (scanDuration < FAST_EMPTY_SCAN_MS) {
                        "NO CELL DATA YET · WAIT A MOMENT AND RESCAN"
                    } else "NO CELL SERVICE · CHECK SIM, AIRPLANE MODE AND SIGNAL"
                } else "NEIGHBOR CELLS FOUND · NO ACTIVE CELL")
                return@runOnUiThread
            }
            networkReadout.text = "${cell.radio}  ·  REGISTERED"
            cellReadout.text = String.format(
                Locale.US,
                "MCC %s  /  MNC %s  /  AREA %d  /  ID %d",
                cell.mcc?.let { String.format(Locale.US, "%03d", it) } ?: "---",
                cell.mnc?.let { String.format(Locale.US, "%02d", it) } ?: "--",
                cell.area,
                cell.id
            )
            val signal = cell.signalDbm
            signalReadout.text = signal?.let { "SIGNAL  ·  $it dBm" } ?: "SIGNAL · UNAVAILABLE"
            distanceReadout.text = signal?.let { "$it dBm" } ?: "-- dBm"
            accuracyReadout.text = signal?.let(::signalQuality) ?: "SIGNAL READING UNAVAILABLE"
            radar.signalDbm = cell.signalDbm
            radar.invalidate()
            updateStatus("CELL FOUND · OFFLINE SCAN")
        }
    }

    private fun setNearbyCells(cells: List<Cell>) {
        if (!::nearbyCells.isInitialized) return
        nearbyCells.removeAllViews()
        if (cells.isEmpty()) {
            nearbyCells.addView(label("NO CELL READINGS YET", 11, 0xffb65d5d.toInt(), false))
            return
        }
        cells.forEachIndexed { index, cell ->
            val signal = cell.signalDbm
            val bars = signal?.let(::signalBars) ?: "....."
            val rowText = buildString {
                append(if (cell.isRegistered) "● " else "  ")
                append(String.format(Locale.US, "%02d %-4s", index + 1, cell.radio))
                append(" MCC ")
                append(cell.mcc?.toString() ?: "---")
                append(" MNC ")
                append(cell.mnc?.toString() ?: "--")
                append(" ID ")
                append(cell.id)
                append("  ")
                append(bars)
                append("  ")
                append(signal?.let { "$it dBm" } ?: "-- dBm")
            }
            nearbyCells.addView(
                label(rowText, 10, if (cell.isRegistered) 0xffff7777.toInt() else 0xffcc9292.toInt(), cell.isRegistered)
            )
        }
    }

    private fun signalBars(dbm: Int): String {
        return SignalMetrics.bars(dbm)
    }

    @SuppressLint("NewApi")
    private fun CellInfo.toCell(): Cell? {
        return when (this) {
            is CellInfoLte -> Cell("LTE", cellIdentity.mcc,
                cellIdentity.mnc, cellIdentity.tac, cellIdentity.ci.toLong(),
                cellSignalStrength.dbm.takeUnless { it == Int.MAX_VALUE }, isRegistered)
            is CellInfoGsm -> Cell("GSM", cellIdentity.mcc,
                cellIdentity.mnc, cellIdentity.lac, cellIdentity.cid.toLong(),
                cellSignalStrength.dbm.takeUnless { it == Int.MAX_VALUE }, isRegistered)
            is CellInfoWcdma -> Cell("UMTS", cellIdentity.mcc,
                cellIdentity.mnc, cellIdentity.lac, cellIdentity.cid.toLong(),
                cellSignalStrength.dbm.takeUnless { it == Int.MAX_VALUE }, isRegistered)
            is CellInfoNr -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) toNrCell(this) else null
            else -> null
        }
    }

    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.Q)
    private fun toNrCell(info: CellInfoNr): Cell {
        val identity = info.cellIdentity as android.telephony.CellIdentityNr
        return Cell(
            "NR",
            identity.mccString?.toIntOrNull(),
            identity.mncString?.toIntOrNull(),
            identity.tac,
            identity.nci,
            info.cellSignalStrength.dbm.takeUnless { it == Int.MAX_VALUE },
            info.isRegistered
        )
    }

    private fun signalQuality(dbm: Int) = SignalMetrics.quality(dbm)

    private fun updateStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class RadarView(context: Context) : View(context) {
        var signalDbm: Int? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sweepMatrix = Matrix()
        private var sweepGradient: SweepGradient? = null
        private var sweepAngle = 0f
        private var isAnimating = false

        fun setAnimating(animate: Boolean) {
            if (isAnimating == animate) return
            isAnimating = animate
            if (animate) invalidate() else removeCallbacks(animationFrame)
        }

        private val animationFrame = Runnable { invalidate() }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            sweepGradient = SweepGradient(
                width / 2f,
                height / 2f,
                intArrayOf(0x00ff2020, 0x55ff2020, 0x00ff2020, 0x00ff2020),
                floatArrayOf(0f, 0.025f, 0.22f, 1f)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width * 0.39f, height * 0.43f)
            canvas.drawColor(0xff130606.toInt())

            sweepMatrix.setRotate(sweepAngle, cx, cy)
            sweepGradient?.setLocalMatrix(sweepMatrix)
            paint.shader = sweepGradient
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = 0xff642323.toInt()
            for (factor in floatArrayOf(1f, 0.75f, 0.5f, 0.25f)) {
                canvas.drawCircle(cx, cy, radius * factor, paint)
            }
            paint.color = 0xff431717.toInt()
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)

            for (tick in 0 until 48) {
                val angle = Math.toRadians(tick * 7.5)
                val outer = radius - dp(2)
                val inner = outer - if (tick % 4 == 0) dp(10) else dp(4)
                paint.color = if (tick % 4 == 0) 0xffff7777.toInt() else 0xff773333.toInt()
                canvas.drawLine(
                    cx + cos(angle).toFloat() * inner,
                    cy + sin(angle).toFloat() * inner,
                    cx + cos(angle).toFloat() * outer,
                    cy + sin(angle).toFloat() * outer,
                    paint
                )
            }

            paint.style = Paint.Style.FILL
            paint.color = 0xffff4444.toInt()
            canvas.drawCircle(cx, cy, dp(6).toFloat(), paint)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(10).toFloat()
            paint.typeface = Typeface.create("monospace", Typeface.BOLD)
            paint.color = 0xffffb3b3.toInt()
            canvas.drawText("SIGNAL", cx, cy - radius + dp(22), paint)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("WEAK", cx - radius + dp(4), cy + radius - dp(5), paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("STRONG", cx + radius - dp(4), cy + radius - dp(5), paint)

            val gaugeRadius = radius * 0.55f
            val gaugeBounds = android.graphics.RectF(
                cx - gaugeRadius,
                cy - gaugeRadius,
                cx + gaugeRadius,
                cy + gaugeRadius
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(7).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xff4a2020.toInt()
            canvas.drawArc(gaugeBounds, 135f, 270f, false, paint)

            val signal = signalDbm
            if (signal != null) {
                val sweep = 270f * SignalMetrics.strengthFraction(signal)
                paint.color = 0xffff5555.toInt()
                canvas.drawArc(gaugeBounds, 135f, sweep, false, paint)
            }

            paint.style = Paint.Style.FILL
            paint.strokeCap = Paint.Cap.BUTT
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(if (signalDbm == null) 11 else 17).toFloat()
            paint.color = if (signalDbm == null) 0xffb65d5d.toInt() else 0xffffb3b3.toInt()
            canvas.drawText(signalDbm?.let { "$it dBm" } ?: "NO DATA", cx, cy + dp(5), paint)
            paint.textSize = dp(9).toFloat()
            paint.color = 0xffc57d7d.toInt()
            canvas.drawText("SIGNAL LEVEL", cx, cy + dp(22), paint)

            if (isAnimating && isShown && windowVisibility == VISIBLE) {
                sweepAngle = (sweepAngle + 2.5f) % 360f
                removeCallbacks(animationFrame)
                postDelayed(animationFrame, RADAR_FRAME_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 71
        private const val CELL_SCAN_TIMEOUT_MS = 15_000L
        private const val FAST_EMPTY_SCAN_MS = 2_000L
        private const val GPS_UPDATE_INTERVAL_MS = 1_000L
        private const val GPS_MIN_DISTANCE_METERS = 0f
        private const val GPS_STALE_AFTER_MS = 60_000L
        private const val RADAR_FRAME_INTERVAL_MS = 100L
        private const val UTC_REFRESH_INTERVAL_MS = 1_000L
    }
}