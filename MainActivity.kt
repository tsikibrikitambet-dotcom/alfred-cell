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
import java.util.concurrent.Executors
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
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var networkReadout: TextView
    private lateinit var cellReadout: TextView
    private lateinit var signalReadout: TextView
    private lateinit var distanceReadout: TextView
    private lateinit var accuracyReadout: TextView
    private lateinit var coordinateReadout: TextView
    private lateinit var gpsUtcReadout: TextView
    private lateinit var nearbyCells: LinearLayout
    private lateinit var radar: RadarView
    private var currentCell: Cell? = null
    private var scanInProgress = false
    private var scanSequence = 0
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
        if (hasPermissions() && ::coordinateReadout.isInitialized) startGpsTracking()
    }

    override fun onPause() {
        scanTimeoutHandler.removeCallbacks(utcTicker)
        if (::locationManager.isInitialized) {
            runCatching { locationManager.removeUpdates(gpsListener) }
        }
        super.onPause()
    }

    override fun onDestroy() {
        scanSequence++
        scanTimeoutHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
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
        actions.addView(button("●  OFFLINE") {
            updateStatus("OFFLINE MODE · CELL RADIO MUST BE CONNECTED")
        }, weightParams().apply {
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

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 11f
        isAllCaps = false
        letterSpacing = 0f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        setTextColor(0xffffb3b3.toInt())
        backgroundTintList = null
        stateListAnimator = null
        background = panelBackground(0xff210909.toInt(), 0xff782929.toInt())
        setOnClickListener { action() }
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
        if (!hasPermissions()) return
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
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
        } catch (_: SecurityException) {
            coordinateReadout.text = "GPS PERMISSION REQUIRED"
        } catch (_: IllegalArgumentException) {
            coordinateReadout.text = "GPS PROVIDER UNAVAILABLE"
        }
    }

    private fun showCoordinates(location: Location) {
        runOnUiThread {
            lastGpsFix = Location(location)
            val accuracy = if (location.hasAccuracy()) {
                String.format(Locale.US, "±%.0f M", location.accuracy)
            } else "ACCURACY UNKNOWN"
            coordinateReadout.text = String.format(
                Locale.US,
                "LAT  %.6f° %s\nLON  %.6f° %s\nFIX  %s  //  GPS LOCKED",
                kotlin.math.abs(location.latitude),
                if (location.latitude >= 0) "N" else "S",
                kotlin.math.abs(location.longitude),
                if (location.longitude >= 0) "E" else "W",
                accuracy
            )
            updateUtcReadout()
        }
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

    @SuppressLint("MissingPermission", "NewApi")
    private fun refreshCell() {
        if (!hasPermissions()) {
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
        if (!locationEnabled) updateStatus("LOCATION OFF · ENABLE IT FOR CELL AND GPS DATA")
        updateStatus("READING REGISTERED CELL")
        val manager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (manager.simState == TelephonyManager.SIM_STATE_ABSENT) {
            networkReadout.text = "NO SIM CARD"
            cellReadout.text = "INSERT AN ACTIVE SIM AND RESCAN"
            updateStatus("CELL SCAN NEEDS AN ACTIVE SIM")
            return
        }
        if (scanInProgress) return
        scanInProgress = true
        val scanId = ++scanSequence
        val scanStartedAt = SystemClock.elapsedRealtime()
        networkReadout.text = "CELL · SCANNING"
        cellReadout.text = "READING RADIO INFORMATION..."
        updateStatus("SCANNING · KEEP MOBILE SERVICE ENABLED")
        scanTimeoutHandler.postDelayed({
            if (scanInProgress && scanSequence == scanId) {
                val cachedCells = runCatching { manager.allCellInfo }.getOrNull().orEmpty()
                scanInProgress = false
                if (cachedCells.isNotEmpty()) {
                    displayCellResult(cachedCells, scanStartedAt)
                    updateStatus("CACHED CELL DATA · LIVE SCAN TIMED OUT")
                } else {
                    networkReadout.text = "SCAN DID NOT RESPOND"
                    cellReadout.text = "CHECK SIM, SIGNAL AND LOCATION, THEN RESCAN"
                    updateStatus("PHONE RADIO TIMED OUT · TAP SCAN CELL TO RETRY")
                }
            }
        }, CELL_SCAN_TIMEOUT_MS)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                manager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        if (scanSequence != scanId) return
                        scanInProgress = false
                        displayCellResult(cellInfo, scanStartedAt)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (scanSequence != scanId) return
                        val cachedCells = runCatching { manager.allCellInfo }.getOrNull().orEmpty()
                        scanInProgress = false
                        if (cachedCells.isNotEmpty()) {
                            displayCellResult(cachedCells, scanStartedAt)
                            updateStatus("CACHED CELL DATA · LIVE SCAN ERROR $errorCode")
                        } else {
                            networkReadout.text = "CELL SCAN FAILED"
                            cellReadout.text = "ANDROID ERROR $errorCode · ${detail?.javaClass?.simpleName ?: "NO DETAILS"}"
                            updateStatus("SCAN FAILED · CHECK PHONE RADIO AND TRY AGAIN")
                        }
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                displayCellResult(manager.allCellInfo, scanStartedAt)
            }
        } catch (_: SecurityException) {
            scanInProgress = false
            networkReadout.text = "PERMISSION REQUIRED"
            cellReadout.text = "ALLOW PRECISE LOCATION AND PHONE ACCESS"
            updateStatus("GRANT BOTH PERMISSIONS, THEN TAP SCAN CELL")
        } catch (error: RuntimeException) {
            scanInProgress = false
            networkReadout.text = "CELL SCAN FAILED"
            cellReadout.text = "${error.javaClass.simpleName} · TAP SCAN CELL TO RETRY"
            updateStatus("CELL SCAN FAILED · ${error.javaClass.simpleName}")
        }
    }

    private fun displayCellResult(cellInfo: List<CellInfo>, scanStartedAt: Long) {
        val cells = cellInfo.mapNotNull { it.toCell() }
            .sortedWith(compareByDescending<Cell> { it.signalDbm ?: Int.MIN_VALUE })
        val cell = cells.firstOrNull { it.isRegistered }
        runOnUiThread {
            currentCell = cell
            setNearbyCells(cells)
            if (cell == null) {
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
        val filled = ((dbm + 120) / 14).coerceIn(0, 5)
        return "[${"|".repeat(filled)}${".".repeat(5 - filled)}]"
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

    private fun signalQuality(dbm: Int) = when {
        dbm >= -80 -> "SIGNAL QUALITY  ·  EXCELLENT"
        dbm >= -95 -> "SIGNAL QUALITY  ·  GOOD"
        dbm >= -105 -> "SIGNAL QUALITY  ·  FAIR"
        else -> "SIGNAL QUALITY  ·  WEAK"
    }

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

            val signal = signalDbm
            if (signal != null) {
                val strength = ((signal + 120f) / 70f).coerceIn(0f, 1f)
                val distance = radius * (0.9f - strength * 0.66f)
                val x = cx + distance
                val y = cy
                paint.color = 0x66ff3333
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1).toFloat()
                canvas.drawLine(cx, cy, x, y, paint)
                paint.style = Paint.Style.FILL
                paint.color = 0xffff6666.toInt()
                canvas.drawCircle(x, y, dp(8).toFloat(), paint)
                paint.color = 0x66ff6666
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(x, y, dp(14).toFloat(), paint)
            }

            sweepAngle = (sweepAngle + 2.5f) % 360f
            if (isShown && windowVisibility == VISIBLE) {
                postInvalidateDelayed(100L)
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 71
        private const val CELL_SCAN_TIMEOUT_MS = 15_000L
        private const val FAST_EMPTY_SCAN_MS = 2_000L
        private const val GPS_UPDATE_INTERVAL_MS = 1_000L
        private const val GPS_MIN_DISTANCE_METERS = 0f
        private const val UTC_REFRESH_INTERVAL_MS = 1_000L
    }
}