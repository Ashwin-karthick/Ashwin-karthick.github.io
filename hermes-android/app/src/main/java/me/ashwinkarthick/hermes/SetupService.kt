package me.ashwinkarthick.hermes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Owns the setup steps and every terminal session, so work keeps going when the screen changes.
 * Runs as a foreground service with a wake lock while anything is running.
 */
class SetupService : Service(), TerminalSessionClient {

    enum class State { WAITING, RUNNING, DONE, FAILED }

    enum class StepId { PHONE, LINUX, BASE, HERMES, API }

    class Step(val id: StepId, val title: String, val description: String) {
        @Volatile var state = State.WAITING
        @Volatile var detail = ""
        @Volatile var progress = -1          // 0..100, or -1 for none
        @Volatile var session: TerminalSession? = null
        @Volatile var startedAt = 0L
        @Volatile var endedAt = 0L
    }

    interface Listener {
        fun onStepsChanged() {}
        fun onSessionsChanged() {}
        fun onTextChanged(session: TerminalSession) {}
    }

    inner class LocalBinder : Binder() { val service: SetupService get() = this@SetupService }

    val steps = listOf(
        Step(StepId.PHONE, "Check this phone", "Confirms the phone can run the built-in Linux."),
        Step(StepId.LINUX, "Download Ubuntu", "Downloads about 30 MB and unpacks the Linux system inside the app."),
        Step(StepId.BASE, "Prepare Ubuntu", "Installs basic tools: curl, git and security certificates."),
        Step(StepId.HERMES, "Install Hermes", "Runs the official Hermes installer. This is the long one."),
        Step(StepId.API, "Start Hermes and test the connection", "Starts the Hermes dashboard and checks the app can talk to it."),
    )
    val sessions = ArrayList<TerminalSession>()
    @Volatile var runAll = false
        private set

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var env: LinuxEnv
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private val prefs by lazy { getSharedPreferences("setup", Context.MODE_PRIVATE) }
    lateinit var runtime: HermesRuntime
        private set

    /** All five install steps are done. */
    val installed: Boolean get() = steps.all { it.state == State.DONE } || (env.rootfsReady && prefs.getBoolean("done_HERMES", false))

