package app.uimapper.service

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import app.uimapper.core.AppInfo
import app.uimapper.core.UiTree
import app.uimapper.data.AppSettings
import app.uimapper.data.SessionStore
import app.uimapper.model.ActionType
import app.uimapper.model.Bounds
import app.uimapper.model.EXTERNAL_PREFIX
import app.uimapper.model.EdgeSource
import app.uimapper.model.ElementRef
import app.uimapper.model.NavEdge
import app.uimapper.model.START_NODE
import app.uimapper.model.UiNode
import app.uimapper.model.externalNodeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Content change that adds/removes views or panes (not just text or state of existing views). */
internal fun isStructuralContentChange(event: AccessibilityEvent): Boolean {
    var mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED
    }
    return (event.contentChangeTypes and mask) != 0
}

/**
 * Passive route mapping while the user navigates the target app themselves. Every UI transition is
 * captured once the screen settles, matched against the session's known screens, and linked to the
 * previous screen with the element the user tapped (or BACK / LAUNCH / EXTERNAL / UNKNOWN).
 *
 * Purely observational: it never clicks, scrolls or performs any action in the observed app.
 * All state is confined to the main thread (events arrive there and [ServiceHost.scope] is main).
 */
internal class RouteRecorder(
    private val host: ServiceHost,
    private val sessionId: String,
    private val targetPkg: String?,
) {

    /** The user interaction that probably caused the next transition. */
    private class Pending(
        val action: ActionType,
        val cls: String?,
        val resId: String?,
        val text: String?,
        val desc: String?,
        val bounds: Bounds?,
        val at: Long,
    ) {
        /** A settled capture taken after this tap still showed the same screen (the tap did not navigate). */
        var sawSameScreen = false
    }

    private val service: InspectorService? = host as? InspectorService

    private var stopped = false
    private var launched = false
    private var startEdgeRecorded = false

    private var currentScreenId: String? = null
    /** Latest capture of [currentScreenId] (fresher bounds than the stored first capture). */
    private var lastRoot: UiNode? = null
    private var lastSignature: String? = null
    private val history = ArrayDeque<String>()

    private var pending: Pending? = null
    /** A tap landed on a screen that was never captured: the next transition's trigger is unknown. */
    private var ambiguous = false
    private var outsidePkg: String? = null
    private var externalCheckPkg: String? = null
    /** The user left the target through the home screen / recents (not through the target app itself). */
    private var leftViaHome = false

    private var captureJob: Job? = null
    private var fallbackJob: Job? = null
    private var externalJob: Job? = null
    private var trailingJob: Job? = null
    private var running = false
    private var dirty = false
    private var lastCaptureAt = 0L
    private var lastContentTriggerAt = 0L

    // ---- lifecycle ----

    fun start(launched: Boolean) {
        if (stopped) return
        this.launched = launched
        logEvent(
            EventLog.Tag.REC,
            "Merekam " + (targetPkg ?: "aplikasi di depan") + (if (launched) " (membuka aplikasi)" else ""),
        )
        if (!launched) {
            scheduleCapture(restart = true)
        } else {
            // The launch normally produces window events; this covers an app already in front.
            fallbackJob = host.scope.launch {
                delay(START_FALLBACK_MS)
                if (!stopped && currentScreenId == null) scheduleCapture(restart = false)
            }
        }
    }

    fun stop() {
        if (!stopped) logEvent(EventLog.Tag.STOP, "Perekaman selesai")
        stopped = true
        captureJob?.cancel()
        captureJob = null
        fallbackJob?.cancel()
        fallbackJob = null
        externalJob?.cancel()
        externalJob = null
        trailingJob?.cancel()
        trailingJob = null
        pending = null
    }

    // ---- events ----

    fun onEvent(event: AccessibilityEvent) {
        if (stopped) return
        val pkg = event.packageName?.toString() ?: return
        val type = event.eventType

        // Home launcher / recents are ignorable, but leaving through them must be noticed first.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isHomeOrRecents(pkg, event.className?.toString())) {
            onLeftViaHome()
            return
        }
        if (isIgnorable(pkg)) return

        if (targetPkg != null && pkg != targetPkg) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                onForeignWindow(pkg, event.className?.toString())
            }
            return
        }

        when (type) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onTap(event, ActionType.CLICK)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> onTap(event, ActionType.LONG_CLICK)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                returnedToApp()
                scheduleCapture(restart = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!isStructuralContentChange(event)) return
                val now = SystemClock.uptimeMillis()
                val wait = lastContentTriggerAt + CONTENT_THROTTLE_MS - now
                if (wait > 0) {
                    // Throttled, but never dropped: the final state after a burst of changes must be read.
                    deferContentCapture(wait)
                    return
                }
                lastContentTriggerAt = now
                scheduleCapture(restart = false)
            }
            else -> Unit
        }
    }

    private fun onTap(event: AccessibilityEvent, action: ActionType) {
        val p = pendingFrom(event, action)
        returnedToApp()
        val previous = pending
        // The previous tap's result is still waiting to be captured, and this tap was made on a screen that
        // is not the current one: that intermediate screen was never captured, so which tap led to the next
        // captured screen is unknown. Better an UNKNOWN edge than this tap attributed to the wrong screen.
        if (previous != null && !previous.sawSameScreen && !running && captureJob?.isActive == true &&
            !tappedOn(lastRoot, p)
        ) {
            ambiguous = true
        }
        pending = p
        scheduleCapture(restart = true)
    }

    /** True when [p]'s element is part of [root] (the latest capture of the current screen). */
    private fun tappedOn(root: UiNode?, p: Pending): Boolean {
        if (root == null) return true
        if (p.bounds == null && p.resId == null && p.text == null && p.desc == null) return true
        return UiTree.flatten(root).any { n ->
            (p.bounds == null || n.bounds == p.bounds) &&
                (p.cls == null || n.cls == p.cls) &&
                (p.resId == null || n.resId == p.resId) &&
                (p.text == null || n.text == p.text) &&
                (p.desc == null || n.desc == p.desc)
        }
    }

    /** Reads the interaction target synchronously (the event is recycled after dispatch). */
    private fun pendingFrom(event: AccessibilityEvent, action: ActionType): Pending {
        val now = SystemClock.uptimeMillis()
        val src = try {
            event.source
        } catch (_: Exception) {
            null
        }
        if (src != null) {
            try {
                val rect = Rect()
                src.getBoundsInScreen(rect)
                // Privacy: never keep text of password or editable fields (typed input).
                val secret = src.isPassword || src.isEditable
                return Pending(
                    action = action,
                    cls = src.className?.toString() ?: event.className?.toString(),
                    resId = src.viewIdResourceName,
                    text = if (secret) null else src.text?.toString()?.take(MAX_TEXT),
                    desc = src.contentDescription?.toString()?.take(MAX_DESC),
                    bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom).takeUnless { it.isEmpty() },
                    at = now,
                )
            } catch (_: Exception) {
                // Stale source node: fall back to the event record below.
            }
        }
        val cls = event.className?.toString()
        val editableLike = cls != null && EDITABLE_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) }
        val text = if (event.isPassword || editableLike) {
            null
        } else {
            event.text?.joinToString(" ")?.takeIf { it.isNotBlank() }?.take(MAX_TEXT)
        }
        return Pending(
            action = action,
            cls = cls,
            resId = null,
            text = text,
            desc = event.contentDescription?.toString()?.take(MAX_DESC),
            bounds = null,
            at = now,
        )
    }

    // ---- leaving the target app ----

    /** Home screen or recents came to the front: the user left the target without the target doing it. */
    private fun onLeftViaHome() {
        leftViaHome = true
        outsidePkg = HOME_MARKER
        pending = null
        ambiguous = false
        externalJob?.cancel()
        externalJob = null
        externalCheckPkg = null
    }

    /** An event of the target app: the user is (back) in it. */
    private fun returnedToApp() {
        outsidePkg = null
        if (!leftViaHome) return
        leftViaHome = false
        // Back from the home screen / recents / another app opened there: whatever the target shows now is
        // a new entry point, not a transition from the screen that was left.
        currentScreenId = null
        lastSignature = null
        lastRoot = null
        pending = null
        ambiguous = false
    }

    private fun onForeignWindow(pkg: String, cls: String?) {
        if (pkg == outsidePkg || pkg == externalCheckPkg) return
        if (isLauncherLike(pkg, cls)) return
        if (leftViaHome) {
            // Opened from the home screen / recents, not by the target app: no edge.
            outsidePkg = pkg
            return
        }
        if (currentScreenId == null) return
        externalCheckPkg = pkg
        externalJob?.cancel()
        externalJob = host.scope.launch {
            try {
                // Confirm that the other app really took over (not a transient overlay window).
                delay(EXTERNAL_CONFIRM_MS)
                if (stopped || outsidePkg == pkg || leftViaHome) return@launch
                if (host.currentForegroundPackage() != pkg) return@launch
                if (stopped || outsidePkg == pkg || leftViaHome) return@launch
                val from = currentScreenId ?: return@launch
                val now = SystemClock.uptimeMillis()
                val p = pending?.takeIf { now - it.at <= PENDING_EDGE_MS }
                pending = null
                outsidePkg = pkg
                val element = p?.let { resolveElement(from, it, lastRoot) }
                val edge = withContext(Dispatchers.IO) {
                    SessionStore.recordEdge(sessionId, from, externalNodeId(pkg), ActionType.EXTERNAL, element, EdgeSource.MANUAL)
                }
                if (edge != null) logEdge(edge)
                val label = withContext(Dispatchers.IO) { AppInfo.label(host.context, pkg) }
                publish(screenId = null, label = null, isNew = false, status = "Pindah ke aplikasi lain: $label")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "External edge failed", e)
            } finally {
                if (externalCheckPkg == pkg) externalCheckPkg = null
            }
        }
    }

    private fun isLauncherLike(pkg: String, cls: String?): Boolean {
        if (isIgnorable(pkg)) return true
        val p = pkg.lowercase()
        if ("launcher" in p || p in KNOWN_HOME_PACKAGES) return true
        // e.g. com.miui.home.launcher.Launcher. Only the class's package part is checked, so an app's
        // own trampoline "com.foo.LauncherActivity" is not mistaken for the home screen.
        val classPackage = cls?.lowercase()?.substringBeforeLast('.', "") ?: return false
        return "launcher" in classPackage
    }

    /** The home launcher or the recents screen (never the target, UI Mapper, an IME or the shade). */
    private fun isHomeOrRecents(pkg: String, cls: String?): Boolean {
        if (pkg == host.ownPackage || pkg == targetPkg) return false
        if (service?.isHomePackage(pkg) == true) return true
        val c = cls?.lowercase().orEmpty()
        if (pkg == SYSTEM_UI_PKG) return "recents" in c
        // Name heuristics only when a target is set: with "all apps" a regular app must never be taken for
        // the home screen.
        if (targetPkg == null) return false
        val p = pkg.lowercase()
        return p in KNOWN_HOME_PACKAGES || "launcher" in p || "launcher" in c.substringBeforeLast('.', "")
    }

    private fun isIgnorable(pkg: String): Boolean =
        service?.isIgnorablePackage(pkg) ?: (pkg == host.ownPackage || pkg == SYSTEM_UI_PKG)

    // ---- capture scheduling ----

    /**
     * Debounced capture. [restart] re-arms a waiting capture (user-driven triggers); otherwise an
     * already waiting capture is kept so continuous animations cannot starve it. A capture that is
     * already running is never cancelled: it is marked dirty and re-run once afterwards.
     */
    private fun scheduleCapture(restart: Boolean) {
        if (stopped) return
        if (running) {
            dirty = true
            return
        }
        if (!restart && captureJob?.isActive == true) return
        captureJob?.cancel()
        captureJob = host.scope.launch {
            host.awaitIdle(AppSettings.settleMs, IDLE_TIMEOUT_MS)
            val wait = lastCaptureAt + MIN_CAPTURE_GAP_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            if (stopped) return@launch
            running = true
            dirty = false
            try {
                captureAndRecord()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Capture failed", e)
            } finally {
                running = false
                lastCaptureAt = SystemClock.uptimeMillis()
            }
            if (dirty && !stopped) {
                dirty = false
                captureJob = null
                scheduleCapture(restart = true)
            }
        }
    }

    /** A structural change inside the content-throttle window: make sure a capture still follows it. */
    private fun deferContentCapture(wait: Long) {
        if (running) {
            // The running capture may already have read the tree: re-run once it is done.
            dirty = true
            return
        }
        // A waiting capture reads the screen after this change anyway (it waits for the UI to settle).
        if (captureJob?.isActive == true) return
        if (trailingJob?.isActive == true) return
        trailingJob = host.scope.launch {
            delay(wait)
            trailingJob = null
            if (stopped) return@launch
            lastContentTriggerAt = SystemClock.uptimeMillis()
            scheduleCapture(restart = false)
        }
    }

    private suspend fun captureAndRecord() {
        // Taken before the first suspension: a tap made while this capture runs belongs to the next
        // transition, so it must be neither used nor cleared here.
        val p0 = pending
        val ambiguous0 = ambiguous

        val cap = host.captureScreen(targetPkg, withScreenshot = false) ?: return
        cap.screenshot?.recycle()
        if (stopped) return
        val snap = cap.snapshot
        if (targetPkg == null && isIgnorable(snap.pkg)) return

        var prev = currentScreenId
        val session = withContext(Dispatchers.IO) { SessionStore.get(sessionId) } ?: return
        if (stopped) return
        // Screens deleted from the session meanwhile (UI Mapper's screen detail) must not get new edges.
        history.retainAll { session.screen(it) != null }
        if (prev != null && session.screen(prev) == null) {
            if (currentScreenId == prev) {
                currentScreenId = null
                lastSignature = null
                lastRoot = null
            }
            prev = null
        }

        if (prev != null && snap.signature == lastSignature) {
            // Identical re-capture of the current screen: nothing new to store.
            lastRoot = snap.root
            onSameScreen(p0)
            return
        }

        val threshold = AppSettings.similarityThreshold
        val wantShots = AppSettings.captureScreenshots
        val pre = withContext(Dispatchers.IO) { SessionStore.findMatch(sessionId, snap, threshold) }

        if (pre != null && pre.summary.id == prev) {
            // Same screen with changed content (scroll, loaded data): no visit, no edge.
            lastRoot = snap.root
            lastSignature = snap.signature
            if (wantShots && pre.summary.screenshot == null) {
                val shot = host.screenshot(snap.root)
                if (shot != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            SessionStore.recordScreen(sessionId, snap, shot, threshold, countVisit = false)
                        }
                    } finally {
                        shot.recycle()
                    }
                }
            }
            onSameScreen(p0)
            return
        }

        val needShot = wantShots && (pre == null || pre.summary.screenshot == null)
        val shot = if (needShot) host.screenshot(snap.root) else null
        val match = try {
            withContext(Dispatchers.IO) { SessionStore.recordScreen(sessionId, snap, shot, threshold) }
        } finally {
            shot?.recycle()
        } ?: return
        if (stopped) return

        val newId = match.summary.id
        if (newId == prev) {
            lastRoot = snap.root
            lastSignature = snap.signature
            onSameScreen(p0)
            return
        }

        // ---- edge ----
        val now = SystemClock.uptimeMillis()
        val p = p0?.takeIf { now - it.at <= PENDING_EDGE_MS }
        var from: String? = null
        var action = ActionType.UNKNOWN
        var element: ElementRef? = null
        if (prev == null) {
            if (launched && !startEdgeRecorded) {
                from = START_NODE
                action = ActionType.LAUNCH
                startEdgeRecorded = true
            }
        } else {
            from = prev
            val backToPrevious = history.size >= 2 && history[history.size - 2] == newId
            when {
                // A screen in between was never captured: the trigger of this transition is unknown.
                ambiguous0 -> action = ActionType.UNKNOWN
                // A tap that already proved not to navigate does not explain a return to the previous screen.
                p != null && !(p.sawSameScreen && backToPrevious) -> {
                    action = p.action
                    element = resolveElement(prev, p, lastRoot)
                }
                backToPrevious -> action = ActionType.BACK
                else -> action = ActionType.UNKNOWN
            }
        }
        if (from != null) {
            val edgeFrom: String = from
            val edgeAction = action
            val edgeElement = element
            val edge = withContext(Dispatchers.IO) {
                SessionStore.recordEdge(sessionId, edgeFrom, newId, edgeAction, edgeElement, EdgeSource.MANUAL)
            }
            if (edge != null) logEdge(edge)
        }

        // ---- navigation stack: returning to a screen already on the stack pops back to it ----
        val existing = history.lastIndexOf(newId)
        if (existing >= 0) {
            while (history.size > existing + 1) history.removeLast()
        } else {
            history.addLast(newId)
            while (history.size > MAX_HISTORY) history.removeFirst()
        }

        currentScreenId = newId
        lastRoot = snap.root
        lastSignature = snap.signature
        if (pending === p0) pending = null
        ambiguous = false

        val label = match.summary.label
        if (match.isNew) {
            logEvent(
                EventLog.Tag.NEW,
                "$newId · $label (${match.summary.nodeCount} elemen, ${match.summary.clickableCount} klik)",
            )
        }
        publish(
            screenId = newId,
            label = label,
            isNew = match.isNew,
            status = "$newId · $label" + if (match.isNew) " (baru)" else "",
        )
    }

    /** The capture showed the screen that was already current. [p0] is the pending tap at capture start. */
    private fun onSameScreen(p0: Pending?) {
        ambiguous = false
        val p = pending
        if (p != null && p === p0) {
            // A capture that started after this tap (once the UI settled) still shows the same screen: the
            // tap did not navigate (toggle, checkbox, ...). Keep it only for navigations that are just slow.
            p.sawSameScreen = true
            if (SystemClock.uptimeMillis() - p.at > AppSettings.settleMs + SAME_SCREEN_GRACE_MS) {
                pending = null
                return
            }
        }
        dropStalePending()
    }

    private fun dropStalePending() {
        val p = pending ?: return
        if (SystemClock.uptimeMillis() - p.at > PENDING_SAME_SCREEN_MS) pending = null
    }

    // ---- element resolution ----

    /**
     * Locate the tapped element in the stored snapshot of [fromId] (so the UI can highlight it by
     * nodeIdx / path); else in the latest live capture of that screen; else keep the raw event info.
     */
    private suspend fun resolveElement(fromId: String, p: Pending, liveRoot: UiNode?): ElementRef {
        val stored: UiNode? = try {
            withContext(Dispatchers.IO) { SessionStore.loadScreen(sessionId, fromId)?.root }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Loading screen $fromId failed", e)
            null
        }
        return withContext(Dispatchers.Default) {
            if (stored != null) {
                val node = UiTree.match(stored, p.cls, p.resId, p.text, p.desc, p.bounds)
                if (node != null) return@withContext UiTree.toElementRef(stored, node)
            }
            if (liveRoot != null) {
                val live = UiTree.match(liveRoot, p.cls, p.resId, p.text, p.desc, p.bounds)
                if (live != null) {
                    // The live node carries the full identity (subtree label); find it in the stored tree.
                    if (stored != null) {
                        val again = UiTree.match(stored, live.cls, live.resId, live.text, live.desc, null)
                        if (again != null) return@withContext UiTree.toElementRef(stored, again)
                    }
                    return@withContext ElementRef(
                        cls = live.cls,
                        resId = live.resId,
                        text = live.text,
                        desc = live.desc,
                        label = UiTree.labelOf(live),
                        bounds = live.bounds,
                    )
                }
            }
            rawElement(p)
        }
    }

    private fun rawElement(p: Pending): ElementRef = ElementRef(
        cls = p.cls,
        resId = p.resId,
        text = p.text,
        desc = p.desc,
        label = cleanLabel(p.text) ?: cleanLabel(p.desc) ?: p.resId?.substringAfter(":id/"),
        bounds = p.bounds,
    )

    private fun cleanLabel(s: String?): String? =
        s?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL)

    // ---- publishing ----

    private suspend fun publish(screenId: String?, label: String?, isNew: Boolean, status: String) {
        val session = withContext(Dispatchers.IO) { SessionStore.get(sessionId) }
        if (stopped) return
        ServiceBridge.update { st ->
            if (st.mode != ServiceMode.RECORDING || st.sessionId != sessionId) {
                st
            } else {
                st.copy(
                    currentScreenId = screenId ?: st.currentScreenId,
                    screenCount = session?.screens?.size ?: st.screenCount,
                    edgeCount = session?.edges?.size ?: st.edgeCount,
                    status = status,
                )
            }
        }
        if (isNew && screenId != null) flash("🆕 $screenId · ${label.orEmpty()}")
    }

    private fun flash(message: String) {
        try {
            host.overlay?.flash(message)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay flash failed", e)
        }
    }

    /** Appends one entry to the shared live log; never lets a logging failure disturb recording. */
    private fun logEvent(tag: EventLog.Tag, message: String) {
        try {
            EventLog.log(tag, message)
        } catch (e: Exception) {
            Log.w(TAG, "Event log failed", e)
        }
    }

    /**
     * One log line describing a freshly recorded [edge]; the tag is chosen by its action and the label
     * reuses the element the recorder already resolved (no new text is read from live nodes).
     */
    private fun logEdge(edge: NavEdge) {
        val (tag, message) = when (edge.action) {
            ActionType.CLICK, ActionType.LONG_CLICK -> {
                val label = edge.element?.display()?.takeIf { it.isNotBlank() } ?: "elemen"
                EventLog.Tag.TAP to "Ketuk \"$label\" — ${edge.from} → ${edge.to}"
            }
            ActionType.LAUNCH -> EventLog.Tag.REC to "Buka aplikasi → ${edge.to}"
            ActionType.BACK -> EventLog.Tag.BACK to "Kembali ${edge.from} → ${edge.to}"
            ActionType.EXTERNAL ->
                EventLog.Tag.EXT to "Pindah ke aplikasi lain (${edge.to.removePrefix(EXTERNAL_PREFIX)})"
            else -> EventLog.Tag.SEEN to "Transisi ${edge.from} → ${edge.to}"
        }
        logEvent(tag, message)
    }

    private companion object {
        const val TAG = "UiMapper/Recorder"
        const val SYSTEM_UI_PKG = "com.android.systemui"
        /** [outsidePkg] while the home screen / recents is in front. */
        const val HOME_MARKER = "#home"

        const val IDLE_TIMEOUT_MS = 4000L
        const val MIN_CAPTURE_GAP_MS = 700L
        const val CONTENT_THROTTLE_MS = 1500L
        const val PENDING_EDGE_MS = 6000L
        const val PENDING_SAME_SCREEN_MS = 3000L
        /** Beyond the settle time: how long a tap that left the screen unchanged may still explain a transition. */
        const val SAME_SCREEN_GRACE_MS = 500L
        const val START_FALLBACK_MS = 1500L
        const val EXTERNAL_CONFIRM_MS = 350L
        const val MAX_HISTORY = 60

        const val MAX_TEXT = 1000
        const val MAX_DESC = 500
        const val MAX_LABEL = 60

        /** Class-name fragments of text-entry widgets whose event text may be user input. */
        val EDITABLE_CLASS_HINTS = listOf("EditText", "TextInput", "TextField", "AutoComplete", "SearchView")

        /** Home apps whose package name does not contain "launcher". */
        val KNOWN_HOME_PACKAGES = setOf(
            "com.miui.home",
            "com.mi.android.globallauncher",
            "com.huawei.android.launcher",
            "com.hihonor.android.launcher",
            "com.oppo.launcher",
            "com.bbk.launcher2",
            "com.sec.android.app.launcher",
        )
    }
}
