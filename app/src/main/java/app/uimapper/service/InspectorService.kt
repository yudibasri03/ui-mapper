package app.uimapper.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.uimapper.core.AppInfo
import app.uimapper.core.NodeCapture
import app.uimapper.core.UiTree
import app.uimapper.data.AppSettings
import app.uimapper.data.SessionStore
import app.uimapper.model.Bounds
import app.uimapper.model.ElementRef
import app.uimapper.model.ScreenSnapshot
import app.uimapper.model.SessionMode
import app.uimapper.model.UiNode
import app.uimapper.overlay.OverlayController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Read-only accessibility service. It observes window/content events of other apps, captures their
 * UI trees (and optional screenshots) on request, and runs the passive [RouteRecorder]. It never
 * performs clicks, gestures, global actions or any other action inside other apps.
 */
class InspectorService : AccessibilityService(), ServiceHost {

    // ---- ServiceHost ----

    override val context: Context get() = this

    override val scope: CoroutineScope get() = serviceScope

    override val ownPackage: String get() = packageName

    override val overlay: OverlayUi? get() = overlayController

    // ---- state (main thread unless marked @Volatile) ----

    private var serviceScope: CoroutineScope = newScope()
    private var overlayController: OverlayUi? = null
    private var recorder: RouteRecorder? = null
    private var captureNowJob: Job? = null
    private var shutDown = false

    @Volatile private var lastUiEventAt: Long = 0L

    /** Own package, system UI, enabled IMEs and the home launcher. Replaced atomically on refresh. */
    @Volatile private var ignorable: Set<String> = emptySet()
    /** Home launcher packages (a subset of [ignorable]). */
    @Volatile private var homePkgs: Set<String> = emptySet()
    private var ignorableRefreshedAt = 0L

    /** Written on the main thread, read by window IPC running on background threads. */
    @Volatile private var fgPkg: String? = null
    @Volatile private var fgActivity: String? = null
    private val activityClassCache = HashMap<String, Boolean>()
    private val packageVisibleCache = HashMap<String, Boolean>()

    /** Package -> SNAPSHOT session id used by CaptureNow outside a recording. */
    private val snapshotSessions = HashMap<String, String>()

    private val shotMutex = Mutex()
    @Volatile private var lastShotAt = 0L

