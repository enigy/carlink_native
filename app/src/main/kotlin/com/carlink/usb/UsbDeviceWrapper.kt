package com.carlink.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.carlink.logging.Logger
import com.carlink.logging.logDebug
import com.carlink.logging.logWarn
import com.carlink.protocol.KnownDevices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val ACTION_USB_PERMISSION = "com.carlink.USB_PERMISSION"

// How long ONE caller waits on the system permission dialog before giving up its wait.
// 180s (was 60s): since [196] a timeout no longer costs the user their dialog — the dialog
// stays up and the next attempt re-attaches to it instead of raising a new one — so a longer
// wait is free, and it keeps the app's own state machine from cycling pointlessly while the
// user is walking up to the truck. Cancellation is driven by the DETACH broadcast, not this
// clock; this is only a safety cap so the state machine can't wedge.
private const val PERMISSION_WAIT_MS = 180_000L
private const val MAX_PAYLOAD_SIZE = 2 * 1024 * 1024 // 2MB — reject corrupted headers
// 15s chosen with headroom over the adapter's ~10s heartbeat window (see
// documents/reference/heartbeat_analysis.md). If no data arrives at all inside this window
// after loop start, the USB IN endpoint is assumed dead. Live validation 2026-04-20:
// timeout fired correctly at 07:29:26 after ~70s of post-handshake silence
// (logcat_20260420_063729_carlink.txt:15735); zero spurious 15s timeouts across 6 sessions.
private const val INITIAL_RESPONSE_TIMEOUT_MS = 15_000L

// Mid-session silence threshold: how long the adapter may send NO data (after data was
// flowing) before we treat it as not responding. Measured by WALL CLOCK — see the reading
// loop. Set to 60s, honoring the original "2 × 30s" intent, but enforced as genuine elapsed
// time so brief gaps (the adapter between scan messages while re-pairing the phone) don't
// trip a teardown. A teardown here closes the device, which makes the CPC200 re-enumerate
// (→ USB-permission re-prompt) and aborts the in-progress wireless re-pair — the core of the
// "stuck loop, needs Reset" spiral. Patience here lets the adapter keep trying to reach the
// phone on the existing connection instead.
private const val MID_SESSION_SILENCE_MS = 60_000L

/**
 * One outstanding system USB-permission dialog, shared by every connection attempt targeting
 * the same device instance ([key] is the `/dev/bus/usb/...` path, which is unique per
 * enumeration).
 *
 * Held process-globally (see [UsbDeviceWrapper.pendingPermission]) because the dialog outlives
 * any single [UsbDeviceWrapper] — the reconnect loop builds a fresh wrapper per attempt. Owning
 * the listeners here rather than on the requesting coroutine also means a grant or denial is
 * still observed however long after the requesting attempt gave up, closing the blind spot that
 * the old 15s post-cancellation grace left (a denial landing later was never logged at all).
 */
internal class PendingPermissionRequest(
    val key: String,
    private val appContext: Context,
    private val log: (String) -> Unit,
) {
    /** Completes once — with the user's answer, or false if the device went away. */
    private val result = CompletableDeferred<Boolean>()

    /**
     * Connection attempts currently blocked on this dialog. A waiter giving up does NOT settle
     * [result] (the dialog stays up), so this counter — not the deferred's state — is what tells
     * a grant whether anyone is still there to receive it.
     */
    private val waiters = AtomicInteger(0)

    private var permissionReceiver: BroadcastReceiver? = null
    private var detachReceiver: BroadcastReceiver? = null

    /** Wait up to [timeoutMs] for an answer. Null means this caller gave up; the dialog lives on. */
    suspend fun await(timeoutMs: Long): Boolean? {
        waiters.incrementAndGet()
        try {
            return withTimeoutOrNull(timeoutMs) { result.await() }
        } finally {
            waiters.decrementAndGet()
        }
    }

    /** Register the grant/deny and detach listeners. Call once, before raising the dialog. */
    fun arm() {
        permissionReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (ACTION_USB_PERMISSION != intent.action) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    resolve(granted, if (granted) "granted" else "denied")
                }
            }

        // RECEIVER_NOT_EXPORTED — only this app sends the permission broadcast. The detach
        // action is a protected system broadcast, which is delivered regardless of the flag
        // (same pattern as MainActivity.registerUsbDetachReceiver).
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // The DEVICE cancels this wait, not a clock. A Reset press, ignition-off, or the
        // adapter's own reset watchdog re-enumerates the CPC200 — at which point the dialog
        // authorises an instance that no longer exists and waiting on it is pointless.
        detachReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (UsbManager.ACTION_USB_DEVICE_DETACHED != intent.action) return
                    val detached =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    if (detached?.deviceName == key) resolve(false, "device detached")
                }
            }

        ContextCompat.registerReceiver(
            appContext,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Settle this request and unregister. A GRANT that lands after every waiter has given up
     * still kicks a fresh connection via [UsbDeviceWrapper.onLatePermissionGrant], so the app
     * self-recovers instead of sitting idle until a manual Reset Device.
     */
    private fun resolve(
        granted: Boolean,
        reason: String,
    ) {
        val late = waiters.get() == 0
        unregister()
        UsbDeviceWrapper.clearPending(this)

        if (!result.complete(granted)) {
            // Already settled by a concurrent broadcast — nothing further to do.
            return
        }

        log("USB permission $reason for $key" + if (late) " (late — no attempt waiting)" else "")

        if (granted && late) {
            try {
                UsbDeviceWrapper.onLatePermissionGrant?.invoke()
            } catch (e: Exception) {
                log("onLatePermissionGrant handler error: ${e.message}")
            }
        }
    }

    /** Abandon this request without an answer (the adapter re-enumerated under us). */
    fun dispose(reason: String) {
        unregister()
        UsbDeviceWrapper.clearPending(this)
        if (result.complete(false)) log("Dropping stale USB permission dialog for $key — $reason")
    }

    private fun unregister() {
        listOfNotNull(permissionReceiver, detachReceiver).forEach { receiver ->
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered
            }
        }
        permissionReceiver = null
        detachReceiver = null
    }
}

