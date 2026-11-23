package me.ashwinkarthick.hermes

import android.content.SharedPreferences
import android.util.Log
import com.termux.terminal.TerminalSession
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Keeps Hermes running (its dashboard API for this app, and its gateway for Telegram, scheduled jobs
 * and chats) and applies settings through the dashboard API, so nothing has to be typed in a terminal.
 *
 * All slow work runs on one background thread, in order. Every public action reports back through
 * [onChange], which the service turns into a screen refresh.
 */
class HermesRuntime(
    private val svc: SetupService,
    private val prefs: SharedPreferences,
    private val onChange: () -> Unit,
) {
    enum class Status { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
    enum class Telegram { NOT_CONNECTED, STARTING, WAITING, FINISHING, CONNECTED, FAILED }

    /** An AI provider the setup screen offers, with how Hermes and the key check name it. */
    data class Provider(
        val id: String, val label: String, val envKey: String, val keyUrl: String,
        val probeUrl: String, val auth: String,
    )

    @Volatile var status = Status.STOPPED; private set
    @Volatile var message = ""; private set
    @Volatile var telegram = if (prefs.getString("telegram_bot", null) != null) Telegram.CONNECTED else Telegram.NOT_CONNECTED
        private set
    @Volatile var telegramMessage = ""; private set
    @Volatile var telegramLink: String? = null; private set
    @Volatile var busyWith: String? = null; private set     // a settings change in progress

    val aiProvider: String? get() = prefs.getString("ai_provider_label", null)
    val aiModel: String? get() = prefs.getString("ai_model", null)
    val telegramBot: String? get() = prefs.getString("telegram_bot", null)
    val webSearch: String? get() = prefs.getString("web_search", null)
    val shouldRun: Boolean get() = prefs.getBoolean("should_run", false)

    @Volatile var dashboard: TerminalSession? = null; private set
    @Volatile var gateway: TerminalSession? = null; private set
    @Volatile private var token: String? = null
    @Volatile private var stopping = false
    private val ops = Executors.newSingleThreadExecutor()
    private val secrets = java.util.Collections.synchronizedSet(HashSet<String>())

    // ---------------------------------------------------------------- start / stop

    fun start() {
        prefs.edit().putBoolean("should_run", true).apply()
        if (status == Status.STARTING || status == Status.RUNNING) return
        set(Status.STARTING, "Starting Hermes…")
        ops.execute {
            try {
                if (!ensureDashboard { set(Status.STARTING, it) }) return@execute
                if (!ensureGateway { set(Status.STARTING, it) }) return@execute
                set(Status.RUNNING, "")
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t); set(Status.FAILED, t.message ?: t.toString())
            }
        }
    }

    fun stop() {
        prefs.edit().putBoolean("should_run", false).apply()
        set(Status.STOPPING, "Stopping Hermes…")
        ops.execute {
            stopping = true
            try {
                interrupt(gateway); interrupt(dashboard)
            } finally {
                stopping = false
            }
            set(Status.STOPPED, "")
        }
    }

    /** The dashboard API this app uses. Blocking; call from [ops] or the setup worker. */
    fun ensureDashboard(progress: (String) -> Unit): Boolean {
        val running = dashboard?.isRunning == true
        if (running && token != null && http("GET", "/api/status").first == 200) return true
        if (running) interrupt(dashboard)
        if (http("GET", "/api/status", auth = false).first != -1) {
            return fail("Another copy of Hermes is still using port $PORT. Force-stop the app and open it again.")
        }
        val t = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        token = t
        val started = System.currentTimeMillis()
        val session = svc.startSessionOnMain("Hermes dashboard", "hermes dashboard --no-open --port $PORT",
            mapOf("HERMES_DASHBOARD_SESSION_TOKEN" to t))
        dashboard = session
        while (true) {
            if (!session.isRunning) return fail("Hermes' dashboard stopped (${exit(session)}). Open its terminal tab to see why.")
            if (http("GET", "/api/status").first == 200) return true
            val waited = (System.currentTimeMillis() - started) / 1000
            if (waited > 20 * 60) return fail("Hermes' dashboard didn't start within 20 minutes.")
            progress("Starting Hermes (${waited}s)…")
            Thread.sleep(2000)
        }
    }

    /** Hermes' gateway: Telegram, scheduled jobs and chat sessions. */
    private fun ensureGateway(progress: (String) -> Unit): Boolean {
        if (gateway?.isRunning == true) return true
        // Hermes can start a gateway of its own (for example right after Telegram setup). Only one
        // may run, and it must be the app's so the app can keep it alive; stop any other first.
        repeat(2) { attempt ->
            stopForeignGateway(progress)
            val started = System.currentTimeMillis()
            val session = svc.startSessionOnMain("Hermes gateway", "hermes gateway run --external-supervisor")
            gateway = session
            while (true) {
                val (code, body) = http("GET", "/api/status")
                val up = code == 200 && runCatching { JSONObject(body).optBoolean("gateway_running") }.getOrDefault(false)
                if (!session.isRunning) {
                    if (up && attempt == 0) break        // another gateway won the race: stop it, try again
                    return fail("Hermes' background service stopped (${exit(session)}). Open its terminal tab to see why.")
                }
                if (up) return true
                val waited = (System.currentTimeMillis() - started) / 1000
                if (waited > 10 * 60) return fail("Hermes' background service didn't start within 10 minutes.")
                progress("Starting Hermes' background service (${waited}s)…")
                Thread.sleep(2000)
            }
        }
        return fail("Another Hermes gateway kept starting. Force-stop the app and open it again.")
    }

    private fun gatewayReportedRunning(): Boolean {
        val (code, body) = http("GET", "/api/status")
        return code == 200 && runCatching { JSONObject(body).optBoolean("gateway_running") }.getOrDefault(false)
    }

    /**
     * Stops a gateway this app didn't start (only called while the app's own isn't running).
     * `hermes gateway stop` can't always confirm the process under proot, so the app reads the PID
     * Hermes records, checks it really is a Hermes gateway, and signals it directly: under proot,
     * Ubuntu's process numbers are the real Android ones, and an app may signal its own processes.
     */
    private fun stopForeignGateway(progress: (String) -> Unit) {
        val pid = recordedGatewayPid() ?: return
        progress("Stopping an extra copy of Hermes' background service…")
        android.os.Process.sendSignal(pid, 15)                     // SIGTERM: Hermes shuts down cleanly
        var until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until && isHermesGateway(pid)) Thread.sleep(500)
        if (isHermesGateway(pid)) {
            android.os.Process.sendSignal(pid, 9)                  // SIGKILL as a last resort
            until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until && isHermesGateway(pid)) Thread.sleep(300)
        }
    }

    private fun recordedGatewayPid(): Int? {
        val state = java.io.File(svc.linuxRootfs, "root/.hermes/gateway_state.json")
        val pid = runCatching { JSONObject(state.readText()).optInt("pid", 0) }.getOrDefault(0)
        return pid.takeIf { it > 1 && isHermesGateway(it) }
    }

    /** True when [pid] is alive and its command line is a Hermes gateway (guards against PID reuse). */
    private fun isHermesGateway(pid: Int): Boolean = runCatching {
        val cmd = java.io.File("/proc/$pid/cmdline").readText().replace('\u0000', ' ')
        "hermes" in cmd && "gateway" in cmd
    }.getOrDefault(false)

    /** Settings are read when the gateway starts, so restart it after a change (if it is running). */
    private fun restartGatewayIfRunning() {
        if (gateway?.isRunning != true) return
        set(Status.STARTING, "Restarting Hermes to use the new settings…")
        stopping = true
        try { interrupt(gateway) } finally { stopping = false }
        if (ensureGateway { set(Status.STARTING, it) }) set(Status.RUNNING, "")
    }

    /** Ctrl+C, like a person would; force-stop only if it doesn't exit. */
    private fun interrupt(session: TerminalSession?) {
        if (session == null || !session.isRunning) return
        svc.onMainSync { session.write("\u0003") }
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && session.isRunning) Thread.sleep(300)
        if (session.isRunning) svc.onMainSync { session.finishIfRunning() }
    }

    /** The service calls this when any terminal session ends. */
    fun onSessionFinished(session: TerminalSession) {
        if (stopping || !shouldRun) return
        if (session === gateway && status == Status.RUNNING) {
            // As its supervisor, start the gateway again (Hermes exits to restart itself), unless
            // it keeps dying: three exits within two minutes is a crash loop, so stop and report it.
            val now = System.currentTimeMillis()
            gatewayExits.add(now); gatewayExits.removeAll { now - it > 120_000 }
            if (gatewayExits.size >= 3) {
                set(Status.FAILED, "Hermes' background service keeps stopping (${exit(session)}). Open its terminal tab to see why, then tap Start.")
                return
            }
            set(Status.STARTING, "Restarting Hermes' background service…")
            ops.execute { if (ensureGateway { set(Status.STARTING, it) }) set(Status.RUNNING, "") }
        } else if (session === dashboard && status == Status.RUNNING) {
            set(Status.FAILED, "Hermes' dashboard stopped (${exit(session)}). Tap Start to run it again.")
        }
    }

    private val gatewayExits = java.util.Collections.synchronizedList(ArrayList<Long>())

    // ---------------------------------------------------------------- settings

    fun connectAi(provider: Provider, key: String, done: (ok: Boolean, message: String) -> Unit) {
        secrets += key
        busy("Checking your ${provider.label} key…")
        ops.execute {
            try {
                when (val check = probeKey(provider, key)) {
                    is KeyCheck.Rejected -> return@execute finishOp(done, false, check.message)
                    is KeyCheck.Unreachable -> return@execute finishOp(done, false, check.message)
                    KeyCheck.Ok -> {}
                }
                busy("Saving…")
                if (!ensureDashboard { busy(it) }) return@execute finishOp(done, false, message)
                val saved = http("PUT", "/api/env", JSONObject()
                    .put("key", provider.envKey).put("value", key).put("provider_setup", true))
                if (saved.first != 200) return@execute finishOp(done, false, "Hermes couldn't save the key (HTTP ${saved.first}).")

                val rec = http("GET", "/api/model/recommended-default?provider=${provider.id}")
                val model = if (rec.first == 200) runCatching { JSONObject(rec.second).optString("model") }.getOrDefault("") else ""
                if (model.isNotEmpty()) {
                    val switched = http("PUT", "/api/profiles/default/model", JSONObject().put("provider", provider.id).put("model", model))
                    if (switched.first != 200) return@execute finishOp(done, false, "Hermes couldn't switch to ${provider.label} (HTTP ${switched.first}).")
                } else {
                    http("PUT", "/api/config", JSONObject().put("config", JSONObject().put("model", JSONObject().put("provider", provider.id))))
                }
                prefs.edit().putString("ai_provider_label", provider.label).putString("ai_model", model.ifEmpty { "Hermes' default" }).apply()
                restartGatewayIfRunning()
                finishOp(done, true, "Connected to ${provider.label}" + (if (model.isNotEmpty()) ", using $model." else "."))
            } catch (t: Throwable) {
                Log.e(TAG, "connectAi", t); finishOp(done, false, t.message ?: t.toString())
            }
        }
    }

    fun enableFreeWebSearch(done: (ok: Boolean, message: String) -> Unit) {
        busy("Turning on web search…")
        ops.execute {
            try {
                if (!ensureDashboard { busy(it) }) return@execute finishOp(done, false, message)
                val r = http("PUT", "/api/config", JSONObject().put("config", JSONObject().put("web", JSONObject().put("backend", "tavily"))))
                if (r.first != 200) return@execute finishOp(done, false, "Hermes couldn't save the setting (HTTP ${r.first}).")
                prefs.edit().putString("web_search", "Free web search (Tavily)").apply()
                restartGatewayIfRunning()
                finishOp(done, true, "Web search is on.")
            } catch (t: Throwable) { finishOp(done, false, t.message ?: t.toString()) }
        }
    }

    /**
     * Hermes' own Telegram pairing: it creates a bot for you, learns your Telegram user ID and saves both.
     * [openLink] receives the t.me link to open in Telegram.
     */
    fun connectTelegram(openLink: (String) -> Unit) {
        setTelegram(Telegram.STARTING, "Preparing Telegram…")
        ops.execute {
            try {
                if (!ensureDashboard { setTelegram(Telegram.STARTING, it) }) return@execute setTelegram(Telegram.FAILED, message)
                val (code, body) = http("POST", "/api/messaging/telegram/onboarding/start", JSONObject().put("bot_name", "Hermes"))
                if (code != 200) return@execute setTelegram(Telegram.FAILED, "Couldn't start Telegram setup (${detail(code, body)}).")
                val start = JSONObject(body)
                val pairing = start.getString("pairing_id")
                val link = start.getString("deep_link")
                telegramLink = link
                setTelegram(Telegram.WAITING, "In Telegram, tap Start and create the bot. Then come back here.")
                svc.onMainSync { openLink(link) }

                val deadline = System.currentTimeMillis() + 15 * 60_000L
                var ready: JSONObject? = null
                while (ready == null) {
                    if (telegram != Telegram.WAITING) return@execute            // cancelled
                    if (System.currentTimeMillis() > deadline) return@execute setTelegram(Telegram.FAILED, "Telegram setup timed out. Tap Connect to try again.")
                    Thread.sleep(3000)
                    val (c, b) = http("GET", "/api/messaging/telegram/onboarding/$pairing")
                    when {
                        c == 200 && JSONObject(b).optString("status") == "ready" -> ready = JSONObject(b)
                        c == 200 -> {}
                        c == 410 -> return@execute setTelegram(Telegram.FAILED, "That Telegram link expired. Tap Connect to get a new one.")
                        c == -1 -> {}                                              // network blip; keep waiting
                        else -> return@execute setTelegram(Telegram.FAILED, "Telegram setup failed (${detail(c, b)}).")
                    }
                }
                setTelegram(Telegram.FINISHING, "Saving your Telegram bot…")
                val owner = ready!!.optString("owner_user_id")
                val bot = ready.optString("bot_username")
                if (owner.isEmpty()) return@execute setTelegram(Telegram.FAILED, "Telegram didn't report your user ID. Tap Connect to try again.")
                val (ac, ab) = http("POST", "/api/messaging/telegram/onboarding/$pairing/apply",
                    JSONObject().put("allowed_user_ids", JSONArray().put(owner)))
                if (ac != 200) return@execute setTelegram(Telegram.FAILED, "Couldn't save the Telegram bot (${detail(ac, ab)}).")
                prefs.edit().putString("telegram_bot", bot.ifEmpty { "your bot" }).apply()
                setTelegram(Telegram.CONNECTED, "")
                // Saving makes Hermes start a restart of its own in the background; give it a moment
                // so the app's (re)start below replaces whatever that left running.
                Thread.sleep(8000)
                // Telegram only answers while the gateway runs: start it, or restart it to load the bot.
                if (gateway?.isRunning == true) restartGatewayIfRunning() else svc.onMainSync { start() }
            } catch (t: Throwable) {
                Log.e(TAG, "telegram", t); setTelegram(Telegram.FAILED, t.message ?: t.toString())
            }
        }
    }

    fun cancelTelegram() { if (telegram == Telegram.WAITING || telegram == Telegram.STARTING) setTelegram(Telegram.NOT_CONNECTED, "") }

    // ---------------------------------------------------------------- key check

    private sealed class KeyCheck {
        object Ok : KeyCheck()
        class Rejected(val message: String) : KeyCheck()
        class Unreachable(val message: String) : KeyCheck()
    }

    /** Sends the key only to the provider's own API, the same kind of check Hermes' dashboard does. */
    private fun probeKey(p: Provider, key: String): KeyCheck = try {
        val url = if (p.auth == "query") "${p.probeUrl}?key=${java.net.URLEncoder.encode(key, "UTF-8")}" else p.probeUrl
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            when (p.auth) {
                "bearer" -> setRequestProperty("Authorization", "Bearer $key")
                "anthropic" -> { setRequestProperty("x-api-key", key); setRequestProperty("anthropic-version", "2023-06-01") }
            }
        }
        try {
            when (val code = c.responseCode) {
                in 200..299, 429 -> KeyCheck.Ok
                401, 403 -> KeyCheck.Rejected("${p.label} rejected this key. Check that you copied all of it, then try again.")
                402 -> KeyCheck.Rejected("${p.label} says this account has no credit. Add credit or billing on their site, then try again.")
                else -> KeyCheck.Rejected("${p.label} answered HTTP $code for this key.")
            }
        } finally { c.disconnect() }
    } catch (e: java.io.IOException) {
        KeyCheck.Unreachable("Couldn't reach ${p.label} to check the key. Check your internet connection and try again.")
    }

    // ---------------------------------------------------------------- helpers

    /** Dashboard API call; returns (status, body) or (-1, error). */
    fun http(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): Pair<Int, String> = try {
        val c = (URL("http://127.0.0.1:$PORT$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000; readTimeout = 60_000
            if (auth) token?.let { setRequestProperty("X-Hermes-Session-Token", it) }
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            code to (stream?.bufferedReader()?.use { it.readText().take(400_000) } ?: "")
        } finally { c.disconnect() }
    } catch (e: Exception) { -1 to (e.message ?: e.toString()) }

    val apiToken: String? get() = token

    /** Removes any key typed into the app from text that is about to be shared. */
    fun redact(text: String): String {
        var out = text
        for (s in secrets.toList()) if (s.length >= 8) out = out.replace(s, "[key removed]")
        return out
    }

    private fun detail(code: Int, body: String): String {
        if (code == -1) return "Hermes isn't reachable"
        val d = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
        return if (d.isNotEmpty()) d else "HTTP $code"
    }

    private fun exit(s: TerminalSession) = if (s.exitStatus < 0) "signal ${-s.exitStatus}" else "exit code ${s.exitStatus}"

    private fun fail(msg: String): Boolean { set(Status.FAILED, msg); return false }

    private fun set(s: Status, msg: String) { status = s; message = msg; onChange() }
    private fun setTelegram(t: Telegram, msg: String) { telegram = t; telegramMessage = msg; onChange() }
    private fun busy(what: String) { busyWith = what; onChange() }
    private fun finishOp(done: (Boolean, String) -> Unit, ok: Boolean, msg: String) {
        busyWith = null; onChange(); svc.onMainSync { done(ok, msg) }
    }

    companion object {
        private const val TAG = "HermesRuntime"
        const val PORT = 9119

        val PROVIDERS = listOf(
            Provider("anthropic", "Anthropic (Claude)", "ANTHROPIC_API_KEY", "https://console.anthropic.com/settings/keys",
                "https://api.anthropic.com/v1/models", "anthropic"),
            Provider("openai-api", "OpenAI", "OPENAI_API_KEY", "https://platform.openai.com/api-keys",
                "https://api.openai.com/v1/models", "bearer"),
            Provider("openrouter", "OpenRouter (many models)", "OPENROUTER_API_KEY", "https://openrouter.ai/keys",
                "https://openrouter.ai/api/v1/key", "bearer"),
            Provider("gemini", "Google Gemini", "GEMINI_API_KEY", "https://aistudio.google.com/app/apikey",
                "https://generativelanguage.googleapis.com/v1beta/models", "query"),
            Provider("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", "https://platform.deepseek.com/api_keys",
                "https://api.deepseek.com/models", "bearer"),
        )
    }
}
