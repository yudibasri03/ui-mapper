package app.uimapper.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import app.uimapper.core.ScreenSignature
import app.uimapper.core.UiTree
import app.uimapper.model.ActionType
import app.uimapper.model.EdgeSource
import app.uimapper.model.ElementRef
import app.uimapper.model.NavEdge
import app.uimapper.model.START_NODE
import app.uimapper.model.ScreenSnapshot
import app.uimapper.model.ScreenSummary
import app.uimapper.model.Session
import app.uimapper.model.SessionMode
import app.uimapper.model.UiNode
import app.uimapper.model.isExternalNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch

/**
 * File-backed store. Layout under filesDir/sessions/<sessionId>/:
 *   session.json          [Session] (screen summaries + edges)
 *   screens/<Sx>.json     full [ScreenSnapshot] with the UI tree
 *   shots/<Sx>.jpg        screenshot (optional)
 *
 * All functions are thread-safe and blocking (file I/O): call them from Dispatchers.IO. The stored
 * sessions are loaded on a background thread after [init]; every call waits for that load to finish.
 *
 * Mutations write to disk first and only then update the in-memory state, so a failed write (e.g. a full
 * disk) throws without leaving memory and disk out of sync.
 */
object SessionStore {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private const val TAG = "UiMapper/Store"
    private const val SESSION_FILE = "session.json"
    private const val MAX_SHOT_WIDTH = 720
    private const val SHOT_QUALITY = 82

    @Volatile private var rootDir: File? = null
    private val root: File get() = rootDir ?: throw IllegalStateException("SessionStore.init() was not called")

