package app.uimapper.service

import android.content.Context
import android.graphics.Bitmap
import app.uimapper.model.ScreenSnapshot
import app.uimapper.model.UiNode
import kotlinx.coroutines.CoroutineScope

/** A capture of the current screen, not yet stored (snapshot.id == ""). */
class CapturedScreen(val snapshot: ScreenSnapshot, val screenshot: Bitmap?)

/**
 * What [InspectorService] exposes to its collaborators ([OverlayUi] implementation, RouteRecorder). All members must be called on the main thread unless stated otherwise.
 */
interface ServiceHost {
    /** The AccessibilityService itself (use for WindowManager / resources / startActivity). */
    val context: Context

    /** Main-thread scope tied to the service lifetime. */
    val scope: CoroutineScope

    val ownPackage: String

    /** The floating overlay, or null if not created. */
    val overlay: OverlayUi?

    /**
     * Cheap guess of the foreground app (not our overlay / systemui / IME / home launcher), tracked from
     * window events. No IPC, safe to call while rendering UI. Null when the home screen is in front.
     */
    fun foregroundPackage(): String?

    /**
     * Package of the top application window, read from the live window list (IPC runs off the main
     * thread). Null when no mappable app is on screen (e.g. the home screen or UI Mapper itself).
     */
    suspend fun currentForegroundPackage(): String?

    /** Class name from the latest TYPE_WINDOW_STATE_CHANGED of the foreground package (activity/dialog). */
    fun foregroundActivity(): String?

    /** Uptime millis of the last UI event from another app (window/content/scroll changes). */
    fun lastEventAt(): Long

    /**
     * Suspend until no UI event from other apps arrived for [quietMs], or [timeoutMs] elapsed.
     * Returns true if the UI settled, false on timeout.
     */
    suspend fun awaitIdle(quietMs: Long = 800, timeoutMs: Long = 5000): Boolean

    /**
     * Capture the current screen into an unsaved snapshot. Overlays are hidden while a screenshot is
     * taken. Screenshot requires API 30+ and is null otherwise or on failure; it is a software bitmap,
     * redacted like [screenshot]. Returns null when no suitable window is on screen. The window list and
     * tree are read off the main thread with a timeout, so a hung app cannot block the caller's thread.
     */
    suspend fun captureScreen(targetPkg: String?, withScreenshot: Boolean): CapturedScreen?

    /**
     * Screenshot only (API 30+, else null), overlays hidden while shooting, returned as a software
     * ARGB_8888 bitmap. Respects the platform rate limit (retries once when shot too soon).
     *
     * Privacy: the bounds of every editable / password node of [tree] (the capture the image belongs to),
     * the keyboard and system windows (status bar, notifications) are painted over. Null when the image
     * could not be redacted or the notification shade covers the screen.
     */
    suspend fun screenshot(tree: UiNode?): Bitmap?

    /** Start the launcher activity of [pkg]. */
    fun launchApp(pkg: String): Boolean

    /** Real display size in pixels (width, height). */
    fun screenSize(): Pair<Int, Int>

    /** Set text on the live EDITABLE node in the foreground target app that matches [ref]. Requires AppSettings.allowTextEditing == true. Empty string clears the field. Returns true only when a matching editable node was found and ACTION_SET_TEXT succeeded. Never stores or logs the text. */
    suspend fun setNodeText(ref: app.uimapper.model.ElementRef, text: String): Boolean
}

/** Floating overlay shown on top of other apps (implemented by overlay.OverlayController). */
interface OverlayUi {
    val isShowing: Boolean

    /** Show the draggable control bubble. */
    fun show()

    /** Remove every overlay window. */
    fun hide()

    /** Toggle the element-inspection layer (bounds + tap-to-inspect + property panel). */
    fun setInspect(enabled: Boolean)

    /**
     * Temporarily make every overlay view transparent (e.g. while a screenshot is taken). The windows stay
     * in place and keep consuming touches, so nothing reaches the app underneath meanwhile.
     */
    fun setHiddenForCapture(hidden: Boolean)

    /** Refresh bubble/panel UI from the latest service state. */
    fun onStateChanged(state: ServiceState)

    /**
     * A window appeared/changed or a pane/subtree changed in some app (including the launcher and system
     * UI). Lets the inspect layer re-read a screen that changed underneath it.
     */
    fun onScreenMaybeChanged()

    /** The screen turned off: remove everything that shows captured data (the device is being locked). */
    fun onScreenOff()

    /** Short transient message near the bubble. */
    fun flash(message: String)

    fun destroy()
}