/**
 * USB Device Wrapper for Carlinkit Adapter Communication.
 *
 * Provides a high-level interface for USB device operations including:
 * - Device discovery and permission handling
 * - Connection lifecycle management
 * - Bulk transfer operations for data exchange
 * - Interface claiming and endpoint configuration
 *
 * Thread model: the reading loop runs on one dedicated "USB-ReadLoop" daemon thread.
 * [write] can be called from several threads (heartbeat timer, mic capture, frame
 * interval, UI); the atomic counters in this file cover statistics only — the
 * underlying UsbDeviceConnection.bulkTransfer is NOT documented thread-safe on a
 * single endpoint. See AdapterDriver's class KDoc for the full threading contract
 * and why the current writer cadences make this safe in practice.
 *
 * Known failure modes worth documenting up-front:
 * - Header desync (on parse error or oversized header) has no scan-for-magic resync;
 *   recovery requires reconnect. See the reading-loop body for details.
 * - Chunk-buffer over-read in the non-video payload path: if a single USB transaction
 *   delivers more than one message's worth of bytes, the tail (containing the next
 *   header) can be lost. Latent — CPC200 bulk transfers align one-msg-per-transaction
 *   in practice.
 * - close() ↔ in-flight bulkTransfer race: close() calls stopReadingLoop().join(1000);
 *   if the read thread is blocked in bulkTransfer, join times out and teardown
 *   proceeds while the call is still mid-flight. Android unsticks pending transfers
 *   by returning -1 on endpoint close, so it survives.
 */