    private var screenOffRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
        }
    }

    private class WinRoot(
        val layer: Int,
        val active: Boolean,
        val pkg: String,
        val root: AccessibilityNodeInfo,
    )

    /** One read of the window list. */
    private class WindowScan(
        /** Application windows of mappable (non-ignorable) apps. */
        val apps: List<WinRoot>,
        /** The window list was readable and contained at least one application window (of any app). */
        val sawAppWindows: Boolean,
    )

    /** Screen areas painted over in screenshots. */
    private class Covers(val rects: List<Rect>, val shadeOverApp: Boolean)

    @RequiresApi(Build.VERSION_CODES.R)
    private class ShotOutcome(val result: AccessibilityService.ScreenshotResult?, val errorCode: Int)

    // ---- lifecycle ----

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppSettings.init(applicationContext)
        SessionStore.init(applicationContext)

        if (!serviceScope.isActive) serviceScope = newScope()
        shutDown = false

        ignorable = baseIgnorable()
        ignorableRefreshedAt = SystemClock.uptimeMillis()
        serviceScope.launch(Dispatchers.IO) { ignorable = computeIgnorable() }

        overlayController = try {
            OverlayController(this)
        } catch (e: Exception) {
            Log.e(TAG, "Overlay could not be created", e)
            null
        }

        ServiceBridge.update { ServiceState() }

        // Subscribe before publishing connected=true: ServiceBridge.send() refuses while disconnected,
        // and SharedFlow drops emissions made before a subscriber exists.
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ServiceBridge.commands.collect { cmd -> handleCommand(cmd) }
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ServiceBridge.state.collect { st ->
                try {
                    overlayController?.onStateChanged(st)
                } catch (e: Exception) {
                    Log.w(TAG, "Overlay state update failed", e)
                }
            }
        }

        ServiceBridge.update { it.copy(connected = true, status = "Layanan terhubung") }
        logEvent(EventLog.Tag.INFO, "Layanan terhubung")

        // Overlay windows stay above the keyguard: drop everything showing captured data when the device locks.
        if (!screenOffRegistered) {
            try {
                ContextCompat.registerReceiver(
                    this,
                    screenOffReceiver,
                    IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                screenOffRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "Screen-off receiver could not be registered", e)
            }
        }

        if (AppSettings.overlayOnConnect) showOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // No spoken/haptic feedback to interrupt.
    }

    private fun shutdown() {
        if (shutDown) return
        shutDown = true
        logEvent(EventLog.Tag.INFO, "Layanan berhenti")
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Recorder stop failed", e)
        }
        recorder = null
        captureNowJob?.cancel()
        captureNowJob = null
        if (screenOffRegistered) {
            screenOffRegistered = false
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Screen-off receiver unregister failed", e)
            }
        }
        try {
            overlayController?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Overlay destroy failed", e)
        }
        overlayController = null
        serviceScope.cancel()
        fgPkg = null
        fgActivity = null
        activityClassCache.clear()
        packageVisibleCache.clear()
        snapshotSessions.clear()
        ServiceBridge.update { ServiceState() }
    }

    // ---- events (main thread, must stay cheap) ----

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || shutDown) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val type = event.eventType
        if (!isIgnorablePackage(pkg)) {
            when (type) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                -> lastUiEventAt = SystemClock.uptimeMillis()
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                trackForeground(pkg, event.className?.toString())
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isHomePackage(pkg)) {
            // The home screen came to the front: no app is in the foreground any more.
            clearForeground()
        }

        // Any window change (launcher and system UI included) may leave the inspect layer on a stale tree.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isStructuralContentChange(event))
        ) {
            try {
                overlayController?.onScreenMaybeChanged()
            } catch (e: Exception) {
                Log.w(TAG, "Overlay change notification failed", e)
            }
        }

        val rec = recorder
        if (rec != null && ServiceBridge.state.value.mode == ServiceMode.RECORDING) {
            try {
                rec.onEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "Recorder event handling failed", e)
            }
        }

        maybeRefreshIgnorable()
    }

    private fun trackForeground(pkg: String, cls: String?) {
        val prevPkg = fgPkg
        val prevActivity = fgActivity
        // A dialog/popup keeps the previous activity of the same package; a new package resets it.
        var activity = if (pkg == prevPkg) prevActivity else null
        if (!cls.isNullOrEmpty() && isActivityClass(pkg, cls)) activity = cls
        if (pkg != prevPkg || activity != prevActivity) {
            fgPkg = pkg
            fgActivity = activity
            ServiceBridge.update { it.copy(foregroundPkg = pkg, foregroundActivity = activity) }
        }
    }

    private fun clearForeground() {
        if (fgPkg == null && fgActivity == null) return
        fgPkg = null
        fgActivity = null
        ServiceBridge.update { it.copy(foregroundPkg = null, foregroundActivity = null) }
    }

    private fun onScreenOff() {
        if (shutDown) return
        val ov = overlayController ?: return
        try {
            ov.onScreenOff()
        } catch (e: Exception) {
            Log.w(TAG, "Overlay screen-off handling failed", e)
        }
        ServiceBridge.update { it.copy(inspecting = false) }
    }

    private fun isActivityClass(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        activityClassCache[key]?.let { return it }
        val result = try {
            getActivityInfoCompat(ComponentName(pkg, cls))
            true
        } catch (_: PackageManager.NameNotFoundException) {
            // Either not an activity (dialog, popup, view class) or the package is hidden from us by
            // package-visibility filtering; only the latter needs a name-based guess.
            if (isPackageVisible(pkg)) false else looksLikeActivityClass(cls)
        } catch (_: Exception) {
            false
        }
        if (activityClassCache.size >= CACHE_LIMIT) activityClassCache.clear()
        activityClassCache[key] = result
        return result
    }

    private fun isPackageVisible(pkg: String): Boolean {
        packageVisibleCache[pkg]?.let { return it }
        val visible = try {
            getPackageInfoCompat(pkg)
            true
        } catch (_: Exception) {
            false
        }
        if (packageVisibleCache.size >= CACHE_LIMIT) packageVisibleCache.clear()
        packageVisibleCache[pkg] = visible
        return visible
    }

    private fun looksLikeActivityClass(cls: String): Boolean {
        if (cls.startsWith("android.") || cls.startsWith("androidx.") || cls.startsWith("com.android.internal.")) {
            return false
        }
        val simple = cls.substringAfterLast('.').lowercase()
        if ("activity" in simple) return true
        return NON_ACTIVITY_HINTS.none { it in simple }
    }

    @Suppress("DEPRECATION")
    private fun getActivityInfoCompat(cn: ComponentName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getActivityInfo(cn, PackageManager.ComponentInfoFlags.of(0L))
        } else {
            packageManager.getActivityInfo(cn, 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoCompat(pkg: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
        } else {
            packageManager.getPackageInfo(pkg, 0)
        }
    }

    // ---- ignorable packages ----

    /** True for our own app, the system UI, input methods and the home launcher. */
    internal fun isIgnorablePackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return true
        return pkg == packageName || pkg == SYSTEM_UI_PKG || pkg in ignorable
    }

    /** True for the resolved home launcher(s) (never for our own app, system UI or an IME). */
    internal fun isHomePackage(pkg: String?): Boolean = pkg != null && pkg in homePkgs

    private fun baseIgnorable(): Set<String> = setOf(packageName, SYSTEM_UI_PKG)

    private fun maybeRefreshIgnorable() {
        val now = SystemClock.uptimeMillis()
        if (now - ignorableRefreshedAt < IGNORABLE_REFRESH_MS) return
        ignorableRefreshedAt = now
        serviceScope.launch(Dispatchers.IO) { ignorable = computeIgnorable() }
    }

    /** Blocking (binder calls): run on Dispatchers.IO. */
    private fun computeIgnorable(): Set<String> {
        val set = HashSet<String>(baseIgnorable())
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.forEach { info -> set += info.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "IME list unavailable", e)
        }
        try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { ComponentName.unflattenFromString(it)?.packageName }
                ?.let { set += it }
        } catch (_: Exception) {
            // Not readable on some builds; the IME window type is excluded anyway.
        }
        val home = homePackages()
        homePkgs = home
        set += home
        return set
    }

    @Suppress("DEPRECATION")
    private fun homePackages(): Set<String> {
        val out = HashSet<String>()
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val def = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            val defPkg = def?.activityInfo?.packageName
            if (defPkg != null && defPkg !in NOT_A_LAUNCHER) {
                out += defPkg
            } else {
                // No default chosen (ResolverActivity): every installed launcher counts.
                val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    pm.queryIntentActivities(intent, 0)
                }
                for (ri in all) {
                    val p = ri.activityInfo?.packageName ?: continue
                    if (p !in NOT_A_LAUNCHER) out += p
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Home launcher lookup failed", e)
        }
        out.remove(packageName)
        return out
    }

    // ---- windows ----
    //
    // AccessibilityWindowInfo.getRoot() / getChild() are blocking IPC answered by the observed app's UI
    // thread (up to 5 s each when it is busy or hung). They never run on the main thread, which owns the
    // touchable overlay windows: everything below runs detached on Dispatchers.IO via [windowIpc].

    /** Blocking: call on a background thread. */
    private fun scanWindows(): WindowScan {
        val wins: List<AccessibilityWindowInfo> = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "Window list unavailable", e)
            return WindowScan(emptyList(), sawAppWindows = false)
        }
        val out = ArrayList<WinRoot>(wins.size)
        var sawApp = false
        for (w in wins) {
            try {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (w.isInPictureInPictureMode) continue
                sawApp = true
                val root = w.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (isIgnorablePackage(pkg)) continue
                out += WinRoot(w.layer, w.isActive, pkg, root)
            } catch (_: Exception) {
                // Stale window: skip.
            }
        }
        return WindowScan(out, sawApp)
    }

    /** Package of the active application window, else of the top-most one. */
    private fun topPackage(wins: List<WinRoot>): String? =
        wins.firstOrNull { it.active }?.pkg ?: wins.maxByOrNull { it.layer }?.pkg

    /**
     * Foreground app of [scan]. The event-tracked [trackedPkg] is only a fallback when the window list said
     * nothing; when it listed application windows but none of a mappable app (home screen, UI Mapper
     * itself), nothing is in front.
     */
    private fun foregroundFrom(scan: WindowScan, trackedPkg: String?): String? =
        topPackage(scan.apps) ?: if (scan.sawAppWindows) null else trackedPkg?.takeUnless { isIgnorablePackage(it) }

    /** Roots of [pkg]'s windows, bottom-most first. Blocking: call on a background thread. */
    private fun rootsFor(scan: WindowScan, pkg: String): List<AccessibilityNodeInfo> {
        val chosen = scan.apps.filter { it.pkg == pkg }
        if (chosen.isNotEmpty()) return chosen.sortedBy { it.layer }.map { it.root }
        val active: AccessibilityNodeInfo = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val activePkg = try {
            active.packageName?.toString()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        if (isIgnorablePackage(activePkg) || activePkg != pkg) return emptyList()
        return listOf(active)
    }

    /**
     * Runs blocking window IPC detached on Dispatchers.IO, so [CAPTURE_TIMEOUT_MS] always applies to the
     * caller even though the IPC itself cannot be interrupted. The detached job is cancelled when the
     * caller gives up (timeout or its own cancellation); [block] should poll `isActive`.
     */
    private suspend fun <T : Any> windowIpc(block: suspend CoroutineScope.() -> T?): T? {
        val job = serviceScope.async(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Window read failed", e)
                null
            }
        }
        var result: T? = null
        try {
            result = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { job.await() }
        } catch (_: CancellationException) {
            // Either the caller was cancelled (rethrown here) or the detached job was (service shutting down).
            currentCoroutineContext().ensureActive()
        } finally {
            if (result == null) job.cancel()
        }
        return result
    }

    override fun foregroundPackage(): String? = fgPkg?.takeUnless { isIgnorablePackage(it) }

    override suspend fun currentForegroundPackage(): String? {
        val tracked = fgPkg
        return windowIpc { foregroundFrom(scanWindows(), tracked) }
    }

    override fun foregroundActivity(): String? = fgActivity

    override fun lastEventAt(): Long = lastUiEventAt

    override suspend fun awaitIdle(quietMs: Long, timeoutMs: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        delay(MIN_IDLE_WAIT_MS)
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - lastUiEventAt >= quietMs) return true
            if (now - start >= timeoutMs) return false
            delay(IDLE_POLL_MS)
        }
    }

    // ---- capture ----

    override suspend fun captureScreen(targetPkg: String?, withScreenshot: Boolean): CapturedScreen? {
        val trackedPkg = fgPkg
        val trackedActivity = fgActivity
        val (w, h) = screenSize()

        // Window enumeration and tree traversal are blocking IPC into the observed app: both run detached on
        // IO under the timeout, and the traversal stops at the next node once the job is cancelled.
        val snapshot: ScreenSnapshot = windowIpc {
            val scan = scanWindows()
            val pkg = targetPkg ?: foregroundFrom(scan, trackedPkg) ?: return@windowIpc null
            val roots = rootsFor(scan, pkg)
            if (roots.isEmpty()) return@windowIpc null
            val activity = if (trackedPkg == pkg) trackedActivity else null
            NodeCapture.capture(roots, isCancelled = { !isActive })?.let { NodeCapture.toSnapshot(it, pkg, activity, w, h) }
        } ?: return null
        val shot = if (withScreenshot) screenshot(snapshot.root) else null
        return CapturedScreen(snapshot, shot)
    }

    override suspend fun screenshot(tree: UiNode?): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bmp = try {
            shotMutex.withLock { screenshotApi30() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot failed", e)
            logEvent(EventLog.Tag.WARN, "Gagal mengambil tangkapan layar")
            null
        } ?: return null
        // Privacy first: an image that could not be redacted is not kept.
        val keep = try {
            redact(bmp, tree)
        } catch (e: CancellationException) {
            bmp.recycle()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot redaction failed", e)
            false
        }
        if (!keep) {
            bmp.recycle()
            return null
        }
        return bmp
    }

    /**
     * Paints over typed text and passwords (editable / password nodes of [tree]), the keyboard (it shows
     * suggestions learned from the user's typing) and system windows (status bar, heads-up notifications).
     * Returns false when the image must not be kept (window list unreadable, or the notification shade
     * covers the app).
     */
    private suspend fun redact(bmp: Bitmap, tree: UiNode?): Boolean {
        val (sw, sh) = screenSize()
        if (sw <= 0 || sh <= 0) return false
        // getWindows() and window bounds come from the system server, not the observed app.
        val covers = withContext(Dispatchers.IO) { coverRects(sw, sh) } ?: return false
        if (covers.shadeOverApp) return false
        withContext(Dispatchers.Default) {
            val canvas = Canvas(bmp)
            val paint = Paint().apply {
                style = Paint.Style.FILL
                color = REDACT_COLOR
            }
            val sx = bmp.width.toFloat() / sw
            val sy = bmp.height.toFloat() / sh
            for (r in covers.rects) {
                canvas.drawRect(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy, paint)
            }
            if (tree != null) {
                for (n in UiTree.flatten(tree)) {
                    if (!(n.editable || n.password) || n.bounds.isEmpty()) continue
                    val b = n.bounds
                    canvas.drawRect(b.l * sx, b.t * sy, b.r * sx, b.b * sy, paint)
                }
            }
        }
        return true
    }

    /** Bounds of the keyboard and system windows. Null when the window list cannot be read. */
    private fun coverRects(sw: Int, sh: Int): Covers? {
        val wins: List<AccessibilityWindowInfo> = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "Window list unavailable for redaction", e)
            return null
        }
        val screenArea = sw.toLong() * sh.toLong()
        val out = ArrayList<Rect>()
        var shade = false
        for (w in wins) {
            try {
                val type = w.type
                if (type != AccessibilityWindowInfo.TYPE_INPUT_METHOD && type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                val r = Rect()
                w.getBoundsInScreen(r)
                if (r.isEmpty) continue
                // A system window over most of the screen is the expanded shade / a notification panel.
                if (type == AccessibilityWindowInfo.TYPE_SYSTEM && r.width().toLong() * r.height().toLong() * 2 > screenArea) {
                    shade = true
                }
                out += r
            } catch (_: Exception) {
                // Stale window: skip.
            }
        }
        return Covers(out, shade)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun screenshotApi30(): Bitmap? {
        val apiR = Build.VERSION.SDK_INT == Build.VERSION_CODES.R
        val minGap = if (apiR) SHOT_GAP_API30_MS else SHOT_GAP_MS
        val wait = lastShotAt + minGap - SystemClock.uptimeMillis()
        if (wait > 0) delay(wait)

        val ov = overlayController
        var outcome: ShotOutcome? = null
        try {
            if (ov != null) {
                try {
                    ov.setHiddenForCapture(true)
                } catch (e: Exception) {
                    Log.w(TAG, "Overlay hide failed", e)
                }
                delay(OVERLAY_HIDE_SETTLE_MS)
            }
            outcome = withTimeoutOrNull(SHOT_TIMEOUT_MS) { requestShot() }
            if (outcome != null && outcome.result == null &&
                outcome.errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
            ) {
                delay(if (apiR) SHOT_GAP_API30_MS else SHOT_RETRY_MS)
                outcome = withTimeoutOrNull(SHOT_TIMEOUT_MS) { requestShot() }
            }
        } finally {
            if (ov != null) {
                try {
                    ov.setHiddenForCapture(false)
                } catch (e: Exception) {
                    Log.w(TAG, "Overlay unhide failed", e)
                }
            }
        }
        val result = outcome?.result ?: return null
        return withContext(Dispatchers.Default) { toSoftwareBitmap(result) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestShot(): ShotOutcome = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                lastShotAt = SystemClock.uptimeMillis()
                if (cont.isActive) {
                    cont.resume(ShotOutcome(screenshot, 0))
                } else {
                    try {
                        screenshot.hardwareBuffer.close()
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                lastShotAt = SystemClock.uptimeMillis()
                if (cont.isActive) cont.resume(ShotOutcome(null, errorCode))
            }
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot rejected", e)
            if (cont.isActive) cont.resume(ShotOutcome(null, SHOT_ERROR_EXCEPTION))
        }
    }

    /** HARDWARE buffer -> mutable software ARGB_8888 bitmap (so it can be redacted); always closes the buffer. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun toSoftwareBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val sw: Bitmap? = hw.copy(Bitmap.Config.ARGB_8888, true)
            hw.recycle()
            return sw
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot conversion failed", e)
            return null
        } finally {
            try {
                buffer.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun launchApp(pkg: String): Boolean = try {
        val intent = AppInfo.launchIntent(this, pkg)
        if (intent != null) {
            startActivity(intent)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.w(TAG, "Launch failed for $pkg", e)
        false
    }

    @Suppress("DEPRECATION")
    override fun screenSize(): Pair<Int, Int> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    } catch (_: Exception) {
        val dm = resources.displayMetrics
        dm.widthPixels to dm.heightPixels
    }

    // ---- live text editing (opt-in) ----
    //
    // The one place UI Mapper acts on another app instead of only observing it. Gated behind
    // [AppSettings.allowTextEditing] (off by default) and driven only by an explicit user request from the
    // inspect panel. It never dispatches taps/gestures, never reads the field's existing text, and never
    // stores or logs the text that is written.

    override suspend fun setNodeText(ref: ElementRef, text: String): Boolean {
        if (!AppSettings.allowTextEditing) return false
        val pkg = foregroundPackage() ?: return false
        // Live lookup + action run detached on Dispatchers.IO (binder IPC) under the capture timeout,
        // reusing the same window enumeration as a normal capture.
        val ok = windowIpc {
            val roots = rootsFor(scanWindows(), pkg)
            if (roots.isEmpty()) null else applyTextToEditable(roots, ref, text) { !isActive }
        } ?: false
        // Log success/failure and the field id only, never the text value.
        val field = ref.resId?.let { it.substringAfter(":id/", it) } ?: ref.cls?.substringAfterLast('.') ?: "kolom"
        logEvent(EventLog.Tag.INFO, "Edit teks pada $field: " + if (ok) "berhasil" else "gagal")
        return ok
    }

    /**
     * Finds the live editable node matching [ref] among [roots] and writes [text] to it. Runs on the
     * window-IPC thread. Prefers a resource-id lookup, else scores the live tree like [UiTree.match].
     * Never reads the field's current value and never logs [text]. Returns true only when an editable node
     * matched and ACTION_SET_TEXT succeeded. Recycles every node it obtained here; the window [roots]
     * belong to the caller and are left untouched.
     */
    private fun applyTextToEditable(
        roots: List<AccessibilityNodeInfo>,
        ref: ElementRef,
        text: String,
        isCancelled: () -> Boolean,
    ): Boolean {
        // (node, owned): owned nodes were obtained here and are recycled before returning.
        val scan = ArrayList<Pair<AccessibilityNodeInfo, Boolean>>()
        try {
            val resId = ref.resId
            if (!resId.isNullOrEmpty()) {
                for (root in roots) {
                    if (isCancelled()) return false
                    val found = try {
                        root.findAccessibilityNodeInfosByViewId(resId)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    for (n in found) if (n != null) scan += n to true
                }
            }
            if (scan.isEmpty()) {
                // No usable id: score the live tree, bounded like NodeCapture so a huge tree cannot hang us.
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                for (root in roots) {
                    scan += root to false
                    stack.addLast(root)
                }
                var visited = 0
                while (stack.isNotEmpty()) {
                    if (isCancelled() || visited >= MAX_EDIT_SCAN_NODES) break
                    val node = stack.removeLast()
                    visited++
                    val count = try {
                        node.childCount
                    } catch (_: Exception) {
                        0
                    }
                    for (i in 0 until count) {
                        if (scan.size >= MAX_EDIT_SCAN_NODES) break
                        val child = try {
                            node.getChild(i)
                        } catch (_: Exception) {
                            null
                        } ?: continue
                        scan += child to true
                        stack.addLast(child)
                    }
                }
            }

            var best: AccessibilityNodeInfo? = null
            var bestScore = 0
            for ((node, _) in scan) {
                if (isCancelled()) break
                if (!isEditableLive(node)) continue
                val score = scoreLiveNode(node, ref)
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
            val target = best ?: return false
            // Same confidence gate as UiTree.match: refuse a weak match rather than edit the wrong field.
            if (bestScore < MATCH_MIN_SCORE) return false
            return performSetText(target, text)
        } finally {
            for ((node, owned) in scan) {
                if (!owned) continue
                try {
                    node.recycle()
                } catch (_: Exception) {
                    // Already recycled / stale.
                }
            }
        }
    }

    /**
     * Scores a live node against [ref], mirroring [UiTree.match] minus the text term: every node scored
     * here is already editable, and reading an editable field's live text would break the "never read the
     * existing text" rule. The text term is a no-op for editable fields anyway (their [ElementRef.text] is
     * always null for privacy), so dropping it does not weaken matching. Any stale-node access is caught.
     */
    private fun scoreLiveNode(n: AccessibilityNodeInfo, ref: ElementRef): Int = try {
        var score = 0
        if (ref.resId != null && n.viewIdResourceName == ref.resId) score += 4
        if (!ref.desc.isNullOrBlank() && n.contentDescription?.toString() == ref.desc) score += 2
        if (ref.cls != null && n.className?.toString() == ref.cls) score += 1
        val refBounds = ref.bounds
        if (refBounds != null && !refBounds.isEmpty()) {
            val r = Rect()
            n.getBoundsInScreen(r)
            val nb = Bounds(r.left, r.top, r.right, r.bottom)
            if (nb == refBounds) score += 5
            else if (UiTree.iou(nb, refBounds) > 0.7f) score += 2
        }
        if (isActionableLive(n)) score += 1
        score
    } catch (_: Exception) {
        0
    }

    private fun isEditableLive(n: AccessibilityNodeInfo): Boolean = try {
        n.isEditable
    } catch (_: Exception) {
        false
    }

    private fun isActionableLive(n: AccessibilityNodeInfo): Boolean = try {
        (n.isClickable || n.isLongClickable) && n.isEnabled && n.isVisibleToUser
    } catch (_: Exception) {
        false
    }

    /**
     * Focuses (best effort) and writes [text] to [node] via ACTION_SET_TEXT. Never reads the field's
     * current value and never logs [text]. Returns the ACTION_SET_TEXT result. Editable password fields
     * are allowed (the user explicitly targets one when testing their own app); the existing value is
     * never read and the new value is never logged.
     */
    private fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean = try {
        try {
            if (node.isFocusable && !node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (_: Exception) {
            // Focus is optional; some editors accept ACTION_SET_TEXT without it.
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    } catch (_: Exception) {
        false
    }

    // ---- commands ----

    private suspend fun handleCommand(cmd: ServiceCommand) {
        try {
            when (cmd) {
                is ServiceCommand.StartRecording -> startRecording(cmd)
                ServiceCommand.Stop -> stopRecording()
                is ServiceCommand.CaptureNow -> launchCaptureNow(cmd.sessionId)
                is ServiceCommand.SetOverlay -> if (cmd.visible) showOverlay() else hideOverlay()
                is ServiceCommand.SetInspect -> setInspect(cmd.enabled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${cmd::class.java.simpleName}", e)
            ServiceBridge.update { it.copy(status = "Terjadi kesalahan: ${e.message ?: e::class.java.simpleName}") }
        }
    }

    private suspend fun startRecording(cmd: ServiceCommand.StartRecording) {
        recorder?.stop()
        recorder = null

        val session = withContext(Dispatchers.IO) { SessionStore.get(cmd.sessionId) }
        if (session == null) {
            ServiceBridge.update { it.copy(mode = ServiceMode.IDLE, status = "Sesi tidak ditemukan") }
            flash("Sesi tidak ditemukan")
            logEvent(EventLog.Tag.WARN, "Sesi tidak ditemukan")
            return
        }

        val recLabel = session.appLabel ?: cmd.targetPkg ?: session.targetPkg ?: "aplikasi di depan"
        logEvent(EventLog.Tag.REC, "Mulai merekam $recLabel")

        val rec = RouteRecorder(this, session.id, cmd.targetPkg)
        recorder = rec
        ServiceBridge.update {
            it.copy(
                mode = ServiceMode.RECORDING,
                sessionId = session.id,
                targetPkg = cmd.targetPkg,
                currentScreenId = null,
                screenCount = session.screens.size,
                edgeCount = session.edges.size,
                status = "Merekam rute...",
            )
        }
        // The bubble is the user's way to follow and stop the recording while inside the target app.
        if (overlayController?.isShowing != true) showOverlay()

        val target = cmd.targetPkg
        if (cmd.launchTarget && target != null) {
            val launched = launchApp(target)
            if (!launched) {
                flash("Tidak dapat membuka aplikasi target, buka secara manual")
                logEvent(EventLog.Tag.WARN, "Tidak dapat membuka aplikasi target")
            }
            rec.start(launched = launched)
        } else {
            rec.start(launched = false)
        }
    }

    private fun stopRecording() {
        recorder?.stop()
        recorder = null
        ServiceBridge.update { it.copy(mode = ServiceMode.IDLE, status = "Perekaman dihentikan") }
        logEvent(EventLog.Tag.STOP, "Perekaman dihentikan")
    }

    private fun launchCaptureNow(sessionId: String?) {
        if (captureNowJob?.isActive == true) {
            flash("Sedang menangkap layar...")
            return
        }
        captureNowJob = serviceScope.launch {
            try {
                captureNow(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                flash("Gagal menangkap layar")
                ServiceBridge.update { it.copy(status = "Gagal menangkap layar") }
            }
        }
    }

    private suspend fun captureNow(requestedId: String?) {
        val st = ServiceBridge.state.value
        val recording = st.mode == ServiceMode.RECORDING && recorder != null
        val explicitId = requestedId ?: st.sessionId?.takeIf { recording }
        val explicit = explicitId?.let { id -> withContext(Dispatchers.IO) { SessionStore.get(id) } }
        if (explicitId != null && explicit == null) {
            flash("Sesi tidak ditemukan")
            ServiceBridge.update { it.copy(status = "Sesi tidak ditemukan") }
            return
        }

        val cap = captureScreen(explicit?.targetPkg, AppSettings.captureScreenshots)
        if (cap == null) {
            flash(NOTHING_TO_CAPTURE)
            ServiceBridge.update { it.copy(status = NOTHING_TO_CAPTURE) }
            logEvent(EventLog.Tag.WARN, NOTHING_TO_CAPTURE)
            return
        }

        val shot = cap.screenshot
        try {
            val sessionId = explicit?.id ?: snapshotSessionFor(cap.snapshot.pkg)
            val threshold = AppSettings.similarityThreshold
            val match = withContext(Dispatchers.IO) {
                SessionStore.recordScreen(sessionId, cap.snapshot, shot, threshold)
            }
            if (match == null) {
                flash("Gagal menyimpan layar")
                ServiceBridge.update { it.copy(status = "Gagal menyimpan layar") }
                return
            }
            val session = withContext(Dispatchers.IO) { SessionStore.get(sessionId) }
            val id = match.summary.id
            val label = match.summary.label
            val suffix = if (match.isNew) "(baru)" else "(sudah ada)"
            val status = "Tangkapan: $id · $label $suffix"
            ServiceBridge.update { s ->
                val rec = s.mode == ServiceMode.RECORDING
                when {
                    rec && s.sessionId == sessionId -> s.copy(
                        screenCount = session?.screens?.size ?: s.screenCount,
                        edgeCount = session?.edges?.size ?: s.edgeCount,
                        status = status,
                    )
                    rec -> s.copy(status = status)
                    else -> s.copy(
                        sessionId = sessionId,
                        currentScreenId = id,
                        screenCount = session?.screens?.size ?: s.screenCount,
                        edgeCount = session?.edges?.size ?: s.edgeCount,
                        status = status,
                    )
                }
            }
            flash("📸 $id · $label $suffix")
            logEvent(EventLog.Tag.SNAP, "Tangkap $id · $label $suffix")
        } finally {
            shot?.recycle()
        }
    }

    /** Reuse a recent SNAPSHOT session of [pkg] (updated within 12 h), else create one. */
    private suspend fun snapshotSessionFor(pkg: String): String {
        val now = System.currentTimeMillis()
        snapshotSessions[pkg]?.let { cached ->
            val s = withContext(Dispatchers.IO) { SessionStore.get(cached) }
            if (s != null && now - s.updatedAt <= SNAPSHOT_REUSE_MS) return cached
        }
        val appContext = applicationContext
        val id = withContext(Dispatchers.IO) {
            SessionStore.sessions.value.firstOrNull {
                it.mode == SessionMode.SNAPSHOT && it.targetPkg == pkg && now - it.updatedAt <= SNAPSHOT_REUSE_MS
            }?.id ?: run {
                val label = AppInfo.label(appContext, pkg)
                SessionStore.create("Snapshot · $label", pkg, label, SessionMode.SNAPSHOT).id
            }
        }
        snapshotSessions[pkg] = id
        return id
    }

    // ---- overlay ----

    private fun showOverlay() {
        val ov = overlayController
        if (ov == null) {
            ServiceBridge.update { it.copy(overlayVisible = false, inspecting = false, status = "Overlay tidak tersedia") }
            return
        }
        try {
            if (!ov.isShowing) ov.show()
            ServiceBridge.update { it.copy(overlayVisible = true) }
        } catch (e: Exception) {
            Log.e(TAG, "Overlay show failed", e)
            ServiceBridge.update { it.copy(overlayVisible = false, status = "Overlay gagal ditampilkan") }
        }
    }

    private fun hideOverlay() {
        val ov = overlayController
        if (ov != null) {
            try {
                ov.setInspect(false)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay inspect off failed", e)
            }
            try {
                ov.hide()
            } catch (e: Exception) {
                Log.w(TAG, "Overlay hide failed", e)
            }
        }
        ServiceBridge.update { it.copy(overlayVisible = false, inspecting = false) }
    }

    private fun setInspect(enabled: Boolean) {
        val ov = overlayController
        if (ov == null) {
            ServiceBridge.update { it.copy(overlayVisible = false, inspecting = false, status = "Overlay tidak tersedia") }
            return
        }
        try {
            if (enabled && !ov.isShowing) ov.show()
            ov.setInspect(enabled)
            ServiceBridge.update {
                it.copy(inspecting = enabled, overlayVisible = if (enabled) true else it.overlayVisible)
            }
            logEvent(EventLog.Tag.INSPECT, if (enabled) "Inspeksi elemen aktif" else "Inspeksi elemen dimatikan")
        } catch (e: Exception) {
            Log.e(TAG, "Inspect toggle failed", e)
            ServiceBridge.update { it.copy(inspecting = false, status = "Mode inspeksi gagal diaktifkan") }
        }
    }

    private fun flash(message: String) {
        try {
            overlayController?.flash(message)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay flash failed", e)
        }
    }

    /** Appends one entry to the shared live log; never lets a logging failure disturb the service. */
    private fun logEvent(tag: EventLog.Tag, message: String) {
        try {
            EventLog.log(tag, message)
        } catch (e: Exception) {
            Log.w(TAG, "Event log failed", e)
        }
    }

    private companion object {
        const val TAG = "UiMapper/Service"
        const val SYSTEM_UI_PKG = "com.android.systemui"
        const val NOTHING_TO_CAPTURE = "Tidak ada layar aplikasi untuk ditangkap"

        const val CACHE_LIMIT = 512
        const val IGNORABLE_REFRESH_MS = 10 * 60 * 1000L
        const val MIN_IDLE_WAIT_MS = 150L
        const val IDLE_POLL_MS = 60L
        const val CAPTURE_TIMEOUT_MS = 5000L

        /** Upper bound on live nodes scanned for a text-edit target (mirrors NodeCapture's node cap). */
        const val MAX_EDIT_SCAN_NODES = 5000
        /** Minimum match score (same gate as UiTree.match) before writing to a found node. */
        const val MATCH_MIN_SCORE = 5

        const val OVERLAY_HIDE_SETTLE_MS = 70L
        const val SHOT_TIMEOUT_MS = 3000L
        const val SHOT_GAP_MS = 350L
        const val SHOT_GAP_API30_MS = 1050L
        const val SHOT_RETRY_MS = 400L
        const val SHOT_ERROR_EXCEPTION = -1
        const val REDACT_COLOR = 0xFF6B7280.toInt()

        const val SNAPSHOT_REUSE_MS = 12 * 60 * 60 * 1000L

        /** HOME handlers that are not real launchers. */
        val NOT_A_LAUNCHER = setOf(
            "android",
            "com.android.settings",
            "com.android.provision",
            "com.google.android.setupwizard",
        )

        /** Simple-name fragments of window classes that are not activities (lower case). */
        val NON_ACTIVITY_HINTS = listOf("dialog", "popup", "menu", "sheet", "toast", "layout", "window", "view")

        fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
