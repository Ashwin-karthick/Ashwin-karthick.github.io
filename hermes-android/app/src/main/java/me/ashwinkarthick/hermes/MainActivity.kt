package me.ashwinkarthick.hermes

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class MainActivity : Activity(), SetupService.Listener {

    private var service: SetupService? = null
    private lateinit var terminal: TerminalView
    private lateinit var stepsBox: LinearLayout
    private lateinit var sessionTabs: LinearLayout
    private var shownSession: TerminalSession? = null
    private var ctrlDown = false
    private var altDown = false
    private var fontPx = 0
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as SetupService.LocalBinder).service
            service = s
            s.addListener(this@MainActivity)
            renderSteps()
            renderSessionTabs()
            (shownSession ?: s.sessions.lastOrNull())?.let { showSession(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        stepsBox = findViewById(R.id.steps)
        sessionTabs = findViewById(R.id.session_tabs)
        terminal = findViewById(R.id.terminal)
        fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics).toInt()
        terminal.setTerminalViewClient(viewClient)
        terminal.setTextSize(fontPx)
        terminal.setTypeface(Typeface.MONOSPACE)
        terminal.keepScreenOn = false

        findViewById<View>(R.id.tab_setup).setOnClickListener { showPage(terminal = false) }
        findViewById<View>(R.id.tab_terminal).setOnClickListener { showPage(terminal = true) }
        findViewById<Button>(R.id.run_all).setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.nextStep() == null) {
                // Setup is finished: open Hermes itself in the terminal.
                showSession(s.openHermes()); showPage(terminal = true)
            } else s.runAllSteps()
        }
        findViewById<Button>(R.id.share_log).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.battery).setOnClickListener { openBatterySettings() }
        buildExtraKeys()
        showPage(terminal = false)

        val intent = Intent(this, SetupService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        service?.removeListener(this)
        unbindService(connection)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- pages

    private fun showPage(terminal: Boolean) {
        findViewById<View>(R.id.page_setup).visibility = if (terminal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.page_terminal).visibility = if (terminal) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tab_setup).isSelected = !terminal
        findViewById<View>(R.id.tab_terminal).isSelected = terminal
        if (terminal) {
            this.terminal.requestFocus()
        } else {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(this.terminal.windowToken, 0)
        }
    }

    // ---------------------------------------------------------------- steps

    private fun renderSteps() {
        val s = service ?: return
        val inflater = layoutInflater
        if (stepsBox.childCount != s.steps.size) {
            stepsBox.removeAllViews()
            s.steps.forEach { _ -> stepsBox.addView(inflater.inflate(R.layout.item_step, stepsBox, false)) }
        }
        val busy = s.isBusy
        s.steps.forEachIndexed { i, step ->
            val v = stepsBox.getChildAt(i)
            val number = v.findViewById<TextView>(R.id.number)
            val status = v.findViewById<TextView>(R.id.status)
            v.findViewById<TextView>(R.id.title).text = step.title
            v.findViewById<TextView>(R.id.description).text = step.description
            number.text = (i + 1).toString()
            val (label, color) = when (step.state) {
                SetupService.State.WAITING -> "Not started" to R.color.muted
                SetupService.State.RUNNING -> "Running" to R.color.accent
                SetupService.State.DONE -> "Done" to R.color.ok
                SetupService.State.FAILED -> "Failed" to R.color.danger
            }
            status.text = label
            status.setTextColor(getColor(color))
            number.setTextColor(getColor(if (step.state == SetupService.State.DONE) R.color.accent_ink else color))
            number.background = circle(if (step.state == SetupService.State.DONE) getColor(R.color.ok) else getColor(R.color.surface2))
            if (step.state == SetupService.State.DONE) number.text = "✓"

            val detail = v.findViewById<TextView>(R.id.detail)
            detail.text = step.detail
            detail.visibility = if (step.detail.isEmpty()) View.GONE else View.VISIBLE
            detail.setTextColor(getColor(if (step.state == SetupService.State.FAILED) R.color.danger else R.color.ink))

            val progress = v.findViewById<ProgressBar>(R.id.progress)
            progress.visibility = if (step.state == SetupService.State.RUNNING) View.VISIBLE else View.GONE
            progress.isIndeterminate = step.progress < 0
            if (step.progress >= 0) progress.progress = step.progress

            val run = v.findViewById<Button>(R.id.run)
            val isNext = s.nextStep() === step
            run.visibility = if (step.state == SetupService.State.RUNNING || (!isNext && step.state == SetupService.State.WAITING)) View.GONE else View.VISIBLE
            run.text = when (step.state) {
                SetupService.State.FAILED -> "Try again"
                SetupService.State.DONE -> "Run again"
                else -> "Start"
            }
            run.isEnabled = !busy
            run.setOnClickListener {
                if (step.id == SetupService.StepId.LINUX && step.state == SetupService.State.DONE) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Reinstall Ubuntu?")
                        .setMessage("This deletes the Linux system and everything installed in it, including Hermes and its settings. You'll need to run the later steps again.")
                        .setPositiveButton("Delete and reinstall") { _, _ -> s.runStep(step.id) }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else s.runStep(step.id)
            }

            val view = v.findViewById<Button>(R.id.view_output)
            val session = step.session
            view.visibility = if (session != null) View.VISIBLE else View.GONE
            view.setOnClickListener { session?.let { showSession(it); showPage(terminal = true) } }
        }
        val runAll = findViewById<Button>(R.id.run_all)
        val next = s.nextStep()
        runAll.isEnabled = !busy
        runAll.text = when {
            busy -> "Working…"
            next == null -> "Open Hermes"
            next === s.steps.first() -> "Run all steps"
            else -> "Continue from “${next.title}”"
        }
    }

    private fun circle(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color)
    }

    // ---------------------------------------------------------------- sessions

    private fun renderSessionTabs() {
        val s = service ?: return
        sessionTabs.removeAllViews()
        val dp = resources.displayMetrics.density
        for (session in s.sessions) {
            val tab = TextView(this).apply {
                val state = if (session.isRunning) "" else if (session.exitStatus == 0) " ✓" else " ✕"
                text = session.mSessionName + state
                textSize = 13.5f
                setTextColor(getColor(R.color.ink))
                gravity = Gravity.CENTER
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                background = getDrawable(R.drawable.tab_bg)
                isSelected = session === shownSession
                setOnClickListener { showSession(session) }
                setOnLongClickListener {
                    if (!session.isRunning || session.mSessionName.startsWith("Shell") || session.mSessionName == "Hermes") {
                        s.closeSession(session)
                        if (shownSession === session) shownSession = null
                        s.sessions.lastOrNull()?.let { showSession(it) } ?: updateEmpty()
                    } else toast("Stop it first: type Ctrl+C in the terminal.")
                    true
                }
            }
            sessionTabs.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (40 * dp).toInt()).apply {
                marginEnd = (6 * dp).toInt()
            })
        }
        val add = TextView(this).apply {
            text = "+ Shell"
            textSize = 13.5f
            setTextColor(getColor(R.color.accent))
            gravity = Gravity.CENTER
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            background = getDrawable(R.drawable.btn_secondary)
            setOnClickListener { s.openShell().let { showSession(it); showPage(terminal = true) } }
        }
        sessionTabs.addView(add, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (40 * dp).toInt()))
        updateEmpty()
    }

    private fun showSession(session: TerminalSession) {
        shownSession = session
        terminal.attachSession(session)
        terminal.onScreenUpdated()
        renderSessionTabs()
    }

    private fun updateEmpty() {
        findViewById<View>(R.id.terminal_empty).visibility = if (shownSession == null) View.VISIBLE else View.GONE
        terminal.visibility = if (shownSession == null) View.INVISIBLE else View.VISIBLE
    }

    // ---------------------------------------------------------------- service callbacks (main thread)

    override fun onStepsChanged() = renderSteps()
    override fun onSessionsChanged() = renderSessionTabs()
    override fun onTextChanged(session: TerminalSession) {
        if (session === shownSession) terminal.onScreenUpdated()
    }

    // ---------------------------------------------------------------- extra keys

    private fun buildExtraKeys() {
        val row = findViewById<LinearLayout>(R.id.extra_keys)
        val dp = resources.displayMetrics.density
        fun key(label: String, onTap: (TextView) -> Unit): TextView = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(getColor(R.color.ink))
            background = getDrawable(R.drawable.key_bg)
            setOnClickListener { onTap(this) }
        }.also {
            row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = (2 * dp).toInt(); marginEnd = (2 * dp).toInt()
            })
        }
        key("Esc") { send("\u001b") }
        key("Tab") { send("\t") }
        ctrlKey = key("Ctrl") { ctrlDown = !ctrlDown; it.isSelected = ctrlDown }
        altKey = key("Alt") { altDown = !altDown; it.isSelected = altDown }
        key("←") { terminal.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0) }
        key("↓") { terminal.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
        key("↑") { terminal.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0) }
        key("→") { terminal.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0) }
    }

    private fun send(text: String) {
        val session = shownSession ?: return
        if (session.isRunning) session.write(text)
    }

    private fun releaseModifiers() {
        if (ctrlDown) { ctrlDown = false; ctrlKey.isSelected = false }
        if (altDown) { altDown = false; altKey.isSelected = false }
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
            val max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
            if (scale < 0.9f || scale > 1.1f) {
                fontPx = (fontPx * scale).coerceIn(min, max).toInt()
                terminal.setTextSize(fontPx)
                return 1.0f
            }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            terminal.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminal, 0)
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = ctrlDown.also { if (it) terminal.post { releaseModifiers() } }
        override fun readAltKey() = altDown.also { if (it) terminal.post { releaseModifiers() } }
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    // ---------------------------------------------------------------- misc

    private fun shareLog() {
        val log = service?.buildLog() ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hermes Mobile log")
            putExtra(Intent.EXTRA_TEXT, log)
        }, "Share log"))
    }

    private fun openBatterySettings() {
        // Asking to be left out of battery optimisation keeps long installs from being paused.
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (i in intents) {
            try { startActivity(i); toast("Find Hermes and choose “Don't optimise” or “Unrestricted”."); return }
            catch (_: Exception) {}
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
