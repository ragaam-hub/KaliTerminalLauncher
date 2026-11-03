package com.example.kaliterminal

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        sub.color = Color.rgb(139, 190, 157)
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
            p.color = if (i < level) Color.rgb(57, 255, 136) else Color.argb(70, 57, 255, 136)
            canvas.drawRoundRect(r, bw / 3f, bw / 3f, p)
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
    private val bg = Color.rgb(5, 10, 7)
    private val panel = Color.argb(235, 12, 25, 17)
    private val panelSolid = Color.rgb(12, 25, 17)
    private val green = Color.rgb(57, 255, 136)
    private val muted = Color.rgb(139, 190, 157)
    private val dim = Color.rgb(24, 75, 45)
    private val amber = Color.rgb(255, 196, 0)
    private val red = Color.rgb(255, 80, 80)
    private val cyan = Color.rgb(0, 220, 255)

    private class AppEntry(val label: String, val pkg: String, val icon: Drawable)

    private var apps: List<AppEntry> = emptyList()
    private lateinit var monitor: SystemMonitor
    private val handler = Handler(Looper.getMainLooper())
    private var homeUi: HomeUi? = null
    private var previewUi: HomeUi? = null
    private var mode = 0 // 0 = preview, 1 = home

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        monitor = SystemMonitor(applicationContext)
        askPhonePermissionOnce()
        loadApps()
        val fromHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        if (fromHome || isDefaultHome()) showHome() else showPreview()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true && mode == 0) showHome()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onBackPressed() {
        if (mode == 1) {
            if (!isDefaultHome()) showPreview()
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

    private fun showHome() {
        mode = 1
        previewUi = null
        val ui = HomeUi(true)
        homeUi = ui
        setContentView(ui.root)
        ui.log("Kali Terminal Launcher siap.")
        ui.log("Ketik 'help' untuk melihat perintah.")
        ui.renderApps()
        tick()
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
        frame.background = rounded(Color.BLACK, green, 2, 24)
        frame.addView(clip, FrameLayout.LayoutParams((w * s).toInt(), (h * s).toInt()))

        val title = TextView(this).apply {
            text = "[ PREVIEW ]"
            setTextColor(green)
            textSize = 15f
            letterSpacing = 0.2f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(14f, 0f, 0f, green)
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
            background = rounded(green, green, 1, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { requestHome() }
        }
        val hint = TextView(this).apply {
            text = "Akan meminta izin menjadikan aplikasi ini layar utama"
            setTextColor(Color.rgb(75, 125, 91))
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
        val st = monitor.read()
        homeUi?.update(st)
        previewUi?.update(st)
    }

    private fun loadApps() {
        try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            apps = packageManager.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    AppEntry(
                        it.loadLabel(packageManager).toString(),
                        it.activityInfo.packageName,
                        it.loadIcon(packageManager)
                    )
                }
                .sortedBy { it.label.lowercase(Locale.getDefault()) }
        } catch (e: Exception) {
        }
        homeUi?.renderApps()
        previewUi?.renderApps()
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
            intArrayOf(Color.rgb(2, 8, 6), Color.rgb(7, 24, 15), Color.rgb(3, 10, 8))
        )

    private fun usageColor(p: Int): Int = if (p < 60) green else if (p < 85) amber else red

    private fun batteryColor(p: Int, charging: Boolean): Int =
        if (charging) cyan else if (p > 50) green else if (p > 20) amber else red

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

        val root: View = build()

        private fun small(text: String): TextView = TextView(this@MainActivity).apply {
            this.text = text
            setTextColor(muted)
            textSize = 9f
            letterSpacing = 0.15f
            typeface = Typeface.MONOSPACE
        }

        private fun card(): LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(panel, dim, 1)
        }

        private fun section(text: String): TextView = TextView(this@MainActivity).apply {
            this.text = text
            setTextColor(green)
            textSize = 12f
            letterSpacing = 0.1f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        private fun gap(v: Int): View = View(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(v))
        }

        private fun build(): View {
            val ctx = this@MainActivity
            val rootLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(26), dp(18), dp(14))
                background = bgGradient()
            }
            val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
            val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(content)

            // Header
            content.addView(TextView(ctx).apply {
                text = "◉  KALI TERMINAL"
                setTextColor(green)
                textSize = 13f
                letterSpacing = 0.15f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
            content.addView(gap(10))

            // Clock + date
            tvClock = TextView(ctx).apply {
                text = "--:--"
                setTextColor(green)
                textSize = 54f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setShadowLayer(22f, 0f, 0f, green)
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
            promptRow.addView(TextView(ctx).apply {
                text = "root@android:~#"
                setTextColor(green)
                textSize = 18f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
            val cursor = TextView(ctx).apply {
                text = " █"
                setTextColor(green)
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
                setTextColor(green)
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
                setTextColor(green)
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
            sigRow.addView(bars, LinearLayout.LayoutParams(dp(26), dp(18)))
            tvSignalKind = TextView(ctx).apply {
                text = "--"
                setTextColor(green)
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
                setTextColor(green)
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
            search = EditText(ctx).apply {
                hint = "Search installed apps..."
                setHintTextColor(muted)
                setTextColor(green)
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setSingleLine(true)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(panel, green, 1)
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
                setTextColor(green)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_DONE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(panel, green, 1)
            }
            commandRow.addView(command, LinearLayout.LayoutParams(0, dp(46), 1f))
            val run = TextView(ctx).apply {
                text = " RUN "
                setTextColor(bg)
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(green, green, 1)
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
            sp.setSpan(ForegroundColorSpan(green), idx, footerText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(StyleSpan(Typeface.BOLD), idx, footerText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rootLayout.addView(TextView(ctx).apply {
                text = sp
                setTextColor(Color.rgb(75, 125, 91))
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
            tvTemp.setTextColor(if (s.tempC >= 42f) red else if (s.tempC >= 38f) amber else green)

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

        fun renderApps() {
            appGrid.removeAllViews()
            val filter = search.text.toString()
            val matches = apps.filter { it.label.contains(filter, true) }
            if (matches.isEmpty()) {
                appGrid.addView(TextView(this@MainActivity).apply {
                    text = "No matching applications."
                    setTextColor(muted)
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                })
                return
            }
            matches.chunked(4).forEach { rowApps ->
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                rowApps.forEach { app ->
                    val item = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(3), dp(8), dp(3), dp(6))
                        background = rounded(panel, Color.rgb(22, 55, 34), 1)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { launchApp(app.pkg) }
                    }
                    val icon = ImageView(this@MainActivity).apply {
                        setImageDrawable(app.icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    item.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
                    val name = TextView(this@MainActivity).apply {
                        text = app.label
                        setTextColor(green)
                        textSize = 9f
                        typeface = Typeface.MONOSPACE
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    item.addView(name, LinearLayout.LayoutParams(-1, dp(28)))
                    row.addView(item, LinearLayout.LayoutParams(0, dp(84), 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    })
                }
                repeat(4 - rowApps.size) {
                    row.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(84), 1f))
                }
                appGrid.addView(row)
            }
        }

        private fun launchApp(pkg: String) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) startActivity(launch) else log("Unable to launch app.")
        }

        private fun executeCommand(raw: String) {
            val input = raw.trim()
            if (input.isEmpty()) return
            log("root@android:~# $input")
            val parts = input.split(" ", limit = 2)
            when (parts[0].lowercase(Locale.ROOT)) {
                "help" -> log("Commands: help, apps, date, clear, about, sethome, open <app name>\nNote: commands are local UI shortcuts, not a Linux shell.")
                "apps" -> {
                    search.setText("")
                    log("Loaded ${apps.size} launchable apps.")
                }
                "date" -> log(SimpleDateFormat("EEE, dd MMM yyyy  HH:mm:ss", Locale.getDefault()).format(Date()))
                "about" -> log("Kali Terminal Launcher 1.1\nA terminal-inspired Android home screen.\nby @Rag")
                "clear" -> terminalLog.text = ""
                "sethome" -> {
                    log("Requesting default Home role...")
                    requestHome()
                }
                "open" -> {
                    val query = parts.getOrNull(1).orEmpty()
                    val found = apps.firstOrNull { it.label.contains(query, true) }
                    if (query.isBlank()) {
                        log("Usage: open <app name>")
                    } else if (found == null) {
                        log("App not found: $query")
                    } else {
                        log("Launching ${found.label}...")
                        launchApp(found.pkg)
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
