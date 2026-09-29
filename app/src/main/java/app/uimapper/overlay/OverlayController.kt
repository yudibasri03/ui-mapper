package app.uimapper.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import app.uimapper.core.AppInfo
import app.uimapper.core.NodeCapture
import app.uimapper.core.UiTree
import app.uimapper.data.SessionStore
import app.uimapper.model.SessionMode
import app.uimapper.model.UiNode
import app.uimapper.service.OverlayUi
import app.uimapper.service.ServiceBridge
import app.uimapper.service.ServiceCommand
import app.uimapper.service.ServiceHost
import app.uimapper.service.ServiceMode
import app.uimapper.service.ServiceState
import app.uimapper.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating inspector overlay drawn over other apps through TYPE_ACCESSIBILITY_OVERLAY windows owned by
 * the AccessibilityService ([host]).
 *
 * Windows (bottom to top):
 *  - inspect layer (only while inspecting): node bounds + tap-to-inspect, consumes touches;
 *  - property panel (only while inspecting);
 *  - control bubble, its menu and the transient flash label.
 *
 * The overlay only observes: it never performs any action on the app underneath. Its buttons act through
 * [ServiceBridge.send] (and [SessionStore.create] for new recording sessions); the service calls back
 * [setInspect], [show], [hide] and [onStateChanged].
 *
 * Every [OverlayUi] member may be called from any thread; work is always done on the main thread.
 */
class OverlayController(private val host: ServiceHost) : OverlayUi {

    private class Win(val view: View, val lp: WindowManager.LayoutParams) {
        var attached = false
    }

    private class Limits(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    private data class MenuKey(
        val mode: ServiceMode,
        val inspecting: Boolean,
        val line1: String,
        val line2: String?,
    )

    private sealed interface Capture {
        data object Idle : Capture
        data object Loading : Capture

        /** [layoutKey] changes when the screen, the bounds or the texts of its visible nodes change. */
        class Ready(val data: LayerData, val pkg: String, val activity: String?, val layoutKey: Long) : Capture
        class Failed(val message: String) : Capture
    }

    /** Views are themed with the dark device-default theme (scrollbars, ripples, text defaults). */
    private val ctx: Context = ContextThemeWrapper(host.context, android.R.style.Theme_DeviceDefault)

    /** Must come from the service context: it carries the accessibility-overlay window token. */
    private val wm: WindowManager by lazy { host.context.getSystemService(WindowManager::class.java) }

    private val main = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop

    private var state: ServiceState = ServiceBridge.state.value
    private var destroyed = false
    private var hiddenForCapture = false

    // ---- windows ----
    private var bubbleWin: Win? = null
    private var menuWin: Win? = null
    private var flashWin: Win? = null
    private var layerWin: Win? = null
    private var panelWin: Win? = null

    private var bubbleView: BubbleView? = null
    private var menuPanel: MenuPanel? = null
    private var flashLabel: TextView? = null
    private var layerView: InspectLayerView? = null
    private var propertyPanel: PropertyPanel? = null

    // ---- bubble placement ----
    private var onRight = true
    private var yFraction = 0.3f
    private var frameOffX = 0
    private var frameOffY = 0
    private var snapAnimator: ValueAnimator? = null
    private var lastScreen: Pair<Int, Int>? = null
    private var lastRotation = -1
    /** Navigation bar + display cutout insets; the bubble is kept clear of them. */
    private var edgeInsets = Rect()
    private var displayListenerRegistered = false
    private var menuOutsideDismissAt = 0L
    private var menuKey: MenuKey? = null

    // ---- inspection ----
    private var capture: Capture = Capture.Idle
    private var captureJob: Job? = null
    private var captureGen = 0
    private var filterOnly = false
    private var panelAtTop = false
    private var startingRecording = false
    /** A background re-read of the inspected screen is posted. */
    private var layerRefreshScheduled = false
    /** The screen changed while a capture was reading it: re-read once more when it is done. */
    private var layerRefreshDirty = false
    /** The current inspect capture has settled and is reading the tree. */
    private var captureReading = false

    private val labelCache = HashMap<String, String>()

    private val hideFlashRunnable = Runnable { hideFlash() }
    private val geometryRunnable = Runnable { checkGeometry() }
    private val layerRefreshRunnable = Runnable {
        layerRefreshScheduled = false
        if (!destroyed && layerWin?.attached == true && captureJob?.isActive != true) {
            startCapture(keepSelection = true, settleFirst = true, quiet = true)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) scheduleGeometryCheck()
        }
    }

    private val layerListener = object : InspectLayerView.Listener {
        override fun onSelectionChanged(node: UiNode?, fromTouch: Boolean) {
            renderPanel(scrollTop = true)
        }

        override fun onLayerResized() {
            scheduleGeometryCheck()
            startCapture(keepSelection = true, settleFirst = true)
        }
    }

    // =================================================================== OverlayUi

    override val isShowing: Boolean
        get() = bubbleWin?.attached == true

    override fun show() {
        onMain { showBubble() }
    }

    override fun hide() {
        onMain { hideAll() }
    }

    override fun setInspect(enabled: Boolean) {
        onMain { if (enabled) enableInspect() else disableInspect() }
    }

