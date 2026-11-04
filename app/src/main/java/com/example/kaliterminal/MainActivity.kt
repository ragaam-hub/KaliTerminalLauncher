package com.example.kaliterminal

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

class Stats {
    var ramPercent = 0
    var ramUsedGb = 0f
    var ramTotalGb = 0f
    var cpuPercent = -1
    var cores = Runtime.getRuntime().availableProcessors()
    var batPercent = 0
    var charging = false
    var tempC = 0f
    var remainLabel = "SISA BATERAI"
    var remainText = "--"
    var signalKind = "SINYAL"
    var signalText = "--"
    var signalLevel = 0
    var clock = "--:--"
    var date = ""
}

// ---------------------------------------------------------------------------
// System monitor: RAM, CPU, battery, temperature, signal, clock
// ---------------------------------------------------------------------------

class SystemMonitor(private val ctx: Context) {
    private var lastTotal = 0L
    private var lastIdle = 0L
    private val samples = mutableListOf<Long>()
    private var lastCharging: Boolean? = null
    private var refLevel = -1
    private var refTime = 0L

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())

    fun read(): Stats {
        val s = Stats()
        val now = Date()
        s.clock = timeFmt.format(now)
        s.date = dateFmt.format(now)
        readRam(s)
        s.cpuPercent = cpuFromProcStat()
        if (s.cpuPercent < 0) s.cpuPercent = cpuFromFreq()
        readBattery(s)
        readSignal(s)
        return s
    }

    private fun readRam(s: Stats) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val total = mi.totalMem.toDouble()
            val used = total - mi.availMem.toDouble()
            if (total > 0) {
                s.ramPercent = ((used / total) * 100.0).toInt().coerceIn(0, 100)
                s.ramUsedGb = (used / 1073741824.0).toFloat()
                s.ramTotalGb = (total / 1073741824.0).toFloat()
            }
        } catch (e: Exception) {
        }
    }

    private fun cpuFromProcStat(): Int {
        try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return -1
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 8 || p[0] != "cpu") return -1
            val v = p.drop(1).take(8).map { it.toLong() }
            val idle = v[3] + v[4]
            val total = v.sum()
            val dT = total - lastTotal
            val dI = idle - lastIdle
            val first = lastTotal == 0L
            lastTotal = total
            lastIdle = idle
            if (first || dT <= 0) return -1
            return (((dT - dI) * 100.0) / dT).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            return -1
        }
    }

    private fun readLong(path: String): Long {
        return try {
            File(path).readText().trim().toLong()
        } catch (e: Exception) {
            -1L
        }
    }

    // Fallback: average CPU clock relative to max clock (approximation of load)
    private fun cpuFromFreq(): Int {
        try {
            var sum = 0.0
            var n = 0
            for (i in 0 until Runtime.getRuntime().availableProcessors()) {
                val base = "/sys/devices/system/cpu/cpu$i/cpufreq/"
                val cur = readLong(base + "scaling_cur_freq")
                val mx = readLong(base + "cpuinfo_max_freq")
                if (cur > 0 && mx > 0) {
                    sum += cur.toDouble() / mx.toDouble()
                    n++
                }
            }
            return if (n > 0) ((sum / n) * 100.0).toInt().coerceIn(0, 100) else -1
        } catch (e: Exception) {
            return -1
        }
    }

    private fun readBattery(s: Stats) {
        try {
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            s.batPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
            s.charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            s.tempC = temp / 10f

            if (lastCharging != s.charging) {
                samples.clear()
                refLevel = -1
                lastCharging = s.charging
            }
            s.remainLabel = if (s.charging) "PENUH DALAM" else "SISA BATERAI"
            if (s.batPercent >= 100 && s.charging) {
                s.remainText = "Penuh"
            } else {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val ms = estimateRemainMs(s.batPercent, s.charging, bm)
                s.remainText = if (ms > 0) fmt(ms) else "Menghitung..."
            }
        } catch (e: Exception) {
        }
    }

    private fun estimateRemainMs(level: Int, charging: Boolean, bm: BatteryManager): Long {
        // 1) System estimate when charging (Android 9+)
        if (charging && Build.VERSION.SDK_INT >= 28) {
            val t = bm.computeChargeTimeRemaining()
            if (t > 0) return t
        }
        // 2) Estimate from current draw and remaining charge
        val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        var cur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (cur != Long.MIN_VALUE && counter != Long.MIN_VALUE && counter > 0L) {
            cur = abs(cur)
            if (cur > 0L && cur < 10000L) cur *= 1000L // some devices report mA instead of uA
            if (cur > 0L) {
                samples.add(cur)
                if (samples.size > 20) samples.removeAt(0)
                val avg = samples.average()
                val targetUah = if (charging) {
                    val lv = if (level < 1) 1 else level
                    val full = counter.toDouble() * 100.0 / lv.toDouble()
                    full - counter
                } else {
                    counter.toDouble()
                }
                val hours = targetUah / avg
                if (hours > 0.0 && hours < 100.0) return (hours * 3600000.0).toLong()
            }
        }
        // 3) Fallback: observed level change over time
        val now = System.currentTimeMillis()
        if (refLevel < 0) {
            refLevel = level
            refTime = now
            return -1L
        }
        val dl = abs(refLevel - level)
        val dt = now - refTime
        if (dl >= 1 && dt > 0L) {
            val rate = dl.toDouble() / dt.toDouble()
            val target = if (charging) 100 - level else level
            return (target / rate).toLong()
        }
        return -1L
    }

    private fun fmt(ms: Long): String {
        val min = (ms / 60000L).coerceAtLeast(1L)
        val h = min / 60L
        val m = min % 60L
        return if (h > 0L) "$h jam $m mnt" else "$m mnt"
    }

    @Suppress("DEPRECATION")
    private fun readSignal(s: Stats) {
        // Wi-Fi first (if it is the active network)
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val rssi = wm.connectionInfo.rssi
                s.signalKind = "WIFI"
                s.signalLevel = WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
                s.signalText = "$rssi dBm"
                return
            }
        } catch (e: Exception) {
        }
        // Cellular
        s.signalKind = "SELULER"
        try {
            if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                s.signalText = "Butuh izin"
                s.signalLevel = 0
                return
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val ss = tm.signalStrength
                if (ss != null) {
                    val lvl = ss.level.coerceIn(0, 4)
                    s.signalLevel = lvl
                    var dbm = Int.MAX_VALUE
                    if (Build.VERSION.SDK_INT >= 29) {
                        val list = ss.cellSignalStrengths
                        if (list.isNotEmpty()) dbm = list[0].dbm
                    }
                    val names = arrayOf("Tak ada", "Lemah", "Sedang", "Baik", "Kuat")
                    s.signalText = if (dbm != Int.MAX_VALUE && dbm < 0) "$dbm dBm" else names[lvl]
                    return
                }
            }
            s.signalText = "--"
        } catch (e: Exception) {
            s.signalText = "--"
        }
    }
}

