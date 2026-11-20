package app.sucareto.hidora.profile.keyboardmouse.agent

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.sucareto.hidora.profile.keyboardmouse.KeyboardMouseOutput
import app.sucareto.hidora.profile.keyboardmouse.MouseReporter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class AgentUiState(
    val waitingForApproval: Boolean = false,
    val allowed: Boolean = false,
    val remainingSeconds: Int = 0,
    val error: String? = null
)

/**
 * The Android-side endpoint is NOT an MCP server. It is a loopback-only,
 * single-request JSON bridge reached through an explicitly paired ADB tunnel.
 * The host-side MCP process handles the public MCP protocol.
 */
class AgentBridge(private val output: KeyboardMouseOutput, private val available: Boolean = true) : Closeable {
    private val stateLock = Any()
    private val ioLock = Any()
    private val random = SecureRandom()
    private val receipts = LinkedHashMap<String, JSONObject>()
    private var approvedToken: String? = null
    private var approvalExpiresAt = 0L
    private var remainingActions = 0
    private var pending: Pending? = null
    @Volatile private var foreground = true
    @Volatile private var closed = false
    @Volatile private var server: ServerSocket? = null

    var ui by mutableStateOf(AgentUiState(error = if (available) null else "Root USB HID required"))
        private set

    private class Pending(val latch: CountDownLatch = CountDownLatch(1)) {
        @Volatile var token: String? = null
    }

    init {
        if (available) {
            Thread(::serve, "hidora-agent-loopback").apply { isDaemon = true; start() }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) revoke()
    }

