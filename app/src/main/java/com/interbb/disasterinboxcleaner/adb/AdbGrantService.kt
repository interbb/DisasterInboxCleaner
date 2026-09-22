package com.interbb.disasterinboxcleaner.adb

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.interbb.disasterinboxcleaner.PermissionState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that walks the on-phone grant flow: the notification normally asks only for
 * the 6-digit pairing code, since this service discovers its own pairing port via mDNS once the
 * user opens the pairing dialog (see [discoverInBackground] and [handleCodeOnly]). Only if that
 * discovery keeps finding nothing does it escalate to asking for the port too (the pairing
 * dialog's port changes every time it is reopened, so it must arrive with the code while the
 * dialog is still on screen). Once paired, it asks for the stable connect port (skipped if a
 * previous run already remembered one); then it runs `appops set <pkg> WRITE_SMS allow` and
 * verifies. Every transition is written to AdbGrantPreferences and mirrored in the notification.
 */
class AdbGrantService : Service() {
    private lateinit var prefs: AdbGrantPreferences
    private lateinit var notifications: AdbGrantNotifications
    private lateinit var adb: AdbBinary
    private lateinit var executor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var phase = GrantPhase.IDLE
    @Volatile private var cancelled = false
    @Volatile private var finishing = false

    // In-memory only (never persisted, unlike the connect port): the pairing port changes on
    // every reopen of the pairing dialog, so keeping it across process death would just be stale.
    @Volatile private var discoveredPairingPort: Int? = null
    @Volatile private var discoveredConnectPort: Int? = null

    // A port the user typed alongside the code this session (see AdbCodeInput.CodeInput.PairingAndCode).
    // Kept separately from discoveredPairingPort because it is authoritative (the user read it off
    // the dialog on screen) and never overwritten by a later mDNS discovery.
    @Volatile private var typedPairingPort: Int? = null

    // I3: identifies the current session. Every task submitted to the executor (and every post
    // back to the main thread from one) captures this value at submission time and refuses to
    // touch shared state — including calling finish() — once it no longer matches, so a stale
    // session's leftover background work can never drive or tear down the session that replaced it.
    @Volatile private var sessionId = 0

    // I4: at most one on-demand re-discovery (triggered by a lone-code reply with no known
    // pairing port yet) may be in flight at a time; a second such reply while one is running is
    // dropped rather than queuing a second discovery and a second runPairing.
    @Volatile private var discoveryPending = false