// ---------------------------------------------------------------------------
// Custom views
// ---------------------------------------------------------------------------

class RingView(ctx: Context) : View(ctx) {
    var name: String = ""
    var trackColor: Int = Color.argb(45, 57, 255, 136)
    var subColor: Int = Color.rgb(139, 190, 157)
    private var centerText = "--"
    private var shown = 0f
    private var ringColor = Color.rgb(57, 255, 136)
    private var anim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(45, 57, 255, 136)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val oval = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setValue(percent: Int, text: String, c: Int) {
        centerText = text
        ringColor = c
        val target = percent.coerceIn(0, 100).toFloat()
        if (abs(shown - target) < 0.5f) {
            shown = target
            invalidate()
            return
        }
        anim?.cancel()
        val a = ValueAnimator.ofFloat(shown, target)
        a.duration = 500
        a.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val stroke = w * 0.085f
        track.color = trackColor
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        arc.color = ringColor
        arc.setShadowLayer(w * 0.04f, 0f, 0f, ringColor)
        val pad = stroke * 0.5f + w * 0.05f
        oval.set(pad, pad, w - pad, w - pad)
        canvas.drawOval(oval, track)
        if (shown >= 0.5f) canvas.drawArc(oval, -90f, 360f * shown / 100f, false, arc)
        val cy = w / 2f
        txt.color = ringColor
        txt.textSize = w * 0.25f
        canvas.drawText(centerText, w / 2f, cy - (txt.ascent() + txt.descent()) / 2f - w * 0.05f, txt)
        sub.color = subColor
        sub.textSize = w * 0.11f
        canvas.drawText(name, w / 2f, cy + w * 0.21f, sub)
    }
}

class BarsView(ctx: Context) : View(ctx) {
    var level: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var onColor: Int = Color.rgb(57, 255, 136)
    var offColor: Int = Color.argb(70, 57, 255, 136)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.08f
        val bw = (w - gap * 3f) / 4f
        for (i in 0 until 4) {
            val bh = h * (i + 1) / 4f
            val left = i * (bw + gap)
            r.set(left, h - bh, left + bw, h)
            p.color = if (i < level) onColor else offColor
            canvas.drawRoundRect(r, bw / 3f, bw / 3f, p)
        }
    }
}

class GearView(ctx: Context) : View(ctx) {
    var tint: Int = Color.rgb(57, 255, 136)
        set(value) {
            field = value
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = s * 0.28f
        p.color = tint
        p.strokeWidth = s * 0.09f
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawCircle(cx, cy, r * 0.38f, p)
        p.strokeWidth = s * 0.12f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val c = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            canvas.drawLine(cx + c * r * 1.18f, cy + sn * r * 1.18f, cx + c * r * 1.52f, cy + sn * r * 1.52f, p)
        }
    }
}

class TouchBlocker(ctx: Context) : FrameLayout(ctx) {
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : Activity() {
    private val defaultAccent = Color.rgb(57, 255, 136)
    private var accent = Color.rgb(57, 255, 136)
    private var bg = Color.rgb(5, 10, 7)
    private var panel = Color.argb(235, 12, 25, 17)
    private var panelSolid = Color.rgb(12, 25, 17)
    private var muted = Color.rgb(139, 190, 157)
    private var dim = Color.rgb(24, 75, 45)
    private var tileBorder = Color.rgb(22, 55, 34)
    private val amber = Color.rgb(255, 196, 0)
    private val red = Color.rgb(255, 80, 80)
    private val cyan = Color.rgb(0, 220, 255)

    private class AppEntry(val label: String, val pkg: String, val icon: Drawable)

    private var apps: List<AppEntry> = emptyList()
    private lateinit var monitor: SystemMonitor
    private val handler = Handler(Looper.getMainLooper())
    private var homeUi: HomeUi? = null
    private var previewUi: HomeUi? = null
    private var mode = 0 // 0 = preview, 1 = home, 2 = settings

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPrefs()
        applyTheme(accent)
        monitor = SystemMonitor(applicationContext)
        askPhonePermissionOnce()
        val fromHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        if (fromHome || isDefaultHome()) showHome() else showPreview()
        registerPackageReceiver()
        loadApps()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) {
            when (mode) {
                0 -> showHome()
                2 -> showHome(true)
                else -> homeUi?.scroll?.smoothScrollTo(0, 0)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (appsDirty) loadApps()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            pkgReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
        }
        pkgReceiver = null
    }