    private val lock = Any()
    private val loaded = CountDownLatch(1)
    private val cache = LinkedHashMap<String, Session>()
    private val screenCache = object : LinkedHashMap<String, ScreenSnapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScreenSnapshot>?) = size > 12
    }

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())

    /** All sessions, most recently updated first. */
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _loading = MutableStateFlow(true)

    /** True until the stored sessions have been read after [init]. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Sets the storage folder and starts reading the stored sessions on a background thread (decoding every
     * session.json can take a while, and this is called from Application.onCreate on the main thread).
     */
    fun init(context: Context) {
        val dir: File
        synchronized(lock) {
            if (rootDir != null) return
            dir = File(context.filesDir, "sessions")
            rootDir = dir
        }
        Thread({ loadAll(dir) }, "SessionStore-load").start()
    }

    private fun loadAll(dir: File) {
        try {
            synchronized(lock) {
                dir.mkdirs()
                dir.listFiles()?.forEach { d ->
                    if (!d.isDirectory) return@forEach
                    try {
                        loadSessionDirLocked(d)?.let { cache[it.id] = it }
                    } catch (e: Exception) {
                        Log.w(TAG, "Session ${d.name} could not be loaded", e)
                    }
                }
                publishLocked()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Loading sessions failed", e)
        } finally {
            _loading.value = false
            loaded.countDown()
        }
    }

    /** session.json, else its temp copy, else a session rebuilt from screens/ so nothing silently vanishes. */
    private fun loadSessionDirLocked(dir: File): Session? {
        val main = File(dir, SESSION_FILE)
        val tmp = File(dir, "$SESSION_FILE.tmp")
        for (f in listOf(main, tmp)) {
            if (!f.isFile) continue
            val s = runCatching { json.decodeFromString(Session.serializer(), f.readText()) }.getOrNull()
            if (s != null) return s
        }
        val screenFiles = File(dir, "screens").listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        if (!main.exists() && screenFiles.isEmpty()) return null
        Log.w(TAG, "session.json of ${dir.name} is unreadable; rebuilding it from the stored screens")
        val recovered = recoverSession(dir, screenFiles.toList())
        if (main.exists()) main.renameTo(File(dir, "$SESSION_FILE.corrupt"))
        try {
            writeSessionLocked(recovered)
        } catch (e: Exception) {
            Log.w(TAG, "Writing the recovered session ${dir.name} failed", e)
        }
        return recovered
    }

    private fun recoverSession(dir: File, screenFiles: List<File>): Session {
        val shots = File(dir, "shots")
        val snaps = screenFiles
            .mapNotNull { f -> runCatching { json.decodeFromString(ScreenSnapshot.serializer(), f.readText()) }.getOrNull() }
            .sortedBy { it.id.removePrefix("S").toIntOrNull() ?: Int.MAX_VALUE }
        val summaries = snaps.map { snap ->
            ScreenSummary(
                id = snap.id,
                label = snap.label.ifBlank { snap.id },
                pkg = snap.pkg,
                activity = snap.activity,
                title = snap.title,
                signature = snap.signature,
                features = snap.features,
                screenshot = snap.screenshot?.takeIf { File(shots, it).isFile },
                screenW = snap.screenW,
                screenH = snap.screenH,
                nodeCount = snap.nodeCount,
                clickableCount = snap.clickableCount,
                firstSeen = snap.capturedAt,
                lastSeen = snap.capturedAt,
                visits = 1,
            )
        }
        val modified = dir.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        val maxNo = snaps.maxOfOrNull { it.id.removePrefix("S").toIntOrNull() ?: 0 } ?: 0
        return Session(
            id = dir.name,
            name = "Sesi dipulihkan (${dir.name})",
            targetPkg = snaps.map { it.pkg }.distinct().singleOrNull(),
            mode = SessionMode.RECORD,
            createdAt = snaps.minOfOrNull { it.capturedAt }?.takeIf { it > 0L } ?: modified,
            updatedAt = modified,
            screens = summaries,
            nextScreenNo = maxNo + 1,
        )
    }

    /** Blocks until the stored sessions are loaded. */
    private fun awaitLoaded() {
        if (loaded.count == 0L) return
        if (rootDir == null) throw IllegalStateException("SessionStore.init() was not called")
        try {
            loaded.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun rootDir(): File = root
    fun sessionDir(id: String): File = File(root, id)
    fun screensDir(id: String): File = File(sessionDir(id), "screens")
    fun shotsDir(id: String): File = File(sessionDir(id), "shots")

    fun create(name: String, targetPkg: String?, appLabel: String?, mode: SessionMode): Session {
        awaitLoaded()
        val now = System.currentTimeMillis()
        val s = Session(
            id = "s" + now.toString(36) + (100..999).random(),
            name = name,
            targetPkg = targetPkg,
            appLabel = appLabel,
            mode = mode,
            createdAt = now,
            updatedAt = now,
            device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        synchronized(lock) {
            try {
                writeSessionLocked(s)
            } catch (e: Exception) {
                sessionDir(s.id).deleteRecursively()
                throw e
            }
            cache[s.id] = s
            publishLocked()
        }
        return s
    }

    fun get(id: String): Session? {
        awaitLoaded()
        return synchronized(lock) { cache[id] }
    }

    fun update(id: String, fn: (Session) -> Session): Session? {
        awaitLoaded()
        return synchronized(lock) {
            val cur = cache[id] ?: return null
            val next = fn(cur).copy(updatedAt = System.currentTimeMillis())
            writeSessionLocked(next)
            cache[id] = next
            publishLocked()
            next
        }
    }

    fun rename(id: String, name: String): Session? = update(id) { it.copy(name = name) }

    fun delete(id: String) {
        awaitLoaded()
        synchronized(lock) {
            cache.remove(id)
            screenCache.keys.removeAll { it.startsWith("$id/") }
            sessionDir(id).deleteRecursively()
            publishLocked()
        }
    }

    /**
     * Deletes every stored session, including folders that could not be read as a session.
     * Returns the number of sessions that were listed.
     */
    fun deleteAll(): Int {
        awaitLoaded()
        return synchronized(lock) {
            val count = cache.size
            cache.clear()
            screenCache.clear()
            root.listFiles()?.forEach { it.deleteRecursively() }
            publishLocked()
            count
        }
    }

    fun loadScreen(sessionId: String, screenId: String): ScreenSnapshot? {
        awaitLoaded()
        return synchronized(lock) { loadScreenLocked(sessionId, screenId) }
    }

    private fun loadScreenLocked(sessionId: String, screenId: String): ScreenSnapshot? {
        val key = "$sessionId/$screenId"
        screenCache[key]?.let { return it }
        val f = File(screensDir(sessionId), "$screenId.json")
        if (!f.isFile) return null
        val snap = runCatching { json.decodeFromString(ScreenSnapshot.serializer(), f.readText()) }.getOrNull()
        if (snap != null) screenCache[key] = snap
        return snap
    }

    fun screenshotFile(sessionId: String, screenId: String): File? {
        val name = get(sessionId)?.screen(screenId)?.screenshot ?: return null
        return File(shotsDir(sessionId), name).takeIf { it.isFile }
    }

    data class ScreenMatch(val summary: ScreenSummary, val isNew: Boolean, val similarity: Float)

    /**
     * Read-only pre-check: the known screen [snapshot] would be matched to, or null if it would be
     * stored as a new screen. Use it to decide whether a screenshot is worth taking.
     */
    fun findMatch(sessionId: String, snapshot: ScreenSnapshot, threshold: Float): ScreenMatch? {
        awaitLoaded()
        return synchronized(lock) {
            val s = cache[sessionId] ?: return null
            val (best, score) = bestMatchLocked(s, snapshot)
            if (best != null && score >= threshold) ScreenMatch(best, false, score) else null
        }
    }

    private fun bestMatchLocked(s: Session, snapshot: ScreenSnapshot): Pair<ScreenSummary?, Float> {
        var best: ScreenSummary? = null
        var bestScore = 0f
        for (sc in s.screens) {
            if (sc.pkg != snapshot.pkg) continue
            if (sc.activity != null && snapshot.activity != null && sc.activity != snapshot.activity) continue
            val score = if (sc.signature == snapshot.signature) 1f
            else ScreenSignature.similarity(sc.features, snapshot.features)
            if (score > bestScore) {
                best = sc
                bestScore = score
            }
        }
        return best to bestScore
    }

    /**
     * Match [snapshot] against the session's known screens (same package, compatible activity, Jaccard
     * similarity of features >= [threshold]). A match bumps visits (unless [countVisit] is false);
     * otherwise a new screen "S<n>" is stored. [screenshot] must be a software bitmap (it is converted if
     * it is HARDWARE).
     *
     * When a matched screen gets its first screenshot, [snapshot] (the capture the image belongs to)
     * replaces the stored tree, so element outlines, sizes and orientation always match the image.
     */
    fun recordScreen(
        sessionId: String,
        snapshot: ScreenSnapshot,
        screenshot: Bitmap?,
        threshold: Float,
        countVisit: Boolean = true,
    ): ScreenMatch? {
        awaitLoaded()
        return synchronized(lock) {
            val s = cache[sessionId] ?: return null
            val now = System.currentTimeMillis()
            val (match, bestScore) = bestMatchLocked(s, snapshot)
            if (match != null && bestScore >= threshold) {
                var updated = if (countVisit) match.copy(lastSeen = now, visits = match.visits + 1) else match
                var edges = s.edges
                if (updated.screenshot == null && screenshot != null) {
                    val name = saveShotLocked(sessionId, match.id, screenshot)
                    if (name != null) {
                        val stored = snapshot.copy(id = match.id, label = match.label, screenshot = name, capturedAt = now)
                        writeScreenLocked(sessionId, stored)
                        updated = updated.copy(
                            screenshot = name,
                            screenW = stored.screenW,
                            screenH = stored.screenH,
                            nodeCount = stored.nodeCount,
                            clickableCount = stored.clickableCount,
                        )
                        edges = reResolveOutgoing(s.edges, match.id, stored.root)
                    }
                }
                if (updated == match && edges === s.edges) return ScreenMatch(updated, false, bestScore)
                val next = s.copy(
                    screens = s.screens.map { if (it.id == match.id) updated else it },
                    edges = edges,
                    updatedAt = now,
                )
                writeSessionLocked(next)
                cache[sessionId] = next
                publishLocked()
                return ScreenMatch(updated, false, bestScore)
            }

            val no = s.nextScreenNo
            val id = "S$no"
            val shotName = screenshot?.let { saveShotLocked(sessionId, id, it) }
            val label = uniqueLabel(
                s,
                snapshot.label.ifBlank { ScreenSignature.defaultLabel(snapshot.title, snapshot.activity, no) },
            )
            val stored = snapshot.copy(id = id, label = label, screenshot = shotName, capturedAt = now)
            writeScreenLocked(sessionId, stored)
            val summary = ScreenSummary(
                id = id,
                label = label,
                pkg = stored.pkg,
                activity = stored.activity,
                title = stored.title,
                signature = stored.signature,
                features = stored.features,
                screenshot = shotName,
                screenW = stored.screenW,
                screenH = stored.screenH,
                nodeCount = stored.nodeCount,
                clickableCount = stored.clickableCount,
                firstSeen = now,
                lastSeen = now,
                visits = 1,
            )
            val next = s.copy(screens = s.screens + summary, nextScreenNo = no + 1, updatedAt = now)
            writeSessionLocked(next)
            cache[sessionId] = next
            publishLocked()
            ScreenMatch(summary, true, 1f)
        }
    }

    /** Points the element references of [screenId]'s outgoing edges at the nodes of its new tree [root]. */
    private fun reResolveOutgoing(edges: List<NavEdge>, screenId: String, root: UiNode): List<NavEdge> =
        edges.map { e ->
            val el = e.element
            if (e.from != screenId || el == null || el.nodeIdx == null) {
                e
            } else {
                val node = UiTree.match(root, el)
                val ref = if (node != null) {
                    el.copy(nodeIdx = node.idx, path = UiTree.xpath(root, node.idx))
                } else {
                    el.copy(nodeIdx = null)
                }
                e.copy(element = ref)
            }
        }

    private fun isKnownNode(s: Session, id: String): Boolean =
        id == START_NODE || isExternalNode(id) || s.screen(id) != null

    /**
     * Add a navigation edge, or bump [NavEdge.count] of an identical existing one. Returns null (and stores
     * nothing) when [from] or [to] is not START, an external app or a screen of the session (e.g. it was
     * deleted meanwhile).
     */
    fun recordEdge(
        sessionId: String,
        from: String,
        to: String,
        action: ActionType,
        element: ElementRef?,
        source: EdgeSource,
    ): NavEdge? {
        awaitLoaded()
        return synchronized(lock) {
            val s = cache[sessionId] ?: return null
            if (!isKnownNode(s, from) || !isKnownNode(s, to)) return null
            val now = System.currentTimeMillis()
            val probe = NavEdge(id = "", from = from, to = to, action = action, element = element, source = source)
            val existing = s.edges.firstOrNull { it.dedupeKey == probe.dedupeKey }
            val (edge, next) = if (existing != null) {
                val upd = existing.copy(count = existing.count + 1, lastAt = now)
                upd to s.copy(edges = s.edges.map { if (it.id == existing.id) upd else it }, updatedAt = now)
            } else {
                val e = probe.copy(id = "E${s.nextEdgeNo}", firstAt = now, lastAt = now)
                e to s.copy(edges = s.edges + e, nextEdgeNo = s.nextEdgeNo + 1, updatedAt = now)
            }
            writeSessionLocked(next)
            cache[sessionId] = next
            publishLocked()
            edge
        }
    }

    fun updateScreenLabel(sessionId: String, screenId: String, label: String) {
        awaitLoaded()
        synchronized(lock) {
            val s = cache[sessionId] ?: return
            val next = s.copy(
                screens = s.screens.map { if (it.id == screenId) it.copy(label = label) else it },
                updatedAt = System.currentTimeMillis(),
            )
            writeSessionLocked(next)
            cache[sessionId] = next
            publishLocked()
            loadScreenLocked(sessionId, screenId)?.let { writeScreenLocked(sessionId, it.copy(label = label)) }
        }
    }

    /** Remove a screen, its files and every edge touching it. */
    fun deleteScreen(sessionId: String, screenId: String) {
        awaitLoaded()
        synchronized(lock) {
            val s = cache[sessionId] ?: return
            val shot = s.screen(screenId)?.screenshot
            val next = s.copy(
                screens = s.screens.filter { it.id != screenId },
                edges = s.edges.filter { it.from != screenId && it.to != screenId },
                updatedAt = System.currentTimeMillis(),
            )
            writeSessionLocked(next)
            cache[sessionId] = next
            screenCache.remove("$sessionId/$screenId")
            File(screensDir(sessionId), "$screenId.json").delete()
            shot?.let { File(shotsDir(sessionId), it).delete() }
            publishLocked()
        }
    }

    fun deleteEdge(sessionId: String, edgeId: String) {
        update(sessionId) { s -> s.copy(edges = s.edges.filter { it.id != edgeId }) }
    }

    // ---- internals ----

    private fun uniqueLabel(s: Session, base: String): String {
        val taken = s.screens.map { it.label }.toHashSet()
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    private fun publishLocked() {
        _sessions.value = cache.values.sortedByDescending { it.updatedAt }
    }

    private fun writeSessionLocked(s: Session) {
        val dir = sessionDir(s.id).apply { mkdirs() }
        atomicWrite(File(dir, SESSION_FILE), json.encodeToString(Session.serializer(), s))
    }

    private fun writeScreenLocked(sessionId: String, snap: ScreenSnapshot) {
        val dir = screensDir(sessionId).apply { mkdirs() }
        atomicWrite(File(dir, "${snap.id}.json"), json.encodeToString(ScreenSnapshot.serializer(), snap))
        screenCache["$sessionId/${snap.id}"] = snap
    }

    private fun saveShotLocked(sessionId: String, screenId: String, bitmap: Bitmap): String? = runCatching {
        val dir = shotsDir(sessionId).apply { mkdirs() }
        var bmp = bitmap
        if (bmp.config == Bitmap.Config.HARDWARE) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        if (bmp.width > MAX_SHOT_WIDTH) {
            val h = (bmp.height.toLong() * MAX_SHOT_WIDTH / bmp.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, MAX_SHOT_WIDTH, h, true)
            if (bmp !== bitmap && scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val name = "$screenId.jpg"
        val file = File(dir, name)
        val ok = try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, it) }
        } finally {
            if (bmp !== bitmap) bmp.recycle()
        }
        if (!ok) {
            file.delete()
            return@runCatching null
        }
        name
    }.getOrNull()

    /** Writes [text] to a temp file, syncs it to storage and renames it over [target]. */
    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                // Without this a power loss shortly after the rename can leave an empty or truncated file.
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // rename(2) replaces the target atomically; this copy is only a last-resort fallback.
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