class UsbDeviceWrapper(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val logCallback: (String) -> Unit,
) {
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private val _isOpened = AtomicBoolean(false)
    private val _isReadingLoopActive = AtomicBoolean(false)

    // @Volatile guards visibility only. Read/write happens from startReadingLoop
    // (creator) and stopReadingLoop/close (joiner + nulling); mutual exclusion comes
    // from the CAS on _isReadingLoopActive in stopReadingLoop, not from volatility.
    @Volatile private var readLoopThread: Thread? = null

    val isOpened: Boolean get() = _isOpened.get()
    val isReadingLoopActive: Boolean get() = _isReadingLoopActive.get()

    val vendorId: Int get() = device.vendorId
    val productId: Int get() = device.productId
    val deviceName: String get() = device.deviceName

    // Performance tracking — atomic because write() is called from multiple threads
    // (heartbeat timer, mic capture timer, frame interval coroutine, UI thread).
    // NOTE: atomics cover STATS ONLY; the underlying bulkTransfer is not documented
    // thread-safe. See class KDoc and AdapterDriver's threading section for why
    // concurrent write() calls survive today.
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val sendCount = AtomicInteger(0)
    private val receiveCount = AtomicInteger(0)
    private val sendErrors = AtomicInteger(0)
    private val receiveErrors = AtomicInteger(0)

    /**
     * Check if we have permission to access the USB device.
     */
    fun hasPermission(): Boolean = usbManager.hasPermission(device)

    /**
     * Request USB permission from the user, showing the system dialog if it isn't already held.
     *
     * ONE dialog per device instance. The reconnect loop calls this on every attempt while
     * disconnected; before [196] each call fired its own `UsbManager.requestPermission()`, so an
     * unanswered dialog was churned out from under the user on a ~90s cycle. Observed
     * 2026-07-28: ten requests across 55 minutes, seven of them 60s timeouts against the SAME
     * device instance (/dev/bus/usb/001/029, attached the whole time) before a tap finally
     * landed. The cluster showed no turn-by-turn for that entire window simply because the
     * adapter was never opened — and each Reset press made it worse, re-enumerating the adapter
     * (017 → 023 → 029) and destroying the pending dialog with it.
     *
     * Now the first caller arms the dialog; every later caller targeting the same device
     * instance awaits that same dialog. A wait timing out does NOT tear the dialog down — it
     * stays on screen, and the next attempt re-attaches to it.
     *
     * Cancellation is driven by the DEVICE, not a clock: a detach broadcast for this instance
     * resolves the wait immediately, because the dialog authorises a device that no longer
     * exists. See [PendingPermissionRequest].
     *
     * @param timeoutMs How long THIS caller waits. See [PERMISSION_WAIT_MS].
     * @return true if granted; false if denied, detached, or this caller's wait expired
     */
    suspend fun requestPermission(timeoutMs: Long = PERMISSION_WAIT_MS): Boolean {
        if (usbManager.hasPermission(device)) {
            log("Permission already granted for ${device.deviceName}")
            // Permission can land without our dialog being the one that delivered it (the
            // MediaBrowserService boot probe, or a "use by default" association). Drop any
            // dialog we're still holding for this device so the slot and its receivers don't
            // linger — nothing will ever resolve them.
            synchronized(permissionLock) { pendingPermission?.takeIf { it.key == device.deviceName } }
                ?.dispose("permission already held")
            return true
        }

        // Register on the APPLICATION context (not the Activity context) so the listeners
        // survive Activity teardown and the connection coroutine's cancellation.
        val appContext = context.applicationContext
        val key = device.deviceName

        val pending =
            synchronized(permissionLock) {
                pendingPermission?.let { current ->
                    if (current.key == key) {
                        log("Permission dialog already showing for $key — waiting on it (no new prompt)")
                        return@synchronized current
                    }
                    // Different instance: the adapter re-enumerated, so the old dialog is for a
                    // device that is gone. Drop it before arming a new one.
                    current.dispose("superseded by $key")
                }

                log("Requesting USB permission for $key...")
                PendingPermissionRequest(key, appContext, ::log).also { fresh ->
                    pendingPermission = fresh
                    fresh.arm()
                    usbManager.requestPermission(device, buildPermissionIntent(appContext))
                }
            }

        return pending.await(timeoutMs) ?: run {
            // Deliberately does NOT dispose: the dialog is still on screen and the listeners are
            // still armed. This caller simply stopped waiting; the next attempt picks it back up,
            // and a grant arriving meanwhile still fires [onLatePermissionGrant].
            log("USB permission wait expired after ${timeoutMs / 1000}s — dialog still showing for $key")
            false
        }
    }

    /**
     * Build the PendingIntent the system fires with the user's answer.
     *
     * Explicit Intent (package set) for Android 12+ security. FLAG_MUTABLE (API 31+) is required
     * because UsbManager injects EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED at send time; pre-31
     * PendingIntents are mutable by default, so the flag is omitted there (it does not exist).
     * The request code packs VID into the high 16 bits and PID into the low 16 so PendingIntents
     * for different device pairs in KnownDevices never collide (both fields are 16-bit).
     */
    private fun buildPermissionIntent(appContext: Context): PendingIntent {
        val mutableFlag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            appContext,
            (vendorId shl 16) or productId,
            Intent(ACTION_USB_PERMISSION).apply { setPackage(appContext.packageName) },
            mutableFlag or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Open the USB device and claim the interface.
     *
     * @return true if device was opened successfully
     */
    fun open(): Boolean {
        if (_isOpened.get()) {
            log("Device already opened")
            return true
        }

        // Check permission
        if (!usbManager.hasPermission(device)) {
            log("No permission for device ${device.deviceName}")
            return false
        }

        // Open connection
        connection = usbManager.openDevice(device)
        if (connection == null) {
            log("Failed to open device connection")
            return false
        }

        // DIAGNOSTIC: log the USB descriptor identity now that the device is open (permission
        // is held). Android only PERSISTS the "always allow" USB grant for devices that expose
        // a serial number; a null/blank serial means the grant is session-only and the user is
        // re-prompted on every re-enumeration (cold boot) / process restart. `persistable`
        // tells us whether remembering the permission is even possible for this adapter.
        try {
            val serial = device.serialNumber
            log(
                "[USB_IDENTITY] name=${device.deviceName} " +
                    "VID=0x${vendorId.toString(16)} PID=0x${productId.toString(16)} " +
                    "serial=${serial ?: "<null>"} " +
                    "mfr=${device.manufacturerName ?: "<null>"} " +
                    "product=${device.productName ?: "<null>"} " +
                    "persistable=${!serial.isNullOrBlank()}",
            )
        } catch (e: SecurityException) {
            log("[USB_IDENTITY] serial read denied (no permission): ${e.message}")
        }

        // Find and claim the bulk transfer interface
        if (!claimBulkInterface()) {
            connection?.close()
            connection = null
            return false
        }

        _isOpened.set(true)
        log("Device opened: VID=0x${vendorId.toString(16)} PID=0x${productId.toString(16)}")
        return true
    }

    /**
     * Request permission if needed and open the USB device.
     * This is a convenience suspend function that combines requestPermission() and open().
     *
     * @return true if device was opened successfully
     */
    suspend fun openWithPermission(): Boolean {
        if (!hasPermission()) {
            if (!requestPermission()) {
                return false
            }
        }
        return open()
    }

    /**
     * Close the USB device and release resources.
     */
    fun close() {
        stopReadingLoop()

        claimedInterface?.let { iface ->
            try {
                connection?.releaseInterface(iface)
            } catch (e: Exception) {
                log("Error releasing interface: ${e.message}")
            }
        }
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null

        connection?.close()
        connection = null
        _isOpened.set(false)

        log(
            "Device closed (sent: ${sendCount.get()}/${bytesSent.get()} bytes, " +
                "received: ${receiveCount.get()}/${bytesReceived.get()} bytes)",
        )
    }

    /**
     * Write data to the USB device.
     *
     * @param data Data to send
     * @param timeout Timeout in milliseconds
     * @return Number of bytes actually sent, or -1 on error
     */
    fun write(
        data: ByteArray,
        timeout: Int = 1000,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot write: device not open")
                return -1
            }

        val endpoint =
            outEndpoint ?: run {
                log("Cannot write: no OUT endpoint")
                return -1
            }

        return try {
            val result = conn.bulkTransfer(endpoint, data, data.size, timeout)
            if (result >= 0) {
                bytesSent.addAndGet(result.toLong())
                sendCount.incrementAndGet()
            } else {
                sendErrors.incrementAndGet()
            }
            result
        } catch (e: Exception) {
            sendErrors.incrementAndGet()
            log("Write error: ${e.message}")
            -1
        }
    }

    /**
     * Read data from the USB device.
     *
     * @param buffer Buffer to receive data
     * @param timeout Timeout in milliseconds
     * @return Number of bytes read, or -1 on error/timeout
     */
    fun read(
        buffer: ByteArray,
        timeout: Int = 1000,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot read: device not open")
                return -1
            }

        val endpoint =
            inEndpoint ?: run {
                log("Cannot read: no IN endpoint")
                return -1
            }

        return try {
            val result = conn.bulkTransfer(endpoint, buffer, buffer.size, timeout)
            if (result >= 0) {
                bytesReceived.addAndGet(result.toLong())
                receiveCount.incrementAndGet()
            } else if (result != -1) {
                // -1 is timeout, not an error
                receiveErrors.incrementAndGet()
            }
            result
        } catch (e: Exception) {
            receiveErrors.incrementAndGet()
            log("Read error: ${e.message}")
            -1
        }
    }

    /**
     * Callback interface for direct video data processing.
     * [DIRECT_HANDOFF]: Data is already read into a buffer by the read loop.
     * Processor receives the buffer directly — no callback, no copy.
     */
    interface VideoDataProcessor {
        /**
         * Process video data directly. Data is valid only for duration of this call.
         *
         * @param data Buffer containing video payload (including 20-byte video header)
         * @param dataLength Actual bytes read into data
         * @param sourcePtsMs Source presentation timestamp in milliseconds from video header
         */
        fun processVideoDirect(
            data: ByteArray,
            dataLength: Int,
            sourcePtsMs: Int,
        )
    }

    /**
     * Callback interface for reading loop events.
     */
    interface ReadingLoopCallback {
        fun onMessage(
            type: Int,
            data: ByteArray?,
            dataLength: Int,
        )

        fun onError(error: String)
    }

    /**
     * Start the continuous reading loop.
     *
     * @param callback Callback for received messages
     * @param timeout Read timeout in milliseconds
     * @param videoProcessor Optional processor for direct video data handling (bypasses message parsing)
     */
    fun startReadingLoop(
        callback: ReadingLoopCallback,
        timeout: Int = 30000,
        videoProcessor: VideoDataProcessor? = null,
    ) {
        if (_isReadingLoopActive.getAndSet(true)) {
            log("Reading loop already active")
            return
        }

        Thread {
            // Elevate thread priority: USB read feeds BOTH audio and video pipelines.
            // Must be >= video codec priority to prevent audio underruns from data starvation.
            // Using -10 (between URGENT_DISPLAY=-8 and URGENT_AUDIO=-19) to match
            // MediaCodec_loop priority (see documents/reference/gminfo/projection/cpc200_integration.md:110).
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY - 2)

            log("Reading loop started")
            val headerBuffer = ByteArray(16)

            // Pre-allocate video buffer to avoid per-frame allocation (reduces GC pressure at 60fps)
            // Initial size 256KB covers most frames; grows if needed (rare for 1080p H.264).
            // Growth is MONOTONIC (never shrinks); bounded by MAX_PAYLOAD_SIZE.
            var videoBuffer = ByteArray(256 * 1024)

            // Pre-allocate audio buffer to avoid per-packet allocation (~17 packets/sec).
            // Initial size 16KB covers typical 11532-byte audio payloads; grows if needed
            // (monotonic, bounded by MAX_PAYLOAD_SIZE).
            // REUSE CONTRACT: when AUDIO_DATA is delivered via callback.onMessage, the
            // returned payload is this same audioBuffer. Downstream consumers MUST copy
            // or fully consume bytes before the next AUDIO_DATA arrives on this thread.
            var audioBuffer = ByteArray(16 * 1024)

            // Pre-allocate chunk buffer for non-video message reads (audio, commands, etc.)
            val chunkBuffer = ByteArray(16384)

            // Timeout detection: two separate checks —
            // 1. Initial response: adapter MUST respond to Open within INITIAL_RESPONSE_TIMEOUT_MS.
            //    If no data arrives at all, the USB IN endpoint is dead (adapter can't write).
            // 2. Mid-session silence: if data was flowing and stops, adapter disconnected/stalled.
            var hasReceivedData = false
            // Wall-clock timestamp of the last data actually received. We must NOT count
            // bulkTransfer -1 returns as fixed-duration timeouts: bulkTransfer can return -1 well
            // before the full read `timeout` elapses (transient bad state, or the adapter briefly
            // quiet between phone-scan messages), so "N consecutive -1s = N × timeout" fires far
            // too early — observed tearing the adapter down ~3s into an 11s session the log
            // mislabeled "(60s)". See MID_SESSION_SILENCE_MS.
            var lastDataMs = System.currentTimeMillis()
            val initialResponseDeadline = System.currentTimeMillis() + INITIAL_RESPONSE_TIMEOUT_MS

            try {
                while (_isReadingLoopActive.get() && _isOpened.get()) {
                    // Read header
                    val headerResult = read(headerBuffer, timeout)
                    if (headerResult != 16) {
                        if (_isReadingLoopActive.get()) {
                            if (headerResult == -1) {
                                if (!hasReceivedData) {
                                    // No data ever received — check initial response deadline
                                    if (System.currentTimeMillis() >= initialResponseDeadline) {
                                        log(
                                            "Adapter not responding: no data received within " +
                                                "${INITIAL_RESPONSE_TIMEOUT_MS / 1000}s of connection — " +
                                                "USB IN endpoint may be dead",
                                        )
                                        callback.onError("USB read timeout — no initial response from adapter")
                                        break
                                    }
                                } else {
                                    // Data was flowing, now a read returned no data. Only treat as
                                    // "adapter not responding" after a GENUINE wall-clock gap — brief
                                    // gaps (between scan messages, transient -1s) are tolerated so we
                                    // don't tear down a live adapter that's still trying to reach the
                                    // phone (which would force a re-enumerate + permission re-prompt).
                                    val silentMs = System.currentTimeMillis() - lastDataMs
                                    if (silentMs >= MID_SESSION_SILENCE_MS) {
                                        log(
                                            "Adapter silent: ${silentMs / 1000}s of no data after data was " +
                                                "flowing — treating as not responding",
                                        )
                                        callback.onError("USB read timeout — adapter not responding")
                                        break
                                    }
                                }
                                // Guard against busy-spin: bulkTransfer can return -1 faster than
                                // `timeout` during a transient bad state. A short sleep keeps the
                                // read thread from pegging a core while we wait out the silence window.
                                Thread.sleep(200)
                                continue
                            }
                            log("Incomplete header read: $headerResult bytes")
                        }
                        continue
                    }

                    // Successful header read — record data arrival for wall-clock silence tracking.
                    lastDataMs = System.currentTimeMillis()
                    hasReceivedData = true

                    // Parse header.
                    // DESYNC HAZARD: on failure we `continue`, which reads the next 16 bytes
                    // from wherever the stream currently is — no scan-for-magic resync is
                    // performed. If the stream is misaligned, every subsequent header parse
                    // will fail until the adapter reconnects. Acceptable today because the
                    // CPC200 aligns one-message-per-bulk-transaction in practice; if stream
                    // corruption becomes a real scenario, add PROTOCOL_MAGIC-byte search here.
                    val header =
                        try {
                            com.carlink.protocol.MessageParser
                                .parseHeader(headerBuffer)
                        } catch (e: com.carlink.protocol.HeaderParseException) {
                            val hex = headerBuffer.take(16).joinToString(" ") { "%02X".format(it) }
                            logWarn(
                                "Header parse error: ${e.message} raw=[$hex]",
                                tag = com.carlink.logging.Logger.Tags.PROTO_UNKNOWN,
                            )
                            continue
                        }

                    // Reject corrupted headers with implausible payload sizes.
                    // Same desync hazard as above — no attempt to drain the phantom payload.
                    if (header.length > MAX_PAYLOAD_SIZE) {
                        val hex = headerBuffer.take(16).joinToString(" ") { "%02X".format(it) }
                        logWarn(
                            "Corrupted header: length=${header.length} exceeds max raw=[$hex]",
                            tag = com.carlink.logging.Logger.Tags.PROTO_UNKNOWN,
                        )
                        continue
                    }

                    // Demux split for 0x06 (VIDEO_DATA) and 0x2C (NAVI_VIDEO_DATA / AltVideo):
                    //   0x06 → main IHU video pipeline (videoProcessor → H264Renderer → Surface).
                    //   0x2C → ClusterHomeDisplay forwarder (com.carlink.ipc.NaviVideoForwarder),
                    //          which strips the 20B video header and broadcasts H.264 Annex-B
                    //          payloads to bound consumer sinks over AIDL.
                    //
                    // Both paths share the chunked USB read into videoBuffer + the source-PTS
                    // extraction below; dispatch happens after totalRead == header.length.
                    //
                    // Previously both types OR'd into the same videoProcessor call — a latent
                    // bug: a 0x2C frame would stomp the main-video MediaCodec with nav-resolution
                    // SPS/PPS and stall the decoder. The split fixes that.
                    //
                    // 0x2C is only accepted when NaviVideoSingleton.enabled is true (debug build
                    // on AAOS emulator); on prod/non-emulator the adapter doesn't emit 0x2C
                    // anyway because MessageSerializer skips naviScreenInfo BoxSettings, and any
                    // unexpected 0x2C is drained via the non-video branch below.
                    //
                    // Gated on header.length > 0: zero-length video headers fall through to
                    // the non-video branch below where payload ends up null and callback.onMessage
                    // fires with dataLength=0 — this is the VideoStreamingSignal sentinel path
                    // consumed by MessageParser.kt (see the VideoStreamingSignal object for
                    // the synthetic-message contract).
                    val isMainVideo =
                        header.type == com.carlink.protocol.MessageType.VIDEO_DATA &&
                            videoProcessor != null
                    val isNaviVideo =
                        header.type == com.carlink.protocol.MessageType.NAVI_VIDEO_DATA &&
                            com.carlink.ipc.NaviVideoSingleton.enabled
                    if ((isMainVideo || isNaviVideo) && header.length > 0) {
                        val conn = connection
                        val endpoint = inEndpoint
                        if (conn != null && endpoint != null) {
                            try {
                                // Reuse pre-allocated buffer; grow only if needed (rare)
                                if (videoBuffer.size < header.length) {
                                    videoBuffer = ByteArray(maxOf(header.length, videoBuffer.size * 2))
                                }
                                var totalRead = 0
                                var readAttempts = 0
                                var lastChunkResult = 0

                                while (totalRead < header.length && _isReadingLoopActive.get()) {
                                    val remaining = header.length - totalRead
                                    val chunkSize = minOf(remaining, 16384)
                                    readAttempts++
                                    val chunkRead =
                                        conn.bulkTransfer(
                                            endpoint,
                                            videoBuffer,
                                            totalRead,
                                            chunkSize,
                                            timeout,
                                        )
                                    lastChunkResult = chunkRead
                                    if (chunkRead > 0) {
                                        totalRead += chunkRead
                                        bytesReceived.addAndGet(chunkRead.toLong())
                                        receiveCount.incrementAndGet()
                                    } else if (chunkRead <= 0) {
                                        logDebug(
                                            "[VIDEO_READ] Read failed: attempts=$readAttempts, " +
                                                "got=$totalRead/${header.length}, lastResult=$chunkRead",
                                            tag = Logger.Tags.VIDEO_USB,
                                        )
                                        break
                                    }
                                }

                                // Extract source PTS from video header (offset 12) if we have enough data
                                val sourcePts =
                                    if (totalRead >= 16) {
                                        extractPtsFromHeader(videoBuffer)
                                    } else {
                                        logDebug(
                                            "[VIDEO_READ] Incomplete read for PTS: got=$totalRead bytes, need>=16",
                                            tag = Logger.Tags.VIDEO_USB,
                                        )
                                        0
                                    }

                                if (totalRead == header.length) {
                                    if (isMainVideo) {
                                        videoProcessor.processVideoDirect(videoBuffer, totalRead, sourcePts)
                                    } else {
                                        // isNaviVideo — gate already enforced upstream.
                                        // Forwarder copies the Annex-B payload out before AIDL
                                        // broadcast, so the shared videoBuffer is safe to reuse
                                        // for the next read immediately.
                                        com.carlink.ipc.NaviVideoSingleton.forwarder
                                            .onUsbFrame(videoBuffer, totalRead)
                                    }
                                } else {
                                    logDebug(
                                        "[VIDEO_READ] Partial frame dropped: got=$totalRead/${header.length} " +
                                            "attempts=$readAttempts, lastResult=$lastChunkResult",
                                        tag = Logger.Tags.VIDEO_USB,
                                    )
                                }

                                // Notify callback that video data was received
                                callback.onMessage(header.type.id, null, 0)
                            } catch (e: Exception) {
                                log("Video processing error (non-fatal): ${e.message}")
                                receiveErrors.incrementAndGet()
                            }
                        }
                        continue
                    }

                    // Read payload for non-video messages (audio, commands, media metadata, etc.)
                    // For AUDIO_DATA: reuse pre-allocated audioBuffer to avoid per-packet allocation.
                    // CHUNK OVER-READ HAZARD: read(chunkBuffer, timeout) may deliver more bytes
                    // than (header.length - totalRead). The minOf() below clamps the COPY to
                    // exactly header.length, but the untranscribed tail of chunkBuffer is
                    // overwritten on the next iteration. If a single USB transaction ever
                    // delivers more than one message's worth (rare but possible), the next
                    // header is lost and the loop desyncs. Latent today.
                    val isAudio = header.type == com.carlink.protocol.MessageType.AUDIO_DATA
                    var dataLength = 0
                    val payload: ByteArray? =
                        if (header.length > 0) {
                            val payloadBuffer =
                                if (isAudio) {
                                    // Grow audioBuffer if needed (doubling strategy)
                                    if (header.length > audioBuffer.size) {
                                        audioBuffer = ByteArray(maxOf(header.length, audioBuffer.size * 2))
                                    }
                                    audioBuffer
                                } else {
                                    ByteArray(header.length)
                                }
                            var totalRead = 0
                            while (totalRead < header.length && _isReadingLoopActive.get()) {
                                val chunkRead = read(chunkBuffer, timeout)
                                if (chunkRead > 0) {
                                    val bytesToCopy = minOf(chunkRead, header.length - totalRead)
                                    System.arraycopy(chunkBuffer, 0, payloadBuffer, totalRead, bytesToCopy)
                                    totalRead += bytesToCopy
                                } else if (chunkRead == -1) {
                                    // Timeout
                                    break
                                }
                            }
                            if (totalRead == header.length) {
                                dataLength = totalRead
                                payloadBuffer
                            } else {
                                logWarn(
                                    "[USB_PARTIAL] Incomplete payload for type=0x${header.type.id.toString(16)}: " +
                                        "got=$totalRead/${header.length}B — dropped",
                                    tag = com.carlink.logging.Logger.Tags.PROTO_UNKNOWN,
                                )
                                null
                            }
                        } else {
                            null
                        }

                    // Deliver message to callback
                    try {
                        callback.onMessage(header.type.id, payload, dataLength)
                    } catch (e: Exception) {
                        log("Message callback error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (_isReadingLoopActive.get()) {
                    log("Reading loop error: ${e.message}")
                    callback.onError(e.message ?: "Unknown error")
                }
            } finally {
                _isReadingLoopActive.set(false)
                log("Reading loop stopped")
            }
        }.apply {
            name = "USB-ReadLoop"
            isDaemon = true
            start()
        }.also { readLoopThread = it }
    }

    /**
     * Stop the reading loop.
     *
     * Best-effort: if the read thread is blocked inside UsbDeviceConnection.bulkTransfer
     * at the moment this is called, the join(1000) will time out silently and
     * readLoopThread is nulled anyway. Subsequent close() then releases the interface
     * and closes the connection, which Android uses as the signal to unstick the
     * pending transfer (returns -1). The read-loop try/catch absorbs the fallout and
     * flips _isReadingLoopActive=false in its finally block. Net: the loop exits
     * cleanly, but there is a brief window where a closing connection is still in
     * use by the reader thread.
     */
    fun stopReadingLoop() {
        if (!_isReadingLoopActive.getAndSet(false)) return
        try {
            readLoopThread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readLoopThread = null
    }

    // ==================== Private Methods ====================

    // First-match semantics: claims the first interface that exposes BOTH a bulk-IN
    // and bulk-OUT endpoint. If the adapter ever exposes multiple such interfaces,
    // the code claims the lowest-index one and ignores the rest. Known devices (per
    // KnownDevices in MessageTypes.kt) present exactly one matching interface, so
    // this is safe today.
    private fun claimBulkInterface(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)

            // Look for bulk endpoints
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null

            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                        inEp = endpoint
                    } else {
                        outEp = endpoint
                    }
                }
            }

            // If we found both endpoints, claim this interface
            if (inEp != null && outEp != null) {
                val claimed = connection?.claimInterface(iface, true) ?: false
                if (claimed) {
                    claimedInterface = iface
                    inEndpoint = inEp
                    outEndpoint = outEp
                    log("Claimed interface $i: IN=${inEp.address.toString(16)} OUT=${outEp.address.toString(16)}")
                    return true
                }
            }
        }

        log("No suitable bulk interface found")
        return false
    }

    private fun log(message: String) {
        logCallback("[USB] $message")
    }

    companion object {
        /**
         * Invoked when the user grants USB permission AFTER the requesting connection attempt
         * has already ended (a "late" grant). Set once by [com.carlink.CarlinkManager] to kick a
         * fresh connection so the app self-recovers instead of sitting idle until a manual Reset
         * Device. Process-global (the permission receiver outlives the per-connection wrapper);
         * the handler must be idempotent and re-check connection state itself.
         */
        @Volatile
        var onLatePermissionGrant: (() -> Unit)? = null

        /**
         * Guards [pendingPermission]. Connection attempts can start from several coroutines
         * (initial connect, reconnect loop, late-grant kick), and the check-or-create must be
         * atomic or two of them raise two dialogs — the exact churn [196] removes.
         */
        private val permissionLock = Any()

        /**
         * The single in-flight system permission dialog, if any. One slot, not a map: the app
         * only ever talks to one adapter, and a new device instance means the previous dialog
         * is stale by definition.
         */
        private var pendingPermission: PendingPermissionRequest? = null

        /** True while a system permission dialog is on screen awaiting the user's answer. */
        val isPermissionDialogShowing: Boolean
            get() = synchronized(permissionLock) { pendingPermission != null }

        /** Release the slot once [request] has settled. No-op if it was already replaced. */
        internal fun clearPending(request: PendingPermissionRequest) {
            synchronized(permissionLock) {
                if (pendingPermission === request) pendingPermission = null
            }
        }

        /**
         * Find all connected Carlinkit devices.
         */
        fun findDevices(usbManager: UsbManager): List<UsbDevice> =
            usbManager.deviceList.values.filter { device ->
                KnownDevices.isKnownDevice(device.vendorId, device.productId)
            }

        /**
         * Create a wrapper for the first available Carlinkit device.
         */
        fun findFirst(
            context: Context,
            usbManager: UsbManager,
            logCallback: (String) -> Unit,
        ): UsbDeviceWrapper? {
            val device = findDevices(usbManager).firstOrNull() ?: return null
            return UsbDeviceWrapper(context, usbManager, device, logCallback)
        }

        /**
         * Extract source PTS from video header buffer.
         *
         * PTS sits at absolute byte 0x1C (=28) in the full USB frame per
         * documents/reference/.../video_protocol.md. Because [buffer] in the
         * reading-loop call site holds PAYLOAD ONLY (the 16-byte USB header
         * already consumed), the effective offset is 28 - 16 = 12, little-endian Int.
         *
         * The [offset] parameter supports reading the PTS from elsewhere in a larger
         * buffer (not used today; kept for future reuse with buffers that include
         * the 16-byte header).
         */
        fun extractPtsFromHeader(
            buffer: ByteArray,
            offset: Int = 0,
        ): Int {
            if (buffer.size < offset + 16) return 0
            return (buffer[offset + 12].toInt() and 0xFF) or
                ((buffer[offset + 13].toInt() and 0xFF) shl 8) or
                ((buffer[offset + 14].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 15].toInt() and 0xFF) shl 24)
        }
    }
}