    private val timeoutRunnable = Runnable {
        transition(GrantEvent.Timeout)
        finish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = AdbGrantPreferences(this)
        notifications = AdbGrantNotifications(this).also { it.ensureChannel() }
        adb = AdbBinary(this)
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_CODE -> intent.getStringExtra(EXTRA_INPUT)?.let { raw -> handleReply(raw) }
            ACTION_CANCEL -> {
                cancelled = true
                transition(GrantEvent.Cancel)
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleReply(raw: String) {
        if (phase == GrantPhase.IDLE) {
            val persisted = prefs.snapshot().phase
            if (persisted == GrantPhase.WAITING_CODE || persisted == GrantPhase.WAITING_CONNECT_PORT) {
                // The process died while waiting; the notification outlived it. Resume the session.
                phase = persisted
            }
        }
        when {
            phase == GrantPhase.WAITING_CODE || phase == GrantPhase.WAITING_CONNECT_PORT -> parseCodeReply(raw)
            GrantStatus(phase).inProgress -> {
                // PAIRING/CONNECTING/GRANTING already own the session; ignore the duplicate reply
                // and leave the running attempt (and its notification) alone.
            }
            else -> {
                prefs.set(GrantPhase.FAILED, GrantMessages.NOT_WAITING)
                mainHandler.post { notifications.show(GrantPhase.FAILED, GrantMessages.NOT_WAITING) }
                finish()
            }
        }
    }

    /**
     * Parses one WAITING_CODE/WAITING_CONNECT_PORT reply and acts on it. Always runs on the main
     * thread. Both WAITING_CODE shapes (port+code together, or the code alone) are always accepted
     * by [AdbCodeInput.parse] regardless of what this service currently knows — see [handleCodeOnly]
     * for what happens to a lone code when no pairing port is known yet.
     */
    private fun parseCodeReply(raw: String) {
        when (val input = AdbCodeInput.parse(raw, phase)) {
            is CodeInput.Code -> handleCodeOnly(input.code)
            is CodeInput.PairingAndCode -> {
                // A port typed alongside the code always wins over a discovered one (the user is
                // reading it off the dialog on screen), and is remembered for any later lone-code
                // reply in this same session.
                typedPairingPort = input.port
                val mySessionId = sessionId
                executor.execute { runPairing(mySessionId, input.port, input.code) }
            }
            is CodeInput.ConnectPort -> {
                val mySessionId = sessionId
                executor.execute { runConnect(mySessionId, input.port) }
            }
            is CodeInput.Invalid -> showInvalid(input.message)
        }
    }

    /**
     * A lone 6-digit code, with no port typed in this same reply (C1). Pairs against whatever
     * pairing port is already known — found by mDNS, or typed earlier this session
     * ([typedPairingPort]). If neither is known yet, retries discovery once (guarded by
     * [discoveryPending] so a second such reply while one is already running is simply dropped,
     * per I4); if the retry still finds nothing, stays in WAITING_CODE and escalates to
     * [GrantMessages.WAITING], asking for the port too — the only place in the flow that ever
     * falls back to the port+code message.
     */
    private fun handleCodeOnly(code: String) {
        val knownPort = discoveredPairingPort ?: typedPairingPort
        if (knownPort != null) {
            val mySessionId = sessionId
            executor.execute { runPairing(mySessionId, knownPort, code) }
            return
        }
        if (discoveryPending) return
        discoveryPending = true
        val mySessionId = sessionId
        executor.execute {
            try {
                if (mySessionId != sessionId) return@execute
                val ports = MdnsDiscovery.discover()
                if (mySessionId != sessionId) return@execute
                // Keep what an earlier discovery already found: a retry that comes up empty, because
                // the pairing dialog happened to be closed just then, must not erase a usable port.
                discoveredPairingPort = ports.pairingPort ?: discoveredPairingPort
                discoveredConnectPort = ports.connectPort ?: discoveredConnectPort
                mainHandler.post {
                    // The session may have been cancelled, timed out, or already paired (via a
                    // typed port) while this ran.
                    if (mySessionId != sessionId || phase != GrantPhase.WAITING_CODE) return@post
                    val port = discoveredPairingPort
                    if (port != null) {
                        executor.execute {
                            if (mySessionId != sessionId) return@execute
                            runPairing(mySessionId, port, code)
                        }
                    } else {
                        // Still nothing found: this was a valid 6-digit code, just with no pairing
                        // port to run it against yet. Stay in WAITING_CODE and escalate to asking
                        // for the port too.
                        prefs.set(GrantPhase.WAITING_CODE, GrantMessages.WAITING)
                        armWaitTimeout()
                        notifications.show(GrantPhase.WAITING_CODE, GrantMessages.WAITING)
                    }
                }
            } finally {
                discoveryPending = false
            }
        }
    }

    private fun showInvalid(message: String) {
        val base = when {
            phase != GrantPhase.WAITING_CODE -> GrantMessages.WAITING_CONNECT_PORT
            // The flow starts (and normally stays) asking for the code alone regardless of
            // whether a pairing port has actually been found yet — handleCodeOnly can always run
            // its own on-demand discovery for a lone code. The one exception is once this session
            // has already escalated to GrantMessages.WAITING (persisted by handleCodeOnly's failed
            // retry, above); that escalation must be preserved here, not silently reverted.
            prefs.snapshot().message.endsWith(GrantMessages.WAITING) -> GrantMessages.WAITING
            else -> GrantMessages.WAITING_CODE_ONLY
        }
        val combined = GrantMessages.composeInvalidReply(message, base)
        prefs.set(phase, combined)
        // A reply that fails to parse still leaves us waiting; guarantee a timeout is armed even
        // if this session was just resumed (new process, no live timer yet) from a dead-process
        // resume in handleReply.
        armWaitTimeout()
        mainHandler.post { notifications.show(phase, combined) }
    }

    private fun promoteToForeground() {
        val shownPhase = if (phase == GrantPhase.IDLE) GrantPhase.WAITING_CODE else phase
        // Only reached with a blank persisted message before start() has ever run in this
        // install; the real flow always begins by asking for the code alone.
        val message = prefs.snapshot().message.ifBlank { GrantMessages.WAITING_CODE_ONLY }
        val notification = notifications.build(shownPhase, message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AdbGrantNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(AdbGrantNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun start() {
        if (GrantStatus(phase).inProgress) return
        phase = GrantPhase.IDLE
        cancelled = false
        finishing = false
        discoveredPairingPort = null
        discoveredConnectPort = null
        typedPairingPort = null
        discoveryPending = false
        val mySessionId = ++sessionId
        when {
            !adb.isAvailable() -> {
                transition(GrantEvent.BinaryMissing)
                finish()
                return
            }
            !wirelessDebuggingEnabled() -> {
                transition(GrantEvent.WirelessDebuggingOff)
                finish()
                return
            }
            !onWifi() -> {
                transition(GrantEvent.NoWifi)
                finish()
                return
            }
        }
        // I2: transition to WAITING_CODE and arm the wait timeout synchronously, exactly as before
        // discovery moved off the main thread, so a reply can never arrive while phase is still
        // IDLE. Discovery below only ever populates discoveredPairingPort/discoveredConnectPort
        // for handleCodeOnly to use; it never drives this transition or touches the message.
        if (!transition(GrantEvent.Start)) {
            finish()
            return
        }
        armWaitTimeout()
        executor.execute { discoverInBackground(mySessionId) }
    }

    /**
     * Finds the phone's own wireless-debugging ports (blocking, hence run off the main thread)
     * right after the phase moves to WAITING_CODE, so that a lone 6-digit code reply
     * ([handleCodeOnly]) can pair immediately instead of needing its own on-demand re-discovery.
     * The notification already reads code-only from session start (see [AdbGrantMachine]'s Start
     * transition), so there is nothing for a successful discovery here to upgrade the message to,
     * and finding nothing must never downgrade it back to asking for the port too — that
     * escalation happens only from [handleCodeOnly]'s own on-demand retry, once an actual code
     * reply has nothing to pair against.
     */
    private fun discoverInBackground(mySessionId: Int) {
        val ports = MdnsDiscovery.discover()
        if (mySessionId != sessionId) return
        discoveredPairingPort = ports.pairingPort
        discoveredConnectPort = ports.connectPort
    }

    /**
     * Schedules [timeoutRunnable] to fire in [WAIT_TIMEOUT_MS], replacing any timer already
     * scheduled. Call this whenever the session ends up staying in a `WAITING_*` phase, so a live
     * timeout is always armed for it — including after a dead-process resume in [handleReply],
     * which starts with no timer of its own (the original one died with the old process).
     */
    private fun armWaitTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, WAIT_TIMEOUT_MS)
    }

    /** Runs `pair 127.0.0.1:<port> <code>`; on success either proceeds to connect or waits for the connect port. */
    private fun runPairing(mySessionId: Int, port: Int, code: String) {
        if (mySessionId != sessionId) return // Superseded by a newer session (I3); do nothing, not even finish().
        mainHandler.removeCallbacks(timeoutRunnable)
        val accepted = transition(GrantEvent.CodeEntered)
        if (!accepted || cancelled || finishing) {
            // The state machine no longer owns this event for the current phase (already
            // cancelled, timed out, or otherwise moved on) — do not run the blocking adb call
            // against a dead session.
            finish()
            return
        }
        val paired = AdbOutcomes.pairSucceeded(adb.pair(port, code))
        if (mySessionId != sessionId) return
        if (cancelled) {
            finish()
            return
        }
        if (!paired) {
            transition(GrantEvent.PairResult(ok = false))
            finish()
            return
        }
        // Priority: a connect port remembered in prefs from a previous successful run, then the
        // one this run's mDNS discovery found (persisted only once a connect actually succeeds,
        // via proceedToConnect's existing prefs.rememberConnectPort call), then ask the user.
        val connectPort = prefs.rememberedConnectPort() ?: discoveredConnectPort
        transition(GrantEvent.PairResult(ok = true, connectPortKnown = connectPort != null))
        if (mySessionId != sessionId) return
        if (cancelled) {
            finish()
            return
        }
        if (connectPort != null) {
            proceedToConnect(connectPort)
        } else {
            // Stay in WAITING_CONNECT_PORT; finish() runs later, from proceedToConnect's own
            // completion or from a cancel/timeout while waiting.
            armWaitTimeout()
        }
    }

    /** Runs `connect 127.0.0.1:<port>` for a connect port the user just typed in. */
    private fun runConnect(mySessionId: Int, port: Int) {
        if (mySessionId != sessionId) return
        mainHandler.removeCallbacks(timeoutRunnable)
        val accepted = transition(GrantEvent.ConnectPortEntered)
        if (!accepted || cancelled || finishing) {
            finish()
            return
        }
        proceedToConnect(port)
    }

    /** Connects, then runs and verifies `appops set <pkg> WRITE_SMS allow`. Always terminates the session. */
    private fun proceedToConnect(port: Int) {
        try {
            if (cancelled) return
            if (!AdbOutcomes.connectSucceeded(adb.connect(port))) {
                prefs.forgetConnectPort()
                transition(GrantEvent.ConnectResult(false))
                return
            }
            prefs.rememberConnectPort(port)
            transition(GrantEvent.ConnectResult(true))
            if (cancelled) return

            val grant = adb.shell("appops", "set", packageName, "WRITE_SMS", "allow")
            val verified = AdbOutcomes.grantSucceeded(grant) && PermissionState.canDeleteSms(this)
            val detail = AdbOutcomes.maskCode(grant.output)
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            if (cancelled) return
            transition(GrantEvent.GrantResult(verified, detail))
        } finally {
            finish()
        }
    }

    /**
     * Returns whether [event] was accepted for the current phase (false if the state machine
     * rejected it). [messageOverride], when non-null, replaces the state machine's own message
     * for this one transition.
     */
    private fun transition(event: GrantEvent, messageOverride: String? = null): Boolean {
        val next = AdbGrantMachine.next(phase, event) ?: return false
        phase = next.phase
        val message = messageOverride ?: next.message
        prefs.set(next.phase, message)
        mainHandler.post { notifications.show(next.phase, message) }
        return true
    }

    /**
     * Tears the session down. A finished session must leave nothing pinned to the shade: cancel (IDLE) and
     * success (DONE) remove the notification outright, because the app's own screen already shows the result
     * and a stuck "권한 부여 진행 중" is what the user sees if anything goes wrong between the last progress
     * post and teardown. Only FAILED detaches, so the reason stays readable — and it is dismissible, since
     * [AdbGrantNotifications.build] clears ongoing for that phase. Guarded so cancel racing a running
     * attempt's own cleanup only runs this once per session.
     */
    private fun finish(
        stopType: Int =
            if (phase == GrantPhase.IDLE || phase == GrantPhase.DONE) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH,
    ) {
        synchronized(this) {
            if (finishing) return
            finishing = true
        }
        mainHandler.removeCallbacks(timeoutRunnable)
        executor.execute {
            adb.killServer()
            mainHandler.post {
                stopForeground(stopType)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        executor.shutdown()
        super.onDestroy()
    }

    private fun wirelessDebuggingEnabled(): Boolean =
        Settings.Global.getInt(contentResolver, SETTING_ADB_WIFI_ENABLED, 0) == 1

    private fun onWifi(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        @Volatile var isRunning = false

        const val ACTION_START = "com.interbb.disasterinboxcleaner.adb.START"
        const val ACTION_CODE = "com.interbb.disasterinboxcleaner.adb.CODE"
        const val ACTION_CANCEL = "com.interbb.disasterinboxcleaner.adb.CANCEL"
        const val EXTRA_INPUT = "input"
        const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val WAIT_TIMEOUT_MS = 10 * 60 * 1000L

        fun startIntent(context: Context): Intent =
            Intent(context, AdbGrantService::class.java).setAction(ACTION_START)

        fun cancelIntent(context: Context): Intent =
            Intent(context, AdbGrantService::class.java).setAction(ACTION_CANCEL)

        /** Used both by the notification's inline-reply receiver and by the guide's manual connect-port field. */
        fun replyIntent(context: Context, raw: String): Intent =
            Intent(context, AdbGrantService::class.java)
                .setAction(ACTION_CODE)
                .putExtra(EXTRA_INPUT, raw)
    }
}