    override fun onBackPressed() {
        if (mode == 2) {
            showHome(true)
            return
        }
        if (mode == 1) {
            if (!isDefaultHome()) {
                leaveHome()
                showPreview()
            }
            return // a home screen never closes with Back
        }
        super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME) {
            if (!isDefaultHome()) {
                Toast.makeText(
                    this,
                    "Belum jadi layar utama default. Launcher tetap bisa dipakai di sini.",
                    Toast.LENGTH_LONG
                ).show()
            }
            showHome()
        }
    }

    // ---- permissions -------------------------------------------------------

    private fun askPhonePermissionOnce() {
        try {
            val prefs = getSharedPreferences("kali", Context.MODE_PRIVATE)
            if (prefs.getBoolean("asked", false)) return
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PERM)
            }
        } catch (e: Exception) {
        }
    }

    // ---- default home handling --------------------------------------------

    private fun isDefaultHome(): Boolean {
        return try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
            ri?.activityInfo?.packageName == packageName
        } catch (e: Exception) {
            false
        }
    }

    private fun requestHome() {
        if (isDefaultHome()) {
            showHome()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME)
                    return
                }
            }
            openHomeSettings()
        } catch (e: Exception) {
            openHomeSettings()
        }
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Buka Pengaturan > Aplikasi > Aplikasi default > Layar utama", Toast.LENGTH_LONG).show()
            }
        }
        showHome()
    }

    // ---- screens -----------------------------------------------------------

    private fun showHome(restoreScroll: Boolean = false) {
        mode = 1
        previewUi = null
        val ui = HomeUi(true)
        homeUi = ui
        setContentView(ui.root)
        val saved = savedLog
        if (saved != null) {
            ui.setLog(saved)
        } else {
            ui.log("Kali Terminal Launcher siap.")
            ui.log("Ketik 'help' untuk melihat perintah.")
        }
        ui.renderApps()
        tick()
        if (restoreScroll) {
            val y = savedScroll
            ui.scroll.post { ui.scroll.scrollTo(0, y) }
        }
    }

    private fun leaveHome() {
        homeUi?.let {
            savedLog = it.logText()
            savedScroll = it.scroll.scrollY
        }
    }

    private fun rebuildHome() {
        leaveHome()
        showHome(true)
    }

    private fun openSettings() {
        leaveHome()
        showSettings(false)
    }

    private fun showPreview() {
        mode = 0
        homeUi = null
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        var s = 0.62f
        val maxH = h - dp(260)
        if ((h.toFloat() * s) > maxH.toFloat()) s = maxH.toFloat() / h.toFloat()

        val ui = HomeUi(false)
        previewUi = ui
        val inner = ui.root
        inner.layoutParams = FrameLayout.LayoutParams(w, h)
        inner.pivotX = 0f
        inner.pivotY = 0f
        inner.scaleX = s
        inner.scaleY = s

        val clip = TouchBlocker(this)
        clip.addView(inner)
        clip.clipToOutline = true
        clip.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
            }
        }

        val frame = FrameLayout(this)
        frame.setPadding(dp(4), dp(4), dp(4), dp(4))
        frame.background = rounded(Color.BLACK, accent, 2, 24)
        frame.addView(clip, FrameLayout.LayoutParams((w * s).toInt(), (h * s).toInt()))

        val title = TextView(this).apply {
            text = "[ PREVIEW ]"
            setTextColor(accent)
            textSize = 15f
            letterSpacing = 0.2f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(14f, 0f, 0f, accent)
        }
        val subtitle = TextView(this).apply {
            text = "Tampilan saat dijadikan layar utama (Home)"
            setTextColor(muted)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        val button = TextView(this).apply {
            text = "▶  JALANKAN"
            setTextColor(bg)
            textSize = 17f
            letterSpacing = 0.12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(accent, accent, 1, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { requestHome() }
        }
        val hint = TextView(this).apply {
            text = "Akan meminta izin menjadikan aplikasi ini layar utama"
            setTextColor(mix(Color.BLACK, accent, 0.5f))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(30), dp(20), dp(20))
            background = bgGradient()
        }
        page.addView(title)
        page.addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        page.addView(frame, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        page.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        page.addView(button, LinearLayout.LayoutParams(-1, dp(56)))
        page.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        setContentView(page)
        ui.renderApps()
        tick()
    }

    // ---- data --------------------------------------------------------------

    private fun tick() {
        if (mode == 2) return
        val st = monitor.read()
        homeUi?.update(st)
        previewUi?.update(st)
    }

    private var appsLoaded = false
    private var appsDirty = false
    private var loading = false
    private var pkgReceiver: BroadcastReceiver? = null

    // Loads the app list (labels + icons) on a background thread so the UI opens instantly.
    private fun loadApps() {
        if (loading) return
        loading = true
        appsDirty = false
        val pm = packageManager
        val self = packageName
        Thread {
            var list: List<AppEntry> = emptyList()
            try {
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                list = pm.queryIntentActivities(i, 0)
                    .filter { it.activityInfo.packageName != self }
                    .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
                    .sortedBy { it.label.lowercase(Locale.getDefault()) }
            } catch (e: Exception) {
            }
            runOnUiThread {
                loading = false
                apps = list
                appsLoaded = true
                homeUi?.renderApps()
                previewUi?.renderApps()
                if (appsDirty) loadApps()
            }
        }.start()
    }

    // Refresh the list only when an app is installed / removed / updated.
    private fun registerPackageReceiver() {
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    appsDirty = true
                    loadApps()
                }
            }
            val f = IntentFilter()
            f.addAction(Intent.ACTION_PACKAGE_ADDED)
            f.addAction(Intent.ACTION_PACKAGE_REMOVED)
            f.addAction(Intent.ACTION_PACKAGE_CHANGED)
            f.addDataScheme("package")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(r, f)
            }
            pkgReceiver = r
        } catch (e: Exception) {
        }
    }

    private fun deviceName(): String {
        val n = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        val model: String = Build.MODEL ?: ""
        val brand: String = (Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }
        val fallback = if (model.startsWith(brand, true)) model else "$brand $model"
        return if (!n.isNullOrBlank()) n else fallback
    }

    // ---- theme -------------------------------------------------------------

    private class NeonColor(val name: String, val color: Int)

    private val presets = listOf(
        NeonColor("hijau", Color.rgb(57, 255, 136)),
        NeonColor("lime", Color.rgb(182, 255, 0)),
        NeonColor("aqua", Color.rgb(29, 233, 182)),
        NeonColor("cyan", Color.rgb(0, 229, 255)),
        NeonColor("biru", Color.rgb(64, 156, 255)),
        NeonColor("violet", Color.rgb(124, 77, 255)),
        NeonColor("ungu", Color.rgb(179, 87, 255)),
        NeonColor("magenta", Color.rgb(255, 43, 214)),
        NeonColor("pink", Color.rgb(255, 77, 166)),
        NeonColor("merah", Color.rgb(255, 59, 59)),
        NeonColor("oranye", Color.rgb(255, 138, 0)),
        NeonColor("kuning", Color.rgb(255, 230, 0))
    )

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1f - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1f - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1f - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun applyTheme(c: Int) {
        accent = c
        bg = mix(Color.rgb(2, 6, 5), c, 0.02f)
        panelSolid = mix(Color.rgb(4, 6, 6), c, 0.07f)
        panel = Color.argb(235, Color.red(panelSolid), Color.green(panelSolid), Color.blue(panelSolid))
        muted = mix(Color.rgb(170, 170, 170), c, 0.35f)
        dim = mix(Color.BLACK, c, 0.30f)
        tileBorder = mix(Color.BLACK, c, 0.20f)
        try {
            window.statusBarColor = bg
            window.navigationBarColor = bg
        } catch (e: Exception) {
        }
    }

    private fun applyAccent(c: Int) {
        applyTheme(c)
        savePrefs()
    }

    private fun changeTheme(c: Int) {
        applyAccent(c)
        rebuildHome()
    }

    private fun neonize(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.5f) hsv[1] = 0.5f
        if (hsv[2] < 0.85f) hsv[2] = 0.85f
        return Color.HSVToColor(hsv)
    }

    private fun hexOf(c: Int): String = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

    private fun mixColor(): Int = Color.HSVToColor(floatArrayOf(mixHue, mixSat, mixVal))

    private fun parseColorInput(q: String): Int? {
        val p = presets.firstOrNull { it.name.equals(q, true) }
        if (p != null) return p.color
        val h = if (q.startsWith("#")) q else "#$q"
        return try {
            neonize(Color.parseColor(h))
        } catch (e: Exception) {
            null
        }
    }

    // ---- saved settings: name, pins, custom app names ------------------------

    private var userName = "root"
    private val pins = mutableListOf<String>()
    private val aliases = mutableMapOf<String, String>()
    private var savedLog: CharSequence? = null
    private var savedScroll = 0
    private var settingsScroll: ScrollView? = null
    private var mixHue = 0f
    private var mixSat = 1f
    private var mixVal = 1f

    private fun loadPrefs() {
        val p = getSharedPreferences("kali", Context.MODE_PRIVATE)
        accent = p.getInt("accent", defaultAccent)
        userName = p.getString("name", "root") ?: "root"
        pins.clear()
        val pinStr = p.getString("pins", "") ?: ""
        if (pinStr.isNotEmpty()) pins.addAll(pinStr.split("\n"))
        aliases.clear()
        try {
            val o = JSONObject(p.getString("aliases", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                aliases[k] = o.getString(k)
            }
        } catch (e: Exception) {
        }
    }

    private fun savePrefs() {
        val o = JSONObject()
        for ((k, v) in aliases) o.put(k, v)
        getSharedPreferences("kali", Context.MODE_PRIVATE).edit()
            .putInt("accent", accent)
            .putString("name", userName)
            .putString("pins", pins.joinToString("\n"))
            .putString("aliases", o.toString())
            .apply()
    }

    private fun cleanName(raw: String): String {
        val c = raw.trim().replace(Regex("[^A-Za-z0-9_.-]"), "").take(16)
        return if (c.isEmpty()) "root" else c
    }

    private fun promptText(): String = "$userName@android:~#"

    private fun labelOf(a: AppEntry): String = aliases[a.pkg] ?: a.label

    private fun isPinned(pkg: String): Boolean = pins.contains(pkg)

    private fun setPinned(pkg: String, pinned: Boolean) {
        if (pinned) {
            if (!pins.contains(pkg)) pins.add(pkg)
        } else {
            pins.remove(pkg)
        }
        savePrefs()
    }

    private fun setAlias(pkg: String, name: String?) {
        if (name == null || name.isBlank()) aliases.remove(pkg) else aliases[pkg] = name.trim().take(24)
        savePrefs()
    }

    private fun findApp(q: String): AppEntry? {
        val s = q.trim()
        if (s.isEmpty()) return null
        return apps.firstOrNull { labelOf(it).equals(s, true) || it.label.equals(s, true) }
            ?: apps.firstOrNull { labelOf(it).contains(s, true) || it.label.contains(s, true) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---- small UI builders -------------------------------------------------

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        }

    private fun small(text: String): TextView = tv(text, 9f, muted).apply { letterSpacing = 0.15f }

    private fun section(text: String): TextView = tv(text, 12f, accent, true).apply { letterSpacing = 0.1f }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(panel, dim, 1)
    }

    private fun gap(v: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(v))
    }

    private fun button(text: String, filled: Boolean, size: Float = 12f, onClick: () -> Unit): TextView =
        tv(text, size, if (filled) bg else accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (filled) rounded(accent, accent, 1, 10) else rounded(panel, accent, 1, 10)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun slider(max: Int, progress: Int, onChange: (Int) -> Unit): SeekBar {
        val sb = SeekBar(this)
        sb.setMax(max)
        sb.setProgress(progress)
        sb.setProgressTintList(ColorStateList.valueOf(accent))
        sb.setThumbTintList(ColorStateList.valueOf(accent))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                onChange(p)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        return sb
    }

    // ---- dialogs (long-press an app) ---------------------------------------

    private fun showAppMenu(app: AppEntry, after: () -> Unit) {
        val pinned = isPinned(app.pkg)
        val items = arrayOf(if (pinned) "Lepas pin" else "Pin ke atas", "Ubah nama", "Reset nama")
        AlertDialog.Builder(this)
            .setTitle(labelOf(app))
            .setItems(items) { _, which ->
                if (which == 0) {
                    setPinned(app.pkg, !pinned)
                    after()
                } else if (which == 1) {
                    showRenameDialog(app, after)
                } else {
                    setAlias(app.pkg, null)
                    after()
                }
            }
            .show()
    }

    private fun showRenameDialog(app: AppEntry, after: () -> Unit) {
        val input = EditText(this)
        input.setText(labelOf(app))
        input.setSelectAllOnFocus(true)
        input.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("Ubah nama aplikasi")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val n = input.text.toString().trim()
                setAlias(app.pkg, if (n == app.label) null else n)
                after()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---- settings page -----------------------------------------------------

    private fun presetGrid(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        presets.chunked(4).forEach { rowItems ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            rowItems.forEach { p ->
                val selected = p.color == accent
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.setPadding(dp(4), dp(4), dp(4), dp(4))
                cell.isClickable = true
                cell.setOnClickListener {
                    applyAccent(p.color)
                    showSettings(true)
                }
                val sw = View(this)
                sw.background = rounded(p.color, if (selected) Color.WHITE else dim, if (selected) 3 else 1, 10)
                cell.addView(sw, LinearLayout.LayoutParams(-1, dp(40)))
                val label = tv(p.name, 9f, muted)
                label.gravity = Gravity.CENTER
                cell.addView(label)
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            box.addView(row)
        }
        return box
    }

    private fun appRow(app: AppEntry): LinearLayout {
        val pinned = isPinned(app.pkg)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), dp(8), dp(10), dp(8))
        row.background = rounded(panel, if (pinned) accent else tileBorder, 1, 10)
        val icon = ImageView(this)
        icon.setImageDrawable(app.icon)
        row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        val main = tv(labelOf(app), 12f, accent, true)
        main.maxLines = 1
        main.ellipsize = TextUtils.TruncateAt.END
        names.addView(main)
        if (aliases.containsKey(app.pkg)) {
            val orig = tv(app.label, 9f, muted)
            orig.maxLines = 1
            orig.ellipsize = TextUtils.TruncateAt.END
            names.addView(orig)
        }
        row.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        row.addView(button(if (pinned) "LEPAS" else "PIN", pinned, 11f) {
            setPinned(app.pkg, !pinned)
            showSettings(true)
        })
        row.addView(button("UBAH", false, 11f) {
            showRenameDialog(app) { showSettings(true) }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        return row
    }

    private fun showSettings(keepScroll: Boolean) {
        val oldY = if (keepScroll) (settingsScroll?.scrollY ?: 0) else 0
        mode = 2
        homeUi = null
        previewUi = null

        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(18), dp(26), dp(18), dp(14))
        page.background = bgGradient()

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(button("‹ KEMBALI", false) { showHome(true) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(tv("[ SETTINGS ]", 14f, accent, true).apply { letterSpacing = 0.15f })
        page.addView(top)
        page.addView(gap(12))

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- nama pengguna
        body.addView(section("[ NAMA PENGGUNA ]"))
        body.addView(tv("Ganti \"root\" pada prompt terminal (huruf, angka, _ . - maks 16)", 10f, muted))
        body.addView(gap(8))
        val nameInput = EditText(this)
        nameInput.setText(userName)
        nameInput.setTextColor(accent)
        nameInput.setHintTextColor(muted)
        nameInput.hint = "root"
        nameInput.textSize = 14f
        nameInput.typeface = Typeface.MONOSPACE
        nameInput.setSingleLine(true)
        nameInput.setPadding(dp(12), dp(10), dp(12), dp(10))
        nameInput.background = rounded(panel, accent, 1, 10)
        val promptPreview = tv(promptText(), 13f, accent, true)
        val nameRow = LinearLayout(this)
        nameRow.orientation = LinearLayout.HORIZONTAL
        nameRow.gravity = Gravity.CENTER_VERTICAL
        nameRow.addView(nameInput, LinearLayout.LayoutParams(0, dp(46), 1f))
        nameRow.addView(button("SIMPAN", true) {
            userName = cleanName(nameInput.text.toString())
            savePrefs()
            nameInput.setText(userName)
            promptPreview.text = promptText()
            toast("Nama diubah: $userName")
        }, LinearLayout.LayoutParams(-2, dp(46)).apply { leftMargin = dp(8) })
        body.addView(nameRow)
        body.addView(gap(6))
        body.addView(promptPreview)
        body.addView(gap(22))

        // ---- warna tema
        body.addView(section("[ WARNA TEMA ]"))
        body.addView(tv("Pilih warna neon siap pakai", 10f, muted))
        body.addView(gap(8))
        body.addView(presetGrid())
        body.addView(gap(14))
        body.addView(tv("Racik warna sendiri (dibatasi agar tetap neon)", 10f, muted))
        body.addView(gap(6))

        val hsv0 = FloatArray(3)
        Color.colorToHSV(accent, hsv0)
        mixHue = hsv0[0]
        mixSat = hsv0[1].coerceIn(0.5f, 1f)
        mixVal = hsv0[2].coerceIn(0.7f, 1f)

        val mixCard = card()
        val swatch = View(this)
        val hexText = tv(hexOf(mixColor()), 14f, accent, true)
        fun refreshMix() {
            val c = mixColor()
            swatch.background = rounded(c, Color.WHITE, 1, 10)
            hexText.text = hexOf(c)
        }
        refreshMix()
        val previewRow = LinearLayout(this)
        previewRow.orientation = LinearLayout.HORIZONTAL
        previewRow.gravity = Gravity.CENTER_VERTICAL
        previewRow.addView(swatch, LinearLayout.LayoutParams(dp(56), dp(40)))
        previewRow.addView(hexText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        mixCard.addView(previewRow)
        mixCard.addView(gap(8))
        mixCard.addView(small("HUE (WARNA)"))
        mixCard.addView(slider(360, mixHue.toInt()) {
            mixHue = it.toFloat()
            refreshMix()
        })
        mixCard.addView(small("INTENSITAS"))
        mixCard.addView(slider(50, ((mixSat - 0.5f) * 100f).toInt()) {
            mixSat = 0.5f + it / 100f
            refreshMix()
        })
        mixCard.addView(small("KECERAHAN"))
        mixCard.addView(slider(30, ((mixVal - 0.7f) * 100f).toInt()) {
            mixVal = 0.7f + it / 100f
            refreshMix()
        })
        mixCard.addView(gap(8))
        val mixBtns = LinearLayout(this)
        mixBtns.orientation = LinearLayout.HORIZONTAL
        mixBtns.addView(button("TERAPKAN", true) {
            applyAccent(mixColor())
            showSettings(true)
        })
        mixBtns.addView(button("RESET HIJAU", false) {
            applyAccent(defaultAccent)
            showSettings(true)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        mixCard.addView(mixBtns)
        body.addView(mixCard)
        body.addView(gap(22))

        // ---- aplikasi: pin & nama
        body.addView(section("[ APLIKASI: PIN & NAMA ]"))
        body.addView(
            tv(
                "PIN: tampil paling atas di daftar aplikasi. UBAH: ganti nama tampilan. " +
                    "Bisa juga lewat terminal (pin, unpin, rename) atau tekan lama ikon aplikasi.",
                10f, muted
            )
        )
        body.addView(gap(8))
        if (!appsLoaded) {
            body.addView(tv("Memuat aplikasi...", 12f, muted))
        } else {
            val ordered = pins.mapNotNull { pk -> apps.firstOrNull { it.pkg == pk } } +
                apps.filter { !pins.contains(it.pkg) }
            ordered.forEach { app ->
                body.addView(appRow(app), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        body.addView(gap(16))
        val foot = tv("by @Rag", 10f, accent, true)
        foot.gravity = Gravity.CENTER
        body.addView(foot, LinearLayout.LayoutParams(-1, -2))
        body.addView(gap(10))

        setContentView(page)
        settingsScroll = scroll
        if (oldY > 0) scroll.post { scroll.scrollTo(0, oldY) }
    }

    // ---- helpers -----------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, stroke: Int, widthDp: Int, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(widthDp), stroke)
        }

    private fun bgGradient(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                mix(Color.rgb(2, 6, 5), accent, 0.02f),
                mix(Color.rgb(3, 8, 6), accent, 0.08f),
                mix(Color.rgb(2, 6, 5), accent, 0.03f)
            )
        )

    private fun usageColor(p: Int): Int = if (p < 60) accent else if (p < 85) amber else red

    private fun batteryColor(p: Int, charging: Boolean): Int =
        if (charging) cyan else if (p > 50) accent else if (p > 20) amber else red

    // =========================================================================
    // The home screen UI (used live and as the scaled-down preview)
    // =========================================================================

    private inner class HomeUi(val interactive: Boolean) {
        private lateinit var tvClock: TextView
        private lateinit var tvDate: TextView
        private lateinit var tvTemp: TextView
        private lateinit var ringRam: RingView
        private lateinit var ringCpu: RingView
        private lateinit var ringBat: RingView
        private lateinit var tvRamDetail: TextView
        private lateinit var tvCpuDetail: TextView
        private lateinit var tvBatDetail: TextView
        private lateinit var bars: BarsView
        private lateinit var tvSignalKind: TextView
        private lateinit var tvSignal: TextView
        private lateinit var tvRemainLabel: TextView
        private lateinit var tvRemain: TextView
        private lateinit var appGrid: LinearLayout
        private lateinit var search: EditText
        private lateinit var terminalLog: TextView
        private lateinit var tvPrompt: TextView
        private lateinit var pinBox: LinearLayout
        lateinit var scroll: ScrollView

        val root: View = build()

        private fun build(): View {
            val ctx = this@MainActivity
            val rootLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(26), dp(18), dp(14))
                background = bgGradient()
            }
            scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
            val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(content)

            // Header
            val headerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(
                tv("◉  KALI TERMINAL", 13f, accent, true).apply { letterSpacing = 0.15f },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            val gear = GearView(ctx)
            gear.tint = accent
            if (interactive) {
                gear.isClickable = true
                gear.isFocusable = true
                gear.setOnClickListener { openSettings() }
            }
            headerRow.addView(gear, LinearLayout.LayoutParams(dp(34), dp(34)))
            content.addView(headerRow)
            content.addView(gap(6))

            // Clock + date
            tvClock = TextView(ctx).apply {
                text = "--:--"
                setTextColor(accent)
                textSize = 54f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setShadowLayer(22f, 0f, 0f, accent)
                includeFontPadding = false
            }
            content.addView(tvClock)
            tvDate = TextView(ctx).apply {
                setTextColor(muted)
                textSize = 12f
                typeface = Typeface.MONOSPACE
            }
            content.addView(tvDate)
            content.addView(gap(8))

            // Prompt with blinking cursor
            val promptRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            tvPrompt = tv(promptText(), 18f, accent, true)
            promptRow.addView(tvPrompt)
            val cursor = TextView(ctx).apply {
                text = " █"
                setTextColor(accent)
                textSize = 18f
                typeface = Typeface.MONOSPACE
            }
            val blink = AlphaAnimation(1f, 0f).apply {
                duration = 600
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            cursor.startAnimation(blink)
            promptRow.addView(cursor)
            content.addView(promptRow)
            content.addView(gap(16))

            // Device + temperature cards
            val infoRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val devCard = card()
            devCard.addView(small("DEVICE"))
            devCard.addView(TextView(ctx).apply {
                text = deviceName()
                setTextColor(accent)
                textSize = 15f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            devCard.addView(TextView(ctx).apply {
                text = "${Build.MODEL}  •  Android ${Build.VERSION.RELEASE}"
                setTextColor(muted)
                textSize = 9f
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val tempCard = card()
            tempCard.addView(small("TEMP"))
            tvTemp = TextView(ctx).apply {
                text = "--"
                setTextColor(accent)
                textSize = 18f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            tempCard.addView(tvTemp)
            infoRow.addView(devCard, LinearLayout.LayoutParams(0, -2, 2f))
            infoRow.addView(View(ctx), LinearLayout.LayoutParams(dp(8), 1))
            infoRow.addView(tempCard, LinearLayout.LayoutParams(0, -1, 1f))
            content.addView(infoRow)
            content.addView(gap(14))

            // Rings: RAM / CPU / BATTERY
            ringRam = RingView(ctx).apply { name = "RAM" }
            ringCpu = RingView(ctx).apply { name = "CPU" }
            ringBat = RingView(ctx).apply { name = "BAT" }
            val trk = Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent))
            for (rv in listOf(ringRam, ringCpu, ringBat)) {
                rv.trackColor = trk
                rv.subColor = muted
            }
            tvRamDetail = detail()
            tvCpuDetail = detail()
            tvBatDetail = detail()
            val ringRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            ringRow.addView(ringColumn(ringRam, tvRamDetail), LinearLayout.LayoutParams(0, -2, 1f))
            ringRow.addView(ringColumn(ringCpu, tvCpuDetail), LinearLayout.LayoutParams(0, -2, 1f))
            ringRow.addView(ringColumn(ringBat, tvBatDetail), LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(ringRow)
            content.addView(gap(14))

            // Signal + battery time cards
            val sigCard = card()
            sigCard.addView(small("SINYAL"))
            val sigRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bars = BarsView(ctx)
            bars.onColor = accent
            bars.offColor = Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent))
            sigRow.addView(bars, LinearLayout.LayoutParams(dp(26), dp(18)))
            tvSignalKind = TextView(ctx).apply {
                text = "--"
                setTextColor(accent)
                textSize = 13f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            sigRow.addView(tvSignalKind, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            sigCard.addView(sigRow)
            tvSignal = TextView(ctx).apply {
                text = "--"
                setTextColor(muted)
                textSize = 10f
                typeface = Typeface.MONOSPACE
            }
            sigCard.addView(tvSignal)

            val remCard = card()
            tvRemainLabel = small("SISA BATERAI")
            remCard.addView(tvRemainLabel)
            tvRemain = TextView(ctx).apply {
                text = "--"
                setTextColor(accent)
                textSize = 14f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            remCard.addView(tvRemain)

            val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chipRow.addView(sigCard, LinearLayout.LayoutParams(0, -1, 1f))
            chipRow.addView(View(ctx), LinearLayout.LayoutParams(dp(8), 1))
            chipRow.addView(remCard, LinearLayout.LayoutParams(0, -1, 1f))
            content.addView(chipRow)
            content.addView(gap(22))

            // Applications
            content.addView(section("[ APPLICATIONS ]"))
            content.addView(gap(8))
            pinBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            content.addView(pinBox)
            search = EditText(ctx).apply {
                hint = "Search installed apps..."
                setHintTextColor(muted)
                setTextColor(accent)
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setSingleLine(true)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(panel, accent, 1)
            }
            content.addView(search)
            content.addView(gap(10))
            appGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            content.addView(appGrid)

            // Terminal
            content.addView(gap(20))
            content.addView(section("[ TERMINAL OUTPUT ]"))
            content.addView(gap(8))
            terminalLog = TextView(ctx).apply {
                setTextColor(muted)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(panel, dim, 1)
                minHeight = dp(100)
            }
            content.addView(terminalLog)
            content.addView(gap(8))

            val commandRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val command = EditText(ctx).apply {
                hint = "Enter command..."
                setHintTextColor(muted)
                setTextColor(accent)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_DONE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(panel, accent, 1)
            }
            commandRow.addView(command, LinearLayout.LayoutParams(0, dp(46), 1f))
            val run = TextView(ctx).apply {
                text = " RUN "
                setTextColor(bg)
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(accent, accent, 1)
            }
            commandRow.addView(run, LinearLayout.LayoutParams(dp(68), dp(46)).apply { leftMargin = dp(8) })
            content.addView(commandRow)
            content.addView(gap(12))

            val execute = {
                executeCommand(command.text.toString())
                command.text.clear()
                true
            }
            run.setOnClickListener { execute() }
            command.setOnEditorActionListener { _, _, _ -> execute() }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderApps()
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            rootLayout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

            // Footer
            val footerText = "KALI TERMINAL LAUNCHER  •  by @Rag"
            val sp = SpannableString(footerText)
            val idx = footerText.indexOf("by @Rag")
            sp.setSpan(ForegroundColorSpan(accent), idx, footerText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(StyleSpan(Typeface.BOLD), idx, footerText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rootLayout.addView(TextView(ctx).apply {
                text = sp
                setTextColor(mix(Color.BLACK, accent, 0.5f))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
            return rootLayout
        }

        private fun detail(): TextView = TextView(this@MainActivity).apply {
            text = "--"
            setTextColor(muted)
            textSize = 9f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        private fun ringColumn(ring: RingView, detail: TextView): LinearLayout =
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), 0, dp(4), 0)
                addView(ring, LinearLayout.LayoutParams(-1, -2))
                addView(detail, LinearLayout.LayoutParams(-1, -2))
            }

        fun update(s: Stats) {
            tvClock.text = s.clock
            tvDate.text = s.date
            tvTemp.text = if (s.tempC > 0f) String.format(Locale.US, "%.1f°C", s.tempC) else "--"
            tvTemp.setTextColor(if (s.tempC >= 42f) red else if (s.tempC >= 38f) amber else accent)

            ringRam.setValue(s.ramPercent, "${s.ramPercent}%", usageColor(s.ramPercent))
            tvRamDetail.text = String.format(Locale.US, "%.1f / %.1f GB", s.ramUsedGb, s.ramTotalGb)

            if (s.cpuPercent >= 0) {
                ringCpu.setValue(s.cpuPercent, "${s.cpuPercent}%", usageColor(s.cpuPercent))
            } else {
                ringCpu.setValue(0, "N/A", muted)
            }
            tvCpuDetail.text = "${s.cores} CORE"

            ringBat.setValue(s.batPercent, "${s.batPercent}%", batteryColor(s.batPercent, s.charging))
            tvBatDetail.text = if (s.charging) "MENGISI ⚡" else "BATERAI"

            bars.level = s.signalLevel
            tvSignalKind.text = s.signalKind
            tvSignal.text = s.signalText
            tvRemainLabel.text = s.remainLabel
            tvRemain.text = s.remainText
        }

        fun log(message: String) {
            terminalLog.append("\n$message")
        }

        fun logText(): CharSequence = terminalLog.text

        fun setLog(t: CharSequence) {
            terminalLog.text = t
        }

        private fun makeTile(app: AppEntry, pinned: Boolean): LinearLayout {
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(8), dp(3), dp(6))
                background = rounded(panel, if (pinned) accent else tileBorder, 1)
                isClickable = true
                isFocusable = true
                setOnClickListener { launchApp(app.pkg) }
                setOnLongClickListener {
                    showAppMenu(app) { renderApps() }
                    true
                }
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            val name = tv(labelOf(app), 9f, accent).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            item.addView(name, LinearLayout.LayoutParams(-1, dp(28)))
            return item
        }

        private fun fillGrid(container: LinearLayout, list: List<AppEntry>) {
            list.chunked(4).forEach { rowApps ->
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                rowApps.forEach { app ->
                    row.addView(
                        makeTile(app, isPinned(app.pkg)),
                        LinearLayout.LayoutParams(0, dp(84), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                    )
                }
                repeat(4 - rowApps.size) {
                    row.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(84), 1f))
                }
                container.addView(row)
            }
        }

        fun renderApps() {
            pinBox.removeAllViews()
            appGrid.removeAllViews()
            if (!appsLoaded) {
                appGrid.addView(tv("Memuat aplikasi...", 12f, muted))
                return
            }
            val pinned = pins.mapNotNull { pk -> apps.firstOrNull { it.pkg == pk } }
            if (pinned.isNotEmpty()) {
                pinBox.addView(tv("★ PINNED", 10f, accent, true))
                pinBox.addView(gap(6))
                fillGrid(pinBox, pinned)
                pinBox.addView(gap(12))
                appGrid.addView(small("SEMUA APLIKASI"))
                appGrid.addView(gap(6))
            }
            val filter = search.text.toString()
            val matches = apps.filter { labelOf(it).contains(filter, true) || it.label.contains(filter, true) }
            if (matches.isEmpty()) {
                appGrid.addView(tv("No matching applications.", 12f, muted))
                return
            }
            fillGrid(appGrid, matches)
        }

        private fun launchApp(pkg: String) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) startActivity(launch) else log("Unable to launch app.")
        }

        private fun helpText(): String =
            "Commands:\n" +
                "  help, apps, date, clear, about, sethome, settings\n" +
                "  open <app>\n" +
                "  pin <app> | unpin <app> | pins\n" +
                "  rename <app> as <nama baru> | rename <app> reset\n" +
                "  setname <nama>   (ganti 'root')\n" +
                "  color <nama|#hex> | colors\n" +
                "Note: local UI shortcuts, not a Linux shell."

        private fun executeCommand(raw: String) {
            val input = raw.trim()
            if (input.isEmpty()) return
            log("${promptText()} $input")
            val parts = input.split(" ", limit = 2)
            val arg = parts.getOrNull(1).orEmpty().trim()
            when (parts[0].lowercase(Locale.ROOT)) {
                "help" -> log(helpText())
                "apps" -> {
                    search.setText("")
                    log("Loaded ${apps.size} launchable apps.")
                }
                "date" -> log(SimpleDateFormat("EEE, dd MMM yyyy  HH:mm:ss", Locale.getDefault()).format(Date()))
                "about" -> log("Kali Terminal Launcher 1.2\nA terminal-inspired Android home screen.\nby @Rag")
                "clear" -> terminalLog.text = ""
                "sethome" -> {
                    log("Requesting default Home role...")
                    requestHome()
                }
                "settings", "setting", "pengaturan" -> {
                    log("Opening settings...")
                    openSettings()
                }
                "open" -> {
                    val found = findApp(arg)
                    if (arg.isEmpty()) {
                        log("Usage: open <app name>")
                    } else if (found == null) {
                        log("App not found: $arg")
                    } else {
                        log("Launching ${labelOf(found)}...")
                        launchApp(found.pkg)
                    }
                }
                "pin" -> {
                    val app = findApp(arg)
                    if (arg.isEmpty()) {
                        log("Usage: pin <app name>")
                    } else if (app == null) {
                        log("App not found: $arg")
                    } else {
                        setPinned(app.pkg, true)
                        renderApps()
                        log("Pinned: ${labelOf(app)}")
                    }
                }
                "unpin" -> {
                    val app = findApp(arg)
                    if (arg.isEmpty()) {
                        log("Usage: unpin <app name>")
                    } else if (app == null) {
                        log("App not found: $arg")
                    } else {
                        setPinned(app.pkg, false)
                        renderApps()
                        log("Unpinned: ${labelOf(app)}")
                    }
                }
                "pins" -> {
                    val list = pins.mapNotNull { pk -> apps.firstOrNull { it.pkg == pk } }
                    if (list.isEmpty()) {
                        log("Belum ada aplikasi yang di-pin.")
                    } else {
                        log("Pinned: " + list.joinToString(", ") { labelOf(it) })
                    }
                }
                "rename" -> {
                    val lower = arg.lowercase(Locale.ROOT)
                    val idx = lower.lastIndexOf(" as ")
                    if (arg.isEmpty()) {
                        log("Usage: rename <app> as <nama baru>  |  rename <app> reset")
                    } else if (lower.endsWith(" reset")) {
                        val q = arg.substring(0, arg.length - 6).trim()
                        val app = findApp(q)
                        if (app == null) {
                            log("App not found: $q")
                        } else {
                            setAlias(app.pkg, null)
                            renderApps()
                            log("Nama dikembalikan: ${app.label}")
                        }
                    } else if (idx > 0) {
                        val q = arg.substring(0, idx).trim()
                        val newName = arg.substring(idx + 4).trim()
                        val app = findApp(q)
                        if (app == null) {
                            log("App not found: $q")
                        } else if (newName.isEmpty()) {
                            log("Usage: rename <app> as <nama baru>")
                        } else {
                            setAlias(app.pkg, newName)
                            renderApps()
                            log("${app.label} -> ${labelOf(app)}")
                        }
                    } else {
                        log("Usage: rename <app> as <nama baru>  |  rename <app> reset")
                    }
                }
                "setname", "name" -> {
                    if (arg.isEmpty()) {
                        log("Usage: setname <nama>   (sekarang: $userName)")
                    } else {
                        userName = if (arg.equals("reset", true)) "root" else cleanName(arg)
                        savePrefs()
                        tvPrompt.text = promptText()
                        log("Nama diubah menjadi: $userName")
                    }
                }
                "colors" -> log("Warna: " + presets.joinToString(", ") { it.name } + "\nAtau: color #RRGGBB")
                "color", "warna" -> {
                    if (arg.isEmpty()) {
                        log("Usage: color <nama|#hex>\nWarna: " + presets.joinToString(", ") { it.name })
                    } else {
                        val c = parseColorInput(arg)
                        if (c == null) {
                            log("Warna tidak dikenal: $arg")
                        } else {
                            log("Tema diubah: $arg")
                            changeTheme(c)
                        }
                    }
                }
                else -> log("Command not found: ${parts[0]}. Type 'help'.")
            }
        }
    }

    companion object {
        private const val REQ_HOME = 4001
        private const val REQ_PERM = 4002
    }
}