    override fun onCreate() {
        super.onCreate()
        env = LinuxEnv(this)
        for (s in steps) if (prefs.getBoolean("done_${s.id}", false) && s.id != StepId.API) {
            s.state = State.DONE
            s.detail = prefs.getString("detail_${s.id}", "") ?: ""
        }
        // A rootfs that is gone or half-written undoes the Linux steps.
        if (!env.rootfsReady) for (s in steps) if (s.id >= StepId.LINUX) { s.state = State.WAITING; s.detail = "" }
        // Step 5 is a live check; once Hermes was installed and checked, treat it as done.
        if (prefs.getBoolean("done_API", false) && steps.first { it.id == StepId.HERMES }.state == State.DONE) {
            steps.first { it.id == StepId.API }.apply { state = State.DONE; detail = prefs.getString("detail_API", "") ?: "" }
        }
        runtime = HermesRuntime(this, prefs) { changed() }
        // Hermes was running when the app was last closed: start it again.
        if (runtime.shouldRun && installed) main.post { runtime.start() }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    val isBusy: Boolean get() = steps.any { it.state == State.RUNNING }

    fun nextStep(): Step? = steps.firstOrNull { it.state != State.DONE }

    // ---------------------------------------------------------------- running steps

    fun runAllSteps() {
        runAll = true
        nextStep()?.let { runStep(it.id) }
    }

    fun runStep(id: StepId) {
        val step = steps.first { it.id == id }
        if (isBusy) return
        val blocker = steps.takeWhile { it.id != id }.firstOrNull { it.state != State.DONE }
        if (blocker != null) {
            step.state = State.FAILED
            step.detail = "Finish “${blocker.title}” first."
            changed(); return
        }
        step.state = State.RUNNING
        step.detail = ""
        step.progress = -1
        step.startedAt = System.currentTimeMillis()
        step.endedAt = 0
        step.session = null
        goForeground(step.title)
        changed()
        worker.execute {
            try {
                when (id) {
                    StepId.PHONE -> checkPhone(step)
                    StepId.LINUX -> installLinux(step)
                    StepId.BASE -> onMain { startSession(step, env.ubuntuArgs("bash /opt/hermes-mobile/setup-base.sh")) }
                    StepId.HERMES -> {
                        val freeGb = StatFs(filesDir.path).availableBytes / 1e9
                        if (freeGb < 3.5) {
                            finish(step, false, "Only %.1f GB free. Installing Hermes needs about 3.5 GB; free up space and try again.".format(freeGb))
                            return@execute
                        }
                        if (env.clearStaleHermesPackages()) {
                            step.detail = "Cleared leftovers from an earlier failed install."; changed()
                        }
                        onMain { startSession(step, env.ubuntuArgs("bash /opt/hermes-mobile/install-hermes.sh")) }
                    }
                    StepId.API -> startDashboard(step)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "step $id failed", t)
                finish(step, false, t.message ?: t.toString())
            }
        }
    }

    private fun checkPhone(step: Step) {
        val abis = Build.SUPPORTED_ABIS.toList()
        if ("arm64-v8a" !in abis) {
            return finish(step, false, "This phone's processor (${abis.joinToString()}) isn't supported. The built-in Linux needs a 64-bit ARM phone.")
        }
        val freeGb = StatFs(filesDir.path).availableBytes / 1e9
        if (freeGb < 5.0) {
            return finish(step, false, "Only %.1f GB free. Ubuntu and Hermes need about 4 GB; free up at least 5 GB and try again.".format(freeGb))
        }
        if (!env.proot.isFile) return finish(step, false, "The app is missing its Linux runner (proot). Reinstall the app.")
        env.prepareHost()
        step.detail = "Android ${Build.VERSION.RELEASE}, %.1f GB free. Testing the Linux runner…".format(freeGb)
        changed()
        onMain {
            startSession(step, env.androidArgs(
                "echo \"Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\"; " +
                    "echo \"Free space: %.1f GB\"; echo; ".format(freeGb) +
                    "\"\$HM_PROOT\" --version || { echo; echo 'The Linux runner (proot) could not start.'; exit 1; }; " +
                    "echo; echo '==> The Linux runner works'"
            ))
        }
    }

    private fun installLinux(step: Step) {
        env.prepareHost()
        if (!(env.download.isFile && sha256(env.download) == ROOTFS_SHA256)) {
            download(step)
        }
        step.detail = "Unpacking Ubuntu…"; step.progress = 0; changed()
        env.clearRootfs()
        // Everything after this step lived inside the old Ubuntu, so it has to run again.
        for (later in steps) if (later.id > StepId.LINUX) {
            later.state = State.WAITING; later.detail = ""; later.session = null
            prefs.edit().remove("done_${later.id}").remove("detail_${later.id}").apply()
        }
        changed()
        val total = env.download.length().coerceAtLeast(1)
        val result = RootfsExtractor(
            chmod = { p, mode -> android.system.Os.chmod(p.toString(), mode) },
            onProgress = { read ->
                step.progress = (read * 100 / total).toInt().coerceIn(0, 100)
                step.detail = "Unpacking Ubuntu… ${step.progress}%"
                changed()
            },
        ).extract(env.download, env.rootfs)
        Log.i(TAG, "rootfs extracted: $result")
        env.prepareGuest()
        env.markRootfsComplete()
        env.download.delete()
        step.progress = -1
        step.detail = "Unpacked ${result.files} files. Starting Linux for the first time…"
        changed()
        onMain { startSession(step, env.ubuntuArgs("bash /opt/hermes-mobile/selftest.sh && echo && echo '==> Linux is running'")) }
    }

    private fun download(step: Step) {
        env.download.delete()
        val part = File(env.download.path + ".part")
        val conn = (URL(ROOTFS_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Download failed: server answered ${conn.responseCode}.")
            val size = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); read += n
                        if (size > 0) {
                            val pct = (read * 100 / size).toInt()
                            if (pct != lastPct) {
                                lastPct = pct; step.progress = pct
                                step.detail = "Downloading Ubuntu… ${read / 1_000_000} of ${size / 1_000_000} MB"
                                changed()
                            }
                        }
                    }
                }
            }
            val sum = digest.digest().joinToString("") { "%02x".format(it) }
            if (sum != ROOTFS_SHA256) {
                part.delete()
                throw IllegalStateException("The download was corrupted (checksum mismatch). Try again.")
            }
            part.renameTo(env.download)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Download failed: ${e.message}. Check your internet connection and try again.")
        } finally {
            conn.disconnect()
        }
    }

    /** Step 5: start Hermes' dashboard (kept running for the app) and check the app can use its API. */
    private fun startDashboard(step: Step) {
        val ok = runtime.ensureDashboard { msg -> step.detail = msg; changed() }
        step.session = runtime.dashboard
        if (!ok) return finish(step, false, runtime.message)
        val (open, _) = runtime.http("GET", "/api/skills", auth = false)
        val (authed, body) = runtime.http("GET", "/api/skills")
        val skills = Regex("\"name\"\\s*:").findAll(body).count()
        val summary = "Hermes is up. Without the app's key: $open. With it: $authed" +
            (if (authed == 200) " ($skills skills found)." else ".")
        finish(step, authed == 200, summary)
    }

    private fun finish(step: Step, ok: Boolean, detail: String) {
        step.state = if (ok) State.DONE else State.FAILED
        step.detail = detail
        step.progress = -1
        step.endedAt = System.currentTimeMillis()
        prefs.edit().putBoolean("done_${step.id}", ok).putString("detail_${step.id}", detail).apply()
        if (!ok) runAll = false
        changed()
        onMain {
            if (ok && runAll) {
                val next = nextStep()
                if (next != null) { runStep(next.id); return@onMain } else runAll = false
            }
            updateForeground()
        }
    }

    // ---------------------------------------------------------------- sessions

    fun openShell(): TerminalSession = startSession(null,
        if (env.rootfsReady) env.ubuntuArgs(null) else arrayOf("sh"))

    private fun startSession(step: Step?, args: Array<String>, extraEnv: Map<String, String> = emptyMap(), name: String? = null): TerminalSession {
        if (step == null || step.id != StepId.PHONE) env.prepareHost()
        val session = TerminalSession(LinuxEnv.SHELL, env.base.path, args, env.environment(extraEnv), TRANSCRIPT_ROWS, this)
        session.mSessionName = name ?: step?.title ?: "Shell ${sessions.count { it.mSessionName.startsWith("Shell") } + 1}"
        // The process starts when the emulator gets a size. Give it one now so a step runs even if
        // its terminal is never on screen; the terminal view resizes it when shown.
        session.updateSize(80, 30)
        sessions += session
        step?.session = session
        goForeground(step?.title ?: "Terminal")
        listeners.forEach { it.onSessionsChanged() }
        changed()
        return session
    }

    /** Starts a named Ubuntu command in a terminal session from any thread and returns the session. */
    fun startSessionOnMain(name: String, command: String, extraEnv: Map<String, String> = emptyMap()): TerminalSession {
        var created: TerminalSession? = null
        onMainSync { created = startSession(null, env.ubuntuArgs(command), extraEnv, name = name) }
        return created!!
    }

    /** Runs [block] on the main thread and waits for it (directly when already on it). */
    fun onMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        latch.await()
    }

    fun closeSession(session: TerminalSession) {
        session.finishIfRunning()
        sessions.remove(session)
        listeners.forEach { it.onSessionsChanged() }
        updateForeground()
    }

    override fun onSessionFinished(finished: TerminalSession) {
        runtime.onSessionFinished(finished)
        val step = steps.firstOrNull { it.session === finished && it.state == State.RUNNING && it.id != StepId.API }
        if (step != null) {
            val code = finished.exitStatus
            if (step.id == StepId.API) {
                finish(step, false, "The Hermes dashboard stopped (${describeExit(code)}). Open the terminal to see why.")
            } else if (code == 0) {
                finish(step, true, step.detail.takeIf { step.id == StepId.PHONE && it.isNotEmpty() }?.substringBefore(" Testing")
                    ?: "Done in ${(System.currentTimeMillis() - step.startedAt) / 1000}s.")
            } else {
                finish(step, false, explainFailure(code))
            }
        }
        listeners.forEach { it.onSessionsChanged() }
        updateForeground()
    }

    private fun describeExit(code: Int) = if (code < 0) "signal ${-code}" else "exit code $code"

    private fun explainFailure(code: Int): String = when {
        code == -9 -> "Android stopped this step (signal 9). Android 12 and later limit background processes. " +
            "Keep the app on screen and try again; if it keeps happening, see “Android stopped the process” in the README."
        code < 0 -> "The step was stopped (signal ${-code}). Try again."
        else -> "The step failed (exit code $code). Open the terminal to see the error, then try again."
    }

    // ---------------------------------------------------------------- TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession) { listeners.forEach { it.onTextChanged(changedSession) } }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        session?.emulator?.paste(text)
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { listeners.forEach { it.onTextChanged(session) } }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "error", e) }

    // ---------------------------------------------------------------- log export

    fun buildLog(): String = buildString {
        append("Hermes Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("Free space: %.1f GB\n".format(StatFs(filesDir.path).availableBytes / 1e9))
        append("Linux ready: ${env.rootfsReady}\n\n")
        for (s in steps) append("[${s.state}] ${s.title}: ${s.detail}\n")
        append("\nHermes: ${runtime.status} ${runtime.message}\n")
        append("AI: ${runtime.aiProvider ?: "not connected"} / ${runtime.aiModel ?: "-"}\n")
        append("Telegram: ${runtime.telegram} ${runtime.telegramBot ?: ""} ${runtime.telegramMessage}\n")
        append("Web search: ${runtime.webSearch ?: "off"}\n")
        for (session in sessions) {
            append("\n===== ${session.mSessionName} (")
            append(if (session.isRunning) "running" else describeExit(session.exitStatus))
            append(") =====\n")
            append(session.emulator?.screen?.transcriptText?.takeLast(60_000) ?: "")
            append('\n')
        }
    }.let { runtime.redact(it) }.takeLast(400_000)

    // ---------------------------------------------------------------- foreground + wake lock

    private fun goForeground(what: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Setup and terminal", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hermes")
            .setContentText(what)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (!foreground) {
            startService(Intent(this, SetupService::class.java))
            startForeground(NOTIFICATION_ID, n)
            foreground = true
        } else {
            nm.notify(NOTIFICATION_ID, n)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:setup").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun updateForeground() {
        if (isBusy || sessions.any { it.isRunning }) return
        wakeLock?.release(); wakeLock = null
        if (foreground) { stopForeground(true); foreground = false; stopSelf() }
    }

    override fun onDestroy() {
        for (s in sessions) s.finishIfRunning()
        wakeLock?.release()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun changed() = main.post { listeners.forEach { it.onStepsChanged() } }
    private fun onMain(block: () -> Unit) { main.post(block) }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val b = ByteArray(1 shl 16); while (true) { val n = input.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "HermesMobile"
        private const val CHANNEL = "setup"
        private const val NOTIFICATION_ID = 1
        private const val TRANSCRIPT_ROWS = 5000
        const val API_PORT = 9119
        const val ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
        const val ROOTFS_SHA256 = "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
    }
}