    fun allowPending() {
        synchronized(stateLock) {
            val request = pending ?: return
            if (!foreground || closed) return
            val secret = ByteArray(32).also(random::nextBytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            approvedToken = secret
            approvalExpiresAt = SystemClock.elapsedRealtime() + GRANT_MS
            remainingActions = MAX_ACTIONS
            request.token = secret
            pending = null
            ui = AgentUiState(allowed = true, remainingSeconds = GRANT_MS.toInt() / 1000)
            request.latch.countDown()
        }
    }

    fun denyPending() {
        synchronized(stateLock) {
            pending?.latch?.countDown()
            pending = null
            ui = AgentUiState()
        }
    }

    fun manualTakeover() {
        val needsRevoke = synchronized(stateLock) { approvedToken != null || pending != null }
        if (needsRevoke) revoke()
    }

    fun revoke() {
        synchronized(stateLock) {
            approvedToken = null
            approvalExpiresAt = 0
            remainingActions = 0
            pending?.latch?.countDown()
            pending = null
            ui = AgentUiState()
        }
        val released = synchronized(ioLock) { output.releaseAll() }
        if (released.isFailure) ui = AgentUiState(error = "USB release uncertain")
    }

    override fun close() {
        closed = true
        revoke()
        runCatching { server?.close() }
    }

    private fun serve() {
        try {
            ServerSocket().use { listener ->
                listener.reuseAddress = true
                listener.bind(InetSocketAddress("127.0.0.1", PORT), 2)
                listener.soTimeout = 1_000
                server = listener
                while (!closed) {
                    try {
                        listener.accept().use(::handle)
                    } catch (_: SocketTimeoutException) {
                        expireIfNeeded()
                    } catch (_: IOException) {
                        // A failed/aborted client must not revoke an existing grant.
                        // The next explicitly requested operation decides what to do.
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) ui = AgentUiState(error = "Agent port unavailable")
        } finally {
            server = null
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = PAIR_TIMEOUT_MS.toInt() + 5_000
        val result = try {
            dispatch(JSONObject(readBoundedLine(socket)))
        } catch (e: Exception) {
            response(false, "rejected", error = "invalid_request")
        }
        runCatching {
            socket.getOutputStream().write((result.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
        }
    }

    private fun readBoundedLine(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= MAX_REQUEST_BYTES) {
            val next = input.read()
            if (next == 10) return bytes.toString("UTF-8")
            if (next < 0) throw IOException("missing newline")
            bytes.write(next)
        }
        throw IOException("request too large")
    }

    private fun dispatch(request: JSONObject): JSONObject {
        expireIfNeeded()
        val operation = request.optString("op")
        if (operation == "info") {
            return response(true, "ready")
                .put("foreground", foreground)
                .put("awaiting_approval", synchronized(stateLock) { pending != null })
                .put("approved", synchronized(stateLock) { approvedToken != null })
        }
        if (operation == "pair") return pair()

        val token = request.optString("token")
        if (!isAuthorized(token)) return response(false, "denied", error = "approval_required_or_expired")
        if (operation == "status") {
            return response(true, "authorized")
                .put("remaining_seconds", synchronized(stateLock) {
                    ((approvalExpiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 1000).toInt()
                })
                .put("remaining_actions", synchronized(stateLock) { remainingActions })
        }
        if (operation == "receipt") {
            val id = request.optString("request_id")
            return synchronized(stateLock) {
                receipts[id]?.let { JSONObject(it.toString()) }
                    ?: response(false, "unknown", id, "receipt_unavailable_do_not_replay")
            }
        }
        val id = request.optString("request_id")
        if (!id.matches(Regex("[A-Za-z0-9_-]{8,64}"))) {
            return response(false, "rejected", error = "invalid_request_id")
        }
        synchronized(stateLock) {
            receipts[id]?.let { return JSONObject(it.toString()) }
            if (receipts.size >= MAX_RECEIPTS || remainingActions <= 0) {
                return response(false, "denied", id, "action_budget_exhausted")
            }
            remainingActions--
        }

        val outcome = try {
            perform(operation, request, token)
            response(true, if (operation == "stop") "revoked" else "usb_written", id)
                .put("target_observed", false)
        } catch (e: IllegalArgumentException) {
            response(false, "rejected", id, "invalid_arguments")
        } catch (e: org.json.JSONException) {
            response(false, "rejected", id, "invalid_arguments")
        } catch (e: Exception) {
            response(false, "unknown", id, "usb_result_unknown_do_not_replay")
        }
        synchronized(stateLock) { receipts[id] = JSONObject(outcome.toString()) }
        return outcome
    }

    private fun pair(): JSONObject {
        if (!available || !foreground || closed) return response(false, "denied", error = "open_hidora_first")
        val candidate = synchronized(stateLock) {
            if (pending != null || approvedToken != null) return response(false, "denied", error = "busy_or_already_authorized")
            Pending().also {
                pending = it
                ui = AgentUiState(waitingForApproval = true)
            }
        }
        candidate.latch.await(PAIR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        synchronized(stateLock) {
            if (pending === candidate) {
                pending = null
                ui = AgentUiState()
            }
            val token = candidate.token
            if (token != null && isAuthorized(token)) {
                return response(true, "authorized").put("token", token)
                    .put("remaining_seconds", (GRANT_MS / 1000).toInt())
            }
        }
        return response(false, "denied", error = "approval_declined_or_timed_out")
    }

    private fun perform(operation: String, request: JSONObject, token: String) {
        when (operation) {
            "mouse_move" -> {
                var x = request.getInt("dx").also { require(it in -512..512) }
                var y = request.getInt("dy").also { require(it in -512..512) }
                while (x != 0 || y != 0) {
                    val stepX = x.coerceIn(-MouseReporter.MAX_DELTA, MouseReporter.MAX_DELTA)
                    val stepY = y.coerceIn(-MouseReporter.MAX_DELTA, MouseReporter.MAX_DELTA)
                    write(token) { output.moveMouse(stepX, stepY) }
                    x -= stepX
                    y -= stepY
                }
            }
            "mouse_scroll" -> {
                val vertical = request.getInt("vertical").also { require(it in -10..10) }
                val horizontal = request.getInt("horizontal").also { require(it in -10..10) }
                if (vertical != 0) write(token) { output.scrollMouse(vertical) }
                if (horizontal != 0) write(token) { output.panMouse(horizontal) }
            }
            "mouse_click" -> {
                val bit = when (request.getString("button")) {
                    "left" -> MouseReporter.BUTTON_LEFT
                    "middle" -> MouseReporter.BUTTON_MIDDLE
                    "right" -> MouseReporter.BUTTON_RIGHT
                    else -> throw IllegalArgumentException("button")
                }
                val count = request.getInt("count")
                require(count in 1..2)
                repeat(count) {
                    try {
                        write(token) { output.setMouseButton(bit, true) }
                        Thread.sleep(45)
                    } finally {
                        synchronized(ioLock) {
                            if (isAuthorized(token)) output.setMouseButton(bit, false).getOrThrow()
                        }
                    }
                    if (it == 0 && count == 2) Thread.sleep(70)
                }
            }
            "key_sequence" -> {
                val strokes = request.getJSONArray("strokes")
                require(strokes.length() in 1..MAX_STROKES)
                // Validate the whole batch before sending the first HID event.
                val keys = (0 until strokes.length()).map { index ->
                    val stroke = strokes.getJSONObject(index)
                    val code = stroke.getInt("code")
                    val mods = stroke.getInt("mods")
                    require(code in 4..101 && mods in 0..255)
                    code to mods
                }
                for ((code, mods) in keys) {
                    try {
                        write(token) { output.writeKeyState(mods, setOf(code)) }
                        Thread.sleep(25)
                    } finally {
                        synchronized(ioLock) {
                            if (isAuthorized(token)) output.writeKeyState(0, emptySet()).getOrThrow()
                        }
                    }
                }
            }
            "release" -> write(token) { output.releaseAll() }
            "stop" -> revoke()
            else -> throw IllegalArgumentException("unsupported operation")
        }
    }

    private fun write(token: String, action: () -> Result<Unit>) {
        synchronized(ioLock) {
            if (!isAuthorized(token)) throw IOException("authorization revoked")
            action().getOrElse { throw IOException("USB report failed", it) }
        }
    }

    private fun expireIfNeeded() {
        val expired = synchronized(stateLock) {
            approvedToken != null && SystemClock.elapsedRealtime() >= approvalExpiresAt
        }
        if (expired) revoke()
    }

    private fun isAuthorized(token: String): Boolean = synchronized(stateLock) {
        val expected = approvedToken ?: return@synchronized false
        foreground && !closed && SystemClock.elapsedRealtime() < approvalExpiresAt &&
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.UTF_8),
                token.toByteArray(StandardCharsets.UTF_8)
            )
    }

    private fun response(ok: Boolean, status: String, id: String = "", error: String? = null): JSONObject =
        JSONObject().put("ok", ok).put("status", status).apply {
            if (id.isNotEmpty()) put("request_id", id)
            if (error != null) put("error", error)
        }

    companion object {
        const val PORT = 39083
        private const val GRANT_MS = 10 * 60 * 1000L
        private const val PAIR_TIMEOUT_MS = 20_000L
        private const val MAX_REQUEST_BYTES = 8_192
        private const val MAX_STROKES = 64
        private const val MAX_ACTIONS = 250
        private const val MAX_RECEIPTS = 4_096
    }
}
