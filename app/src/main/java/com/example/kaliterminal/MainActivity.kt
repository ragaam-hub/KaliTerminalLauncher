package com.example.kaliterminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(5, 10, 7)
    private val panel = Color.rgb(12, 25, 17)
    private val green = Color.rgb(57, 255, 136)
    private val muted = Color.rgb(139, 190, 157)
    private lateinit var appGrid: LinearLayout
    private lateinit var search: EditText
    private lateinit var terminalLog: TextView
    private var apps: List<android.content.pm.ResolveInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE
        buildUi()
        loadApps()
        log("Kali Terminal Launcher initialized.")
        log("Type 'help' to view available commands.")
    }

    override fun onResume() {
        super.onResume()
        if (::appGrid.isInitialized) loadApps()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(16))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val header = TextView(this).apply {
            text = "◉  KALI TERMINAL  //  ANDROID"
            setTextColor(green); textSize = 13f; typeface = Typeface.MONOSPACE
        }
        content.addView(header)
        content.addView(space(20))
        val prompt = TextView(this).apply {
            text = "root@android:~#"
            setTextColor(green); textSize = 27f; typeface = Typeface.MONOSPACE; setTypeface(null, Typeface.BOLD)
        }
        content.addView(prompt)
        val subtitle = TextView(this).apply {
            text = "MOBILE COMMAND INTERFACE     [ ONLINE ]"
            setTextColor(muted); textSize = 10f; typeface = Typeface.MONOSPACE
        }
        content.addView(subtitle)
        content.addView(space(18))

        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        info.addView(infoCard("PLATFORM", "ANDROID"), weightParams())
        info.addView(space(8, true))
        info.addView(infoCard("INTERFACE", "KALI STYLE"), weightParams())
        info.addView(space(8, true))
        info.addView(infoCard("STATUS", "READY"), weightParams())
        content.addView(info)
        content.addView(space(22))

        val section = label("[ APPLICATIONS ]")
        content.addView(section)
        content.addView(space(8))
        search = EditText(this).apply {
            hint = "Search installed apps..."
            setHintTextColor(muted); setTextColor(green); textSize = 14f
            typeface = Typeface.MONOSPACE
            singleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(panel, green, 1)
        }
        content.addView(search)
        content.addView(space(10))
        appGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appGrid)

        content.addView(space(20))
        content.addView(label("[ TERMINAL OUTPUT ]"))
        content.addView(space(8))
        terminalLog = TextView(this).apply {
            setTextColor(muted); textSize = 11f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(panel, Color.rgb(24, 75, 45), 1)
            minHeight = dp(100)
        }
        content.addView(terminalLog)
        content.addView(space(8))
        val commandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val command = EditText(this).apply {
            hint = "Enter command..."; setHintTextColor(muted); setTextColor(green); textSize = 13f
            typeface = Typeface.MONOSPACE; singleLine = true; imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = rounded(panel, green, 1)
        }
        commandRow.addView(command, LinearLayout.LayoutParams(0, dp(46), 1f))
        val run = TextView(this).apply {
            text = " RUN "; setTextColor(bg); textSize = 13f; gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(green, green, 1)
        }
        commandRow.addView(run, LinearLayout.LayoutParams(dp(68), dp(46)).apply { leftMargin = dp(8) })
        content.addView(commandRow)
        val execute = { executeCommand(command.text.toString()); command.text.clear(); true }
        run.setOnClickListener { execute() }
        command.setOnEditorActionListener { _, _, _ -> execute() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderApps(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = TextView(this).apply {
            text = "KALI-STYLE UI  •  LOCAL DEVICE LAUNCHER"
            setTextColor(Color.rgb(75, 125, 91)); textSize = 9f; typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0)
        }
        root.addView(footer)
        setContentView(root)
    }

    private fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
        renderApps(if (::search.isInitialized) search.text.toString() else "")
    }

    private fun renderApps(filter: String) {
        if (!::appGrid.isInitialized) return
        appGrid.removeAllViews()
        val matches = apps.filter { it.loadLabel(packageManager).toString().contains(filter, true) }
        if (matches.isEmpty()) {
            appGrid.addView(label("No matching applications.")); return
        }
        matches.chunked(3).forEach { rowApps ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowApps.forEach { app ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(5), dp(10), dp(5), dp(10)); background = rounded(panel, Color.rgb(22, 55, 34), 1)
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        val launch = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)
                        if (launch != null) startActivity(launch) else log("Unable to launch app.")
                    }
                }
                val icon = ImageView(this).apply { setImageDrawable(app.loadIcon(packageManager)); scaleType = ImageView.ScaleType.FIT_CENTER }
                item.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
                val name = TextView(this).apply {
                    text = app.loadLabel(packageManager); setTextColor(green); textSize = 10f
                    typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; maxLines = 2
                }
                item.addView(name, LinearLayout.LayoutParams(-1, dp(34)))
                row.addView(item, LinearLayout.LayoutParams(0, dp(88), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            repeat(3 - rowApps.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(88), 1f)) }
            appGrid.addView(row)
        }
    }

    private fun executeCommand(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        log("root@android:~# $input")
        val parts = input.split(" ", limit = 2)
        when (parts[0].lowercase(Locale.ROOT)) {
            "help" -> log("Commands: help, apps, date, clear, about, open <app name>\nNote: commands are local UI shortcuts, not a Linux shell.")
            "apps" -> { search.setText(""); log("Loaded ${apps.size} launchable apps.") }
            "date" -> log(SimpleDateFormat("EEE, dd MMM yyyy  HH:mm:ss", Locale.getDefault()).format(Date()))
            "about" -> log("Kali Terminal Launcher 1.0\nA terminal-inspired Android home screen.")
            "clear" -> terminalLog.text = ""
            "open" -> {
                val query = parts.getOrNull(1).orEmpty()
                val found = apps.firstOrNull { it.loadLabel(packageManager).toString().contains(query, true) }
                if (query.isBlank()) log("Usage: open <app name>")
                else if (found == null) log("App not found: $query")
                else {
                    log("Launching ${found.loadLabel(packageManager)}...")
                    packageManager.getLaunchIntentForPackage(found.activityInfo.packageName)?.let { startActivity(it) }
                }
            }
            else -> log("Command not found: ${parts[0]}. Type 'help'.")
        }
    }

    private fun log(message: String) {
        if (!::terminalLog.isInitialized) return
        terminalLog.append("\n$message")
    }
    private fun label(text: String) = TextView(this).apply { this.text = text; setTextColor(green); textSize = 12f; typeface = Typeface.MONOSPACE }
    private fun infoCard(title: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(10), dp(5), dp(10)); background = rounded(panel, Color.rgb(24, 75, 45), 1)
        addView(TextView(this@MainActivity).apply { text = title; setTextColor(muted); textSize = 8f; typeface = Typeface.MONOSPACE })
        addView(TextView(this@MainActivity).apply { text = value; setTextColor(green); textSize = 10f; typeface = Typeface.MONOSPACE })
    }
    private fun rounded(color: Int, stroke: Int, width: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat(); setStroke(dp(width), stroke) }
    private fun space(value: Int, horizontal: Boolean = false) = View(this).apply { layoutParams = if (horizontal) LinearLayout.LayoutParams(dp(value), 1) else LinearLayout.LayoutParams(1, dp(value)) }
    private fun weightParams() = LinearLayout.LayoutParams(0, dp(58), 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