    override fun setHiddenForCapture(hidden: Boolean) {
        onMain {
            hiddenForCapture = hidden
            // Transparent, not INVISIBLE: an invisible window is dropped from input dispatch, so taps made
            // during the screenshot would reach (and click) the app underneath.
            val a = rootAlpha()
            for (w in windows()) {
                if (w.attached && w.view.alpha != a) w.view.alpha = a
            }
        }
    }

    override fun onStateChanged(state: ServiceState) {
        onMain { applyState(state) }
    }

    override fun onScreenMaybeChanged() {
        onMain { scheduleLayerRefresh() }
    }

    override fun onScreenOff() {
        onMain {
            if (destroyed) return@onMain
            main.removeCallbacks(layerRefreshRunnable)
            hideMenu()
            hideFlash()
            disableInspect()
        }
    }

    override fun flash(message: String) {
        onMain { showFlash(message) }
    }

    override fun destroy() {
        onMain { destroyInternal() }
    }

    // =================================================================== threading / windows

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post { block() }
    }

    private fun windows(): List<Win> = listOfNotNull(layerWin, panelWin, bubbleWin, menuWin, flashWin)

    /** Alpha of every window root: 0 while a screenshot is taken (see [setHiddenForCapture]). */
    private fun rootAlpha(): Float = if (hiddenForCapture) 0f else 1f

    private fun attach(w: Win): Boolean {
        if (w.attached) return true
        w.view.visibility = View.VISIBLE
        w.view.alpha = rootAlpha()
        return try {
            wm.addView(w.view, w.lp)
            w.attached = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "addView failed", e)
            false
        }
    }

    private fun detach(w: Win, immediate: Boolean = false) {
        if (!w.attached) return
        w.attached = false
        try {
            if (immediate) wm.removeViewImmediate(w.view) else wm.removeView(w.view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
    }

    private fun relayout(w: Win) {
        if (!w.attached) return
        try {
            wm.updateViewLayout(w.view, w.lp)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    /** Re-add the floating windows so they stack above a window that was just added (the layer). */
    private fun restackFloating() {
        for (w in listOfNotNull(bubbleWin, menuWin, flashWin)) {
            if (!w.attached) continue
            try {
                wm.removeViewImmediate(w.view)
            } catch (e: Exception) {
                Log.w(TAG, "removeViewImmediate failed", e)
            }
            w.attached = false
            attach(w)
        }
    }

    /** Params for the bubble, its menu and the flash label: TOP|START in plain screen coordinates. */
    private fun floatingParams(touchable: Boolean, watchOutside: Boolean): WindowManager.LayoutParams {
        val touchFlag = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            touchFlag
        if (watchOutside) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            flags,
            fullDisplayFrame = true,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    // =================================================================== state

    private fun applyState(next: ServiceState) {
        if (destroyed) return
        val prev = state
        state = next
        val inspectWindows = layerWin?.attached == true || panelWin?.attached == true
        if (prev.inspecting && !next.inspecting && inspectWindows) {
            disableInspect()
        } else if (layerWin?.attached == true &&
            next.foregroundPkg != null &&
            next.foregroundPkg != host.ownPackage &&
            (prev.foregroundPkg != next.foregroundPkg || prev.foregroundActivity != next.foregroundActivity)
        ) {
            // The inspected screen changed underneath (back / home / dialog): re-read it once it settles.
            startCapture(keepSelection = false, settleFirst = true)
        }
        refreshBubbleLook()
        if (menuWin?.attached == true) renderMenu()
    }

    private fun inspectOn(): Boolean = state.inspecting || layerWin?.attached == true

    private fun refreshBubbleLook() {
        val v = bubbleView ?: return
        val inspecting = inspectOn()
        val recording = state.mode == ServiceMode.RECORDING
        val look = when {
            inspecting -> BubbleView.Look.INSPECTING
            recording -> BubbleView.Look.RECORDING
            else -> BubbleView.Look.IDLE
        }
        v.setLook(look, badge = inspecting && recording)
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) { AppInfo.label(host.context, pkg) }

    private fun send(cmd: ServiceCommand): Boolean {
        val ok = ServiceBridge.send(cmd)
        if (!ok) showFlash(MSG_SERVICE_OFF)
        return ok
    }

    // =================================================================== bubble

    private fun showBubble() {
        if (destroyed) return
        val win = bubbleWin ?: createBubbleWindow()
        if (!win.attached) {
            placeBubble(win)
            if (attach(win)) {
                // Runs after the first layout: correct for any frame offset the platform applied.
                win.view.post {
                    if (win.attached && !destroyed) {
                        measureFrameOffset()
                        placeBubble(win)
                        relayout(win)
                    }
                }
                registerDisplayListener()
                lastScreen = host.screenSize()
                lastRotation = displayRotation()
            }
        }
        refreshBubbleLook()
    }

    private fun createBubbleWindow(): Win {
        val v = BubbleView(ctx)
        v.setOnClickListener { toggleMenu() }
        v.setOnTouchListener(BubbleTouchListener())
        v.onConfigChanged = { scheduleGeometryCheck() }
        val lp = floatingParams(touchable = true, watchOutside = false)
        lp.title = "UI Mapper · bubble"
        bubbleView = v
        return Win(v, lp).also { bubbleWin = it }
    }

    private fun bubbleSize(): Int = bubbleView?.sizePx ?: ctx.dpi(52)

    /**
     * Refreshes [edgeInsets] (API 30+): a bubble snapped onto the navigation bar would swallow taps on its
     * buttons (accessibility overlays sit above it). Cached because [bubbleLimits] runs on every drag move.
     */
    private fun updateEdgeInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        edgeInsets = try {
            val i = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout(),
            )
            Rect(i.left, i.top, i.right, i.bottom)
        } catch (e: Exception) {
            Log.w(TAG, "window insets unavailable", e)
            Rect()
        }
    }

    private fun bubbleLimits(): Limits {
        val (sw, sh) = host.screenSize()
        val size = bubbleSize()
        val m = ctx.dpi(EDGE_MARGIN_DP)
        val ins = edgeInsets
        val minX = ins.left + m - frameOffX
        val minY = ins.top + m - frameOffY
        return Limits(
            minX = minX,
            maxX = max(minX, sw - ins.right - size - m - frameOffX),
            minY = minY,
            maxY = max(minY, sh - ins.bottom - size - m - frameOffY),
        )
    }

    /** Put the bubble on its remembered side and relative height (used on show and on rotation). */
    private fun placeBubble(win: Win) {
        updateEdgeInsets()
        val lim = bubbleLimits()
        win.lp.x = if (onRight) lim.maxX else lim.minX
        win.lp.y = (lim.minY + yFraction * (lim.maxY - lim.minY)).roundToInt().coerceIn(lim.minY, lim.maxY)
    }

    /** Difference between the window's lp.x/y and where it actually sits on screen. */
    private fun measureFrameOffset() {
        val win = bubbleWin ?: return
        if (!win.attached || win.view.width == 0) return
        val loc = IntArray(2)
        win.view.getLocationOnScreen(loc)
        val (sw, sh) = host.screenSize()
        val ox = loc[0] - win.lp.x
        val oy = loc[1] - win.lp.y
        frameOffX = if (kotlin.math.abs(ox) <= sw / 4) ox else 0
        frameOffY = if (kotlin.math.abs(oy) <= sh / 4) oy else 0
    }

    private fun moveBubbleTo(win: Win, x: Int, y: Int) {
        val lim = bubbleLimits()
        win.lp.x = x.coerceIn(lim.minX, lim.maxX)
        win.lp.y = y.coerceIn(lim.minY, lim.maxY)
        relayout(win)
    }

    private fun snapToEdge(win: Win) {
        val lim = bubbleLimits()
        val (sw, _) = host.screenSize()
        val centerX = win.lp.x + frameOffX + bubbleSize() / 2
        onRight = centerX > sw / 2
        win.lp.y = win.lp.y.coerceIn(lim.minY, lim.maxY)
        yFraction = if (lim.maxY > lim.minY) (win.lp.y - lim.minY).toFloat() / (lim.maxY - lim.minY) else 0f
        animateBubbleX(win, if (onRight) lim.maxX else lim.minX)
    }

    private fun animateBubbleX(win: Win, target: Int) {
        snapAnimator?.cancel()
        val from = win.lp.x
        if (from == target) {
            relayout(win)
            return
        }
        snapAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                win.lp.x = a.animatedValue as Int
                relayout(win)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (snapAnimator === animation) snapAnimator = null
                }
            })
            start()
        }
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var menuWasOpen = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val win = bubbleWin ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    measureFrameOffset()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = win.lp.x
                    startY = win.lp.y
                    dragging = false
                    // An outside touch on the menu is delivered for this same DOWN; remember the menu was open.
                    menuWasOpen = menuWin?.attached == true ||
                        SystemClock.uptimeMillis() - menuOutsideDismissAt < MENU_REOPEN_GUARD_MS
                    // Never make the bubble visible while a screenshot is being taken.
                    v.alpha = if (hiddenForCapture) 0f else 0.85f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        hideMenu()
                        hideFlash()
                    }
                    if (dragging) moveBubbleTo(win, startX + dx.roundToInt(), startY + dy.roundToInt())
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = rootAlpha()
                    if (dragging) {
                        snapToEdge(win)
                    } else if (menuWasOpen) {
                        hideMenu()
                    } else {
                        v.performClick()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = rootAlpha()
                    if (dragging) snapToEdge(win)
                    dragging = false
                }
            }
            return true
        }
    }

    // =================================================================== geometry (rotation)

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        try {
            host.context.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, main)
            displayListenerRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerDisplayListener failed", e)
        }
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        displayListenerRegistered = false
        try {
            host.context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterDisplayListener failed", e)
        }
    }

    private fun scheduleGeometryCheck() {
        if (destroyed) return
        main.removeCallbacks(geometryRunnable)
        main.postDelayed(geometryRunnable, GEOMETRY_DELAY_MS)
    }

    private fun displayRotation(): Int = try {
        host.context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1
    } catch (e: Exception) {
        Log.w(TAG, "display rotation unavailable", e)
        -1
    }

    private fun checkGeometry() {
        if (destroyed || !isShowing) return
        val size = host.screenSize()
        val rotation = displayRotation()
        val rotated = rotation != lastRotation
        lastRotation = rotation
        if (size == lastScreen) {
            if (!rotated) return
            // 90 <-> 270 degrees keeps the size, so the layer is not resized, but every captured bound moved.
            if (layerWin?.attached == true) startCapture(keepSelection = false, settleFirst = true)
        }
        lastScreen = size
        val bw = bubbleWin ?: return
        measureFrameOffset()
        placeBubble(bw)
        relayout(bw)
        if (menuWin?.attached == true) positionMenu()
        if (flashWin?.attached == true) positionFlash()
        panelWin?.let { pw ->
            if (pw.attached) {
                layoutPanel()
                relayout(pw)
            }
        }
    }

    // =================================================================== menu

    private fun ensureMenu(): Win {
        menuWin?.let { return it }
        val panel = MenuPanel(ctx)
        panel.root.onOutsideTouch = {
            menuOutsideDismissAt = SystemClock.uptimeMillis()
            // Never remove a window from inside its own input dispatch.
            main.post { hideMenu() }
        }
        val lp = floatingParams(touchable = true, watchOutside = true)
        lp.title = "UI Mapper · menu"
        menuPanel = panel
        return Win(panel.root, lp).also { menuWin = it }
    }

    private fun toggleMenu() {
        if (menuWin?.attached == true) hideMenu() else showMenu()
    }

    private fun showMenu() {
        if (destroyed || !isShowing) return
        val win = ensureMenu()
        hideFlash()
        menuKey = null
        renderMenu()
        positionMenu()
        attach(win)
    }

    private fun hideMenu() {
        menuWin?.let { detach(it) }
    }

    private fun renderMenu() {
        val panel = menuPanel ?: return
        val inspecting = inspectOn()
        val key = MenuKey(state.mode, inspecting, statusLine(), secondaryStatus())
        val prev = menuKey
        if (key == prev) return
        menuKey = key
        panel.setStatus(key.line1, key.line2)
        if (prev == null || prev.mode != key.mode || prev.inspecting != key.inspecting) {
            panel.setItems(menuItems(key.mode, inspecting))
        }
        if (menuWin?.attached == true) positionMenu()
    }

    private fun statusLine(): String = when (state.mode) {
        ServiceMode.RECORDING -> "Merekam · ${state.screenCount} layar · ${state.edgeCount} rute"
        ServiceMode.IDLE -> {
            val pkg = state.foregroundPkg ?: host.foregroundPackage()
            if (pkg == null) {
                "Belum ada aplikasi di layar"
            } else {
                val act = (state.foregroundActivity ?: host.foregroundActivity())
                    ?.substringAfterLast('.')
                    ?.takeIf { it.isNotBlank() }
                if (act == null) appLabel(pkg) else "${appLabel(pkg)} · $act"
            }
        }
    }

    private fun secondaryStatus(): String? {
        val status = state.status?.trim()?.takeIf { it.isNotEmpty() }
        if (state.mode == ServiceMode.RECORDING) {
            val target = state.targetPkg?.let { "Target: ${appLabel(it)}" }
            return listOfNotNull(target, status).joinToString("\n").ifEmpty { null }
        }
        return status
    }

    private fun menuItems(mode: ServiceMode, inspecting: Boolean): List<MenuPanel.Item> {
        val items = ArrayList<MenuPanel.Item>()
        if (mode == ServiceMode.RECORDING) {
            items += MenuPanel.Item("⏹  Hentikan rekam", OverlayColors.RED) {
                hideMenu()
                send(ServiceCommand.Stop)
            }
        }
        items += MenuPanel.Item(
            if (inspecting) "🔍  Inspeksi elemen · aktif" else "🔍  Inspeksi elemen",
            if (inspecting) OverlayColors.BLUE else OverlayColors.BUTTON,
        ) {
            hideMenu()
            val on = inspectOn()
            if (!send(ServiceCommand.SetInspect(!on)) && on) {
                // Service unreachable: never leave a touch-blocking layer behind.
                disableInspect()
            }
        }
        items += MenuPanel.Item("📸  Tangkap layar ini", OverlayColors.BUTTON) {
            hideMenu()
            send(ServiceCommand.CaptureNow(null))
        }
        if (mode == ServiceMode.IDLE) {
            items += MenuPanel.Item("⏺  Rekam rute", OverlayColors.BUTTON) {
                hideMenu()
                startRecordingHere()
            }
        }
        items += MenuPanel.Item("📱  Buka UI Mapper", OverlayColors.BUTTON) {
            hideMenu()
            openMainApp()
        }
        items += MenuPanel.Item("✕  Sembunyikan overlay", OverlayColors.BUTTON) {
            hideMenu()
            if (!ServiceBridge.send(ServiceCommand.SetOverlay(false))) hideAll()
        }
        return items
    }

    private fun positionMenu() {
        val mw = menuWin ?: return
        val bw = bubbleWin ?: return
        val panel = menuPanel ?: return
        val (sw, sh) = host.screenSize()
        val margin = ctx.dpi(8)
        val gap = ctx.dpi(8)
        val size = bubbleSize()
        val width = min(ctx.dpi(MENU_WIDTH_DP), sw - 2 * margin).coerceAtLeast(ctx.dpi(160))
        val maxH = (sh - 2 * margin).coerceAtLeast(ctx.dpi(200))
        panel.setListMaxHeight((maxH - ctx.dpi(MENU_CHROME_DP)).coerceAtLeast(ctx.dpi(96)))
        mw.view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST),
        )
        val h = mw.view.measuredHeight
        val bx = bw.lp.x + frameOffX
        val by = bw.lp.y + frameOffY
        val x = (if (onRight) bx - width - gap else bx + size + gap)
            .coerceIn(margin, max(margin, sw - width - margin))
        val y = by.coerceIn(margin, max(margin, sh - h - margin))
        mw.lp.width = width
        mw.lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        mw.lp.x = x - frameOffX
        mw.lp.y = y - frameOffY
        relayout(mw)
    }

    private fun startRecordingHere() {
        if (startingRecording) return
        if (state.mode == ServiceMode.RECORDING) {
            showFlash("Perekaman sudah berjalan")
            return
        }
        startingRecording = true
        host.scope.launch {
            try {
                // Read from the live window list (off the main thread): the tracked package can be stale.
                val pkg = host.currentForegroundPackage()
                if (destroyed) return@launch
                if (pkg == null || pkg == host.ownPackage) {
                    showFlash("Buka aplikasi yang ingin dipetakan dulu")
                    return@launch
                }
                val session = withContext(Dispatchers.IO) {
                    val label = AppInfo.label(host.context, pkg)
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    SessionStore.create("$label · rekam $time", pkg, label, SessionMode.RECORD)
                }
                if (destroyed) return@launch
                val sent = ServiceBridge.send(
                    ServiceCommand.StartRecording(session.id, pkg, launchTarget = false),
                )
                if (sent) {
                    showFlash("Merekam — jelajahi aplikasi seperti biasa")
                } else {
                    withContext(Dispatchers.IO) { SessionStore.delete(session.id) }
                    showFlash(MSG_SERVICE_OFF)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "start recording failed", e)
                showFlash("Gagal memulai rekaman")
            } finally {
                startingRecording = false
            }
        }
    }

    private fun openMainApp() {
        // The inspect layer swallows touches; switch it off before bringing our own UI forward.
        if (inspectOn() && !ServiceBridge.send(ServiceCommand.SetInspect(false))) disableInspect()
        try {
            host.context.startActivity(
                Intent(host.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w(TAG, "startActivity failed", e)
            showFlash("Tidak bisa membuka UI Mapper")
        }
    }

    // =================================================================== flash

    private fun ensureFlash(): Win {
        flashWin?.let { return it }
        val tv = makeText(ctx, 13f, OverlayColors.TEXT).apply {
            background = surfaceBackground(ctx, 12f)
            setPadding(ctx.dpi(12), ctx.dpi(8), ctx.dpi(12), ctx.dpi(8))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val lp = floatingParams(touchable = false, watchOutside = false)
        lp.title = "UI Mapper · pesan"
        flashLabel = tv
        return Win(tv, lp).also { flashWin = it }
    }

    private fun showFlash(message: String) {
        if (destroyed) return
        main.removeCallbacks(hideFlashRunnable)
        if (!isShowing) {
            hideFlash()
            // A toast is drawn by the system, cannot be hidden for a screenshot and would end up in the
            // recorded images. The app shows the same message from ServiceState.status.
            if (state.mode == ServiceMode.RECORDING) return
            try {
                Toast.makeText(host.context, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "toast failed", e)
            }
            return
        }
        val fw = ensureFlash()
        flashLabel?.text = message
        positionFlash()
        if (!fw.attached) attach(fw)
        main.postDelayed(hideFlashRunnable, FLASH_MS)
    }

    private fun hideFlash() {
        main.removeCallbacks(hideFlashRunnable)
        flashWin?.let { detach(it) }
    }

    private fun positionFlash() {
        val fw = flashWin ?: return
        val bw = bubbleWin ?: return
        val (sw, sh) = host.screenSize()
        val margin = ctx.dpi(8)
        val gap = ctx.dpi(8)
        val size = bubbleSize()
        val maxW = min(ctx.dpi(280), sw - size - 2 * margin - gap).coerceAtLeast(ctx.dpi(120))
        fw.view.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = fw.view.measuredWidth
        val h = fw.view.measuredHeight
        val bx = bw.lp.x + frameOffX
        val by = bw.lp.y + frameOffY
        val x = (if (onRight) bx - w - gap else bx + size + gap)
            .coerceIn(margin, max(margin, sw - w - margin))
        val y = (by + (size - h) / 2).coerceIn(margin, max(margin, sh - h - margin))
        fw.lp.width = w
        fw.lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        fw.lp.x = x - frameOffX
        fw.lp.y = y - frameOffY
        relayout(fw)
    }

    // =================================================================== inspection

    private fun ensureLayer(): Win {
        layerWin?.let { return it }
        val v = InspectLayerView(ctx)
        v.listener = layerListener
        v.onlyActionable = filterOnly
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            flags,
            fullDisplayFrame = false,
        )
        applyCutoutAlways(lp)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.title = "UI Mapper · inspeksi"
        layerView = v
        return Win(v, lp).also { layerWin = it }
    }

    private fun ensurePanel(): Win {
        panelWin?.let { return it }
        val p = PropertyPanel(ctx)
        p.parentBtn.setOnClickListener { selectParent() }
        p.childBtn.setOnClickListener { selectChild() }
        p.copyBtn.setOnClickListener { copySelected() }
        p.filterBtn.setOnClickListener { toggleFilter() }
        p.refreshBtn.setOnClickListener { startCapture(keepSelection = true) }
        p.saveBtn.setOnClickListener { send(ServiceCommand.CaptureNow(null)) }
        p.doneBtn.setOnClickListener {
            // Service unreachable: never leave a touch-blocking layer behind.
            if (!ServiceBridge.send(ServiceCommand.SetInspect(false))) disableInspect()
        }
        p.setFilterActive(filterOnly)
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            fullDisplayFrame = false,
        )
        lp.title = "UI Mapper · properti"
        propertyPanel = p
        return Win(p.root, lp).also { panelWin = it }
    }

    private fun enableInspect() {
        if (destroyed) return
        if (!isShowing) showBubble()
        hideMenu()
        val lw = ensureLayer()
        val pw = ensurePanel()
        if (!lw.attached) {
            if (!attach(lw)) {
                showFlash("Lapisan inspeksi tidak bisa ditampilkan")
                ServiceBridge.send(ServiceCommand.SetInspect(false))
                return
            }
            // Order: layer, then panel, then bubble/menu/flash on top.
            if (pw.attached) detach(pw, immediate = true)
            layoutPanel()
            attach(pw)
            restackFloating()
        } else if (!pw.attached) {
            layoutPanel()
            attach(pw)
            restackFloating()
        }
        refreshBubbleLook()
        if (menuWin?.attached == true) renderMenu()
        startCapture(keepSelection = false)
    }

    private fun disableInspect() {
        main.removeCallbacks(layerRefreshRunnable)
        layerRefreshScheduled = false
        layerRefreshDirty = false
        captureReading = false
        captureJob?.cancel()
        captureJob = null
        captureGen++
        capture = Capture.Idle
        layerWin?.let { detach(it) }
        panelWin?.let { detach(it) }
        layerView?.setData(null)
        layerView?.setMessage(null)
        panelAtTop = false
        refreshBubbleLook()
        if (menuWin?.attached == true) renderMenu()
    }

    /**
     * The inspected screen may have changed underneath the layer (back, home, dialog, pane, rotation):
     * re-read it in the background. Coalesced, and never lost while another capture is in flight.
     */
    private fun scheduleLayerRefresh() {
        if (destroyed || layerWin?.attached != true) return
        if (captureJob?.isActive == true) {
            // A capture still waiting for the UI to settle covers this change; one already reading does not.
            if (captureReading) layerRefreshDirty = true
            return
        }
        postLayerRefresh()
    }

    private fun postLayerRefresh() {
        if (layerRefreshScheduled) return
        layerRefreshScheduled = true
        main.postDelayed(layerRefreshRunnable, LAYER_REFRESH_DELAY_MS)
    }

    /**
     * Reads the current screen into the layer. A [quiet] refresh keeps the current data on screen while it
     * runs and only replaces it when something actually changed (no "loading" flicker on every event).
     */
    private fun startCapture(keepSelection: Boolean, settleFirst: Boolean = false, quiet: Boolean = false) {
        if (destroyed) return
        val lw = layerWin ?: return
        val layer = layerView ?: return
        if (!lw.attached) return
        captureJob?.cancel()
        layerRefreshDirty = false
        captureReading = false
        val gen = ++captureGen
        val showLoading = !quiet || capture !is Capture.Ready
        if (showLoading) {
            capture = Capture.Loading
            layer.setMessage(MSG_LOADING)
            renderPanel(scrollTop = false)
        }
        captureJob = host.scope.launch {
            if (settleFirst) {
                try {
                    host.awaitIdle(quietMs = 400, timeoutMs = 2500)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "awaitIdle failed", e)
                }
            }
            // From here on the tree may already have been read: later changes need another refresh.
            if (gen == captureGen) captureReading = true
            val result: Capture = try {
                val shot = host.captureScreen(null, withScreenshot = false)
                if (shot == null) {
                    Capture.Failed(MSG_NOTHING)
                } else {
                    val snap = shot.snapshot
                    val (data, key) = withContext(Dispatchers.Default) {
                        val d = LayerData.build(snap.root)
                        d to layoutKey(snap.signature, d)
                    }
                    if (data.candidates.isEmpty()) Capture.Failed(MSG_EMPTY) else Capture.Ready(data, snap.pkg, snap.activity, key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "capture failed", e)
                Capture.Failed("Gagal membaca struktur layar (${e.javaClass.simpleName}). Ketuk Segarkan untuk mencoba lagi.")
            }
            if (gen != captureGen || destroyed) return@launch
            captureReading = false
            val current = capture
            val unchanged = !showLoading && result is Capture.Ready && current is Capture.Ready &&
                result.layoutKey == current.layoutKey
            // The selection as it is now: the user may have picked another element meanwhile.
            val previous = if (keepSelection) layerView?.selected else null
            if (!unchanged) applyCapture(result, previous, scrollTop = showLoading)
            // Posted: this job is still active right now.
            if (layerRefreshDirty) {
                layerRefreshDirty = false
                postLayerRefresh()
            }
        }
    }

    private fun layoutKey(signature: String, data: LayerData): Long {
        var h = signature.hashCode().toLong()
        for (n in data.candidates) {
            h = h * 31 + n.idx
            h = h * 31 + n.bounds.hashCode()
            h = h * 31 + (n.text?.hashCode() ?: 0)
            h = h * 31 + (n.desc?.hashCode() ?: 0)
        }
        return h
    }

    private fun applyCapture(result: Capture, previous: UiNode?, scrollTop: Boolean = true) {
        val layer = layerView ?: return
        if (layerWin?.attached != true) return
        capture = result
        when (result) {
            is Capture.Ready -> {
                layer.setData(result.data)
                layer.setMessage(null)
                if (previous != null) {
                    val match = UiTree.match(
                        result.data.root,
                        previous.cls,
                        previous.resId,
                        previous.text,
                        previous.desc,
                        previous.bounds,
                    )
                    if (match != null && match.cls != NodeCapture.SYNTHETIC_ROOT_CLS) layer.select(match, notify = false)
                }
            }
            is Capture.Failed -> {
                layer.setData(null)
                layer.setMessage("Struktur layar tidak terbaca")
            }
            Capture.Idle, Capture.Loading -> Unit
        }
        renderPanel(scrollTop = scrollTop)
    }

    private fun layoutPanel() {
        val pw = panelWin ?: return
        val p = propertyPanel ?: return
        val (sw, sh) = host.screenSize()
        val margin = ctx.dpi(8)
        pw.lp.width = min(sw - 2 * margin, ctx.dpi(PANEL_MAX_WIDTH_DP))
        pw.lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        pw.lp.gravity = (if (panelAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        pw.lp.x = 0
        pw.lp.y = margin
        val cap = (sh * PANEL_HEIGHT_FRACTION).toInt()
        p.setBodyMaxHeight((cap - ctx.dpi(PANEL_CHROME_DP)).coerceAtLeast(ctx.dpi(72)))
    }

    /** Keep the panel away from the selection: top when the selected element sits in the lower half. */
    private fun updatePanelSide(sel: UiNode?) {
        val pw = panelWin ?: return
        val (_, sh) = host.screenSize()
        val wantTop = sel != null && sel.bounds.centerY > sh / 2
        if (wantTop == panelAtTop) return
        panelAtTop = wantTop
        layoutPanel()
        relayout(pw)
    }

    private fun renderPanel(scrollTop: Boolean) {
        val p = propertyPanel ?: return
        val c = capture
        val sel = layerView?.selected
        p.setFilterActive(filterOnly)
        when (c) {
            Capture.Idle, Capture.Loading -> p.setContent(
                MSG_LOADING,
                null,
                "Membaca elemen dari layar yang sedang tampil…",
                hasSelection = false,
            )
            is Capture.Failed -> p.setContent("Struktur tidak terbaca", null, c.message, hasSelection = false)
            is Capture.Ready -> if (sel == null) {
                p.setContent("Inspeksi elemen", appLine(c), overviewText(c), hasSelection = false)
            } else {
                p.setContent(
                    UiTree.labelOf(sel) ?: sel.simpleCls,
                    sel.simpleCls + (sel.resIdEntry?.let { " · $it" } ?: ""),
                    propertiesText(sel, c),
                    hasSelection = true,
                )
            }
        }
        if (scrollTop) p.scrollBodyToTop()
        // While (re)loading keep the panel where it is to avoid a jump on every refresh.
        when (c) {
            is Capture.Ready -> updatePanelSide(sel)
            is Capture.Failed -> updatePanelSide(null)
            Capture.Idle, Capture.Loading -> Unit
        }
    }

    private fun appLine(c: Capture.Ready): String {
        val act = (c.activity ?: host.foregroundActivity())?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
        return if (act == null) appLabel(c.pkg) else "${appLabel(c.pkg)} · $act"
    }

    private fun overviewText(c: Capture.Ready): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append("Ketuk elemen mana saja untuk melihat propertinya.")
        sb.append("\nSeret jari untuk menelusuri; ketuk lagi di titik yang sama untuk memilih elemen induknya.")
        sb.append("\n\n")
        prop(sb, "Elemen", "${c.data.totalCount} total · ${c.data.actionableCount} bisa diklik")
        prop(sb, "Filter", if (filterOnly) "Hanya yang bisa diklik" else "Semua elemen")
        prop(sb, "Aplikasi", "${appLabel(c.pkg)} (${c.pkg})")
        prop(sb, "Activity", c.activity ?: host.foregroundActivity(), mono = true)
        sb.append('\n')
        keySpan(sb, "Warna")
        sb.append("  ")
        colored(sb, "■ bisa diklik", OverlayColors.GREEN_LIGHT)
        sb.append("   ")
        colored(sb, "■ berisi teks", OverlayColors.BLUE_LIGHT)
        sb.append("   ")
        colored(sb, "■ lainnya", OverlayColors.MUTED)
        return sb
    }

    private fun propertiesText(n: UiNode, c: Capture.Ready): CharSequence {
        val sb = SpannableStringBuilder()
        prop(sb, "Kelas", n.cls, mono = true)
        prop(sb, "Resource-id", n.resId, mono = true)
        val textValue = when {
            n.password -> "(kolom kata sandi — isi tidak dibaca)"
            n.editable && n.text == null -> "(kolom input — isi tidak dibaca)"
            else -> n.text?.replace('\n', ' ')?.take(MAX_VALUE_CHARS)
        }
        prop(sb, "Teks", textValue)
        prop(sb, "Content-desc", n.desc?.replace('\n', ' ')?.take(MAX_VALUE_CHARS))
        n.hint?.let { prop(sb, "Hint", it.take(MAX_VALUE_CHARS)) }
        n.paneTitle?.let { prop(sb, "Judul panel", it.take(MAX_VALUE_CHARS)) }
        prop(sb, "Bounds", "${n.bounds} · ${n.bounds.width}×${n.bounds.height} px", mono = true)
        prop(sb, "Pohon", "idx ${n.idx} · kedalaman ${n.depth} · ${n.children.size} anak")
        flagsLine(sb, n)
        prop(sb, "Aksi", n.actions.joinToString(", ").ifEmpty { null })
        prop(sb, "XPath", UiTree.xpath(c.data.root, n.idx), mono = true)
        prop(sb, "Paket", n.pkg ?: c.pkg, mono = true)
        prop(sb, "Activity", c.activity ?: host.foregroundActivity(), mono = true)
        return sb
    }

    private fun keySpan(sb: SpannableStringBuilder, key: String) {
        val start = sb.length
        sb.append(key)
        sb.setSpan(ForegroundColorSpan(OverlayColors.MUTED), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun colored(sb: SpannableStringBuilder, text: String, color: Int) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun prop(sb: SpannableStringBuilder, key: String, value: String?, mono: Boolean = false) {
        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
        keySpan(sb, key)
        sb.append("  ")
        val start = sb.length
        if (value.isNullOrEmpty()) {
            sb.append("—")
            sb.setSpan(ForegroundColorSpan(OverlayColors.DIM), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.append(value)
            if (mono) sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun flagsLine(sb: SpannableStringBuilder, n: UiNode) {
        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
        keySpan(sb, "Flag")
        sb.append("  ")
        val flags = listOf(
            "clickable" to n.clickable,
            "long-clickable" to n.longClickable,
            "scrollable" to n.scrollable,
            "editable" to n.editable,
            "checkable" to n.checkable,
            "checked" to n.checked,
            "enabled" to n.enabled,
            "focusable" to n.focusable,
            "focused" to n.focused,
            "selected" to n.selected,
            "visible" to n.visible,
            "password" to n.password,
            "heading" to n.heading,
        )
        flags.forEachIndexed { i, (name, on) ->
            if (i > 0) sb.append("  ")
            colored(sb, (if (on) "✓ " else "✗ ") + name, if (on) OverlayColors.GREEN_LIGHT else OverlayColors.DIM)
        }
    }

    private fun selectParent() {
        val c = capture as? Capture.Ready ?: return
        val layer = layerView ?: return
        val sel = layer.selected ?: return
        val parent = UiTree.parentOf(c.data.root, sel.idx)
        if (parent == null || parent.cls == NodeCapture.SYNTHETIC_ROOT_CLS) {
            showFlash("Sudah di elemen paling atas")
            return
        }
        layer.select(parent)
    }

    private fun selectChild() {
        if (capture !is Capture.Ready) return
        val layer = layerView ?: return
        val sel = layer.selected ?: return
        val child = sel.children.firstOrNull { it.visible && !it.bounds.isEmpty() } ?: sel.children.firstOrNull()
        if (child == null) {
            showFlash("Elemen ini tidak punya anak")
            return
        }
        layer.select(child)
    }

    private fun toggleFilter() {
        filterOnly = !filterOnly
        layerView?.onlyActionable = filterOnly
        propertyPanel?.setFilterActive(filterOnly)
        renderPanel(scrollTop = false)
        showFlash(if (filterOnly) "Filter: hanya yang bisa diklik" else "Filter mati: semua elemen")
    }

    private fun copySelected() {
        val c = capture as? Capture.Ready ?: return
        val sel = layerView?.selected ?: return
        val text = SessionStore.json.encodeToString(UiNode.serializer(), sel.copy(children = emptyList())) +
            "\nxpath: " + UiTree.xpath(c.data.root, sel.idx)
        val ok = try {
            val cm = host.context.getSystemService(ClipboardManager::class.java)
            if (cm == null) {
                false
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("UI Mapper – elemen", text))
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "clipboard failed", e)
            false
        }
        showFlash(if (ok) "Properti elemen disalin ke papan klip" else "Gagal menyalin ke papan klip")
    }

    // =================================================================== teardown

    private fun hideAll() {
        snapAnimator?.cancel()
        snapAnimator = null
        main.removeCallbacks(hideFlashRunnable)
        main.removeCallbacks(geometryRunnable)
        disableInspect()
        menuWin?.let { detach(it) }
        flashWin?.let { detach(it) }
        bubbleWin?.let { detach(it) }
        unregisterDisplayListener()
    }

    private fun destroyInternal() {
        if (destroyed) return
        hideAll()
        destroyed = true
        captureJob?.cancel()
        captureJob = null
        main.removeCallbacksAndMessages(null)
        layerView?.listener = null
        bubbleView?.onConfigChanged = null
        menuPanel?.let { it.root.onOutsideTouch = null }
    }

    private companion object {
        const val TAG = "UiMapperOverlay"
        const val FLASH_MS = 2500L
        const val GEOMETRY_DELAY_MS = 250L
        const val LAYER_REFRESH_DELAY_MS = 300L
        const val MENU_REOPEN_GUARD_MS = 250L
        const val EDGE_MARGIN_DP = 6
        const val MENU_WIDTH_DP = 264
        const val MENU_CHROME_DP = 96
        const val PANEL_MAX_WIDTH_DP = 560
        const val PANEL_CHROME_DP = 150
        const val PANEL_HEIGHT_FRACTION = 0.42f
        const val MAX_VALUE_CHARS = 300
        const val MSG_LOADING = "Memuat struktur..."
        const val MSG_NOTHING =
            "Tidak ada layar aplikasi yang bisa dibaca. Buka aplikasi yang ingin diinspeksi, lalu ketuk Segarkan."
        const val MSG_EMPTY =
            "Layar ini tidak punya elemen yang terlihat (mungkin game, video, atau tampilan yang digambar sendiri). Ketuk Segarkan untuk mencoba lagi."
        const val MSG_SERVICE_OFF = "Layanan UI Mapper belum terhubung"
    }
}
