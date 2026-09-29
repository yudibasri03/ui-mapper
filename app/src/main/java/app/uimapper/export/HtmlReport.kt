package app.uimapper.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.uimapper.core.RouteFinder
import app.uimapper.core.UiTree
import app.uimapper.data.SessionStore
import app.uimapper.model.ActionType
import app.uimapper.model.EXTERNAL_PREFIX
import app.uimapper.model.EdgeSource
import app.uimapper.model.NavEdge
import app.uimapper.model.START_NODE
import app.uimapper.model.ScreenSnapshot
import app.uimapper.model.ScreenSummary
import app.uimapper.model.Session
import app.uimapper.model.SessionMode
import app.uimapper.model.UiNode
import app.uimapper.model.isExternalNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Single-file, offline HTML report of a session: header, navigation map (inline SVG laid out here,
 * no scripts), Mermaid source, routes from the start screen, all transitions and one card per screen
 * (embedded screenshot with element outlines, actionable elements, full hierarchy).
 *
 * Every string that comes from a captured app is untrusted and goes through [h]. The page also
 * carries a CSP that forbids scripts and any network access, as a second line of defence.
 * Blocking (reads snapshots and screenshots): call from Dispatchers.IO.
 */
internal object HtmlReport {
    fun write(session: Session, exportedAt: Long, out: Writer) {
        ReportWriter(session, exportedAt, out).write()
        out.flush()
    }
}

private const val SHOT_MAX_W = 480
private const val SHOT_QUALITY = 70
private const val MAX_ELEMENT_ROWS = 250
private const val TREE_TOTAL_BUDGET = 30_000
private const val TREE_MIN_NODES = 150
private const val TREE_MAX_NODES = 800
private const val TREE_MAX_DEPTH = 32
private const val MAP_LABEL_CHARS = 24
private const val MAP_EDGE_CHARS = 22

/** HTML-escapes untrusted text (& < > " ') and turns control characters into spaces. */
private fun h(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    var clean = true
    for (ch in s) {
        if (ch == '&' || ch == '<' || ch == '>' || ch == '"' || ch == '\'' || isControl(ch)) {
            clean = false
            break
        }
    }
    if (clean) return s
    val sb = StringBuilder(s.length + 16)
    for (ch in s) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(if (isControl(ch)) ' ' else ch)
        }
    }
    return sb.toString()
}

private fun isControl(ch: Char): Boolean = (ch < ' ' && ch != '\n' && ch != '\t') || ch == '\u007F'

private fun anchorSafe(id: String): String {
    val sb = StringBuilder(id.length)
    for (ch in id) {
        val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-'
        sb.append(if (ok) ch else '_')
    }
    return sb.toString()
}

private fun screenAnchor(id: String) = "screen-" + anchorSafe(id)
private fun routeAnchor(id: String) = "route-" + anchorSafe(id)
private fun edgeAnchor(id: String) = "edge-" + anchorSafe(id)
private fun elementAnchor(screenId: String, idx: Int) = "el-" + anchorSafe(screenId) + "-" + idx

private fun pct(v: Float): String = String.format(Locale.US, "%.3f", v)

private class ReportWriter(
    private val s: Session,
    private val exportedAt: Long,
    private val w: Writer,
) {
    private val dateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("id-ID"))
    private val screenById: Map<String, ScreenSummary> = s.screens.associateBy { it.id }
    private val startId: String? = RouteFinder.startScreenId(s)
    private val depths: Map<String, Int> = RouteFinder.depths(s)
    private val routes: Map<String, List<NavEdge>> = RouteFinder.routesFromStart(s)
    private val treeBudget: Int =
        (TREE_TOTAL_BUDGET / s.screens.size.coerceAtLeast(1)).coerceIn(TREE_MIN_NODES, TREE_MAX_NODES)

    private class ActionableItem(val no: Int, val node: UiNode, val label: String, val key: String)

    private class TreeState(val budget: Int) {
        var emitted = 0
        var truncated = false
    }

    private class Shot(val dataUri: String, val width: Int, val height: Int)

    fun write() {
        head()
        header()
        w.write("<main class=\"wrap\">\n")
        mapSection()
        routesSection()
        transitionsSection()
        screensSection()
        w.write("</main>\n")
        footer()
    }

    // ------------------------------------------------------------------ small helpers

    private fun appName(): String = s.appLabel?.takeIf { it.isNotBlank() } ?: s.targetPkg ?: "Aplikasi"

    private fun date(ms: Long): String = if (ms <= 0L) "–" else dateFmt.format(Date(ms))

    private fun screenTitle(id: String): String = screenById[id]?.let { "${it.id} · ${it.label}" } ?: id

    /** Escaped (and linked, for screens) label of any graph node id. */
    private fun nodeRef(id: String): String = when {
        id == START_NODE -> "Mulai"
        isExternalNode(id) -> "Aplikasi lain (<code>" + h(id.removePrefix(EXTERNAL_PREFIX)) + "</code>)"
        id in screenById -> "<a href=\"#" + screenAnchor(id) + "\">" + h(screenTitle(id)) + "</a>"
        else -> h(id)
    }

    private fun modeLabel(mode: SessionMode): String = when (mode) {
        SessionMode.RECORD -> "Rekam navigasi"
        SessionMode.EXPLORE -> "Jelajah"
        SessionMode.SNAPSHOT -> "Snapshot"
    }

    private fun sourceLabel(src: EdgeSource): String = when (src) {
        EdgeSource.MANUAL -> "Manual"
        EdgeSource.AUTO -> "Otomatis"
    }

    private fun dt(label: String, valueHtml: String) {
        w.write("<div><dt>")
        w.write(label)
        w.write("</dt><dd>")
        w.write(valueHtml)
        w.write("</dd></div>")
    }

    // ------------------------------------------------------------------ page frame

    private fun head() {
        w.write("<!DOCTYPE html>\n<html lang=\"id\">\n<head>\n<meta charset=\"utf-8\">\n")
        w.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        w.write("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\">\n")
        w.write("<meta name=\"color-scheme\" content=\"light dark\">\n")
        w.write("<meta name=\"generator\" content=\"UI Mapper\">\n")
        w.write("<title>")
        w.write(h("Peta UI · " + appName() + " · " + s.name))
        w.write("</title>\n<style>")
        w.write(REPORT_CSS)
        w.write("</style>\n</head>\n<body>\n")
    }

    private fun header() {
        val totalClickable = s.screens.sumOf { it.clickableCount }
        val totalNodes = s.screens.sumOf { it.nodeCount }
        val externalCount = s.edges.flatMap { listOf(it.from, it.to) }.filter { isExternalNode(it) }.toSet().size

        w.write("<header class=\"top\"><div class=\"wrap\">\n")
        w.write("<p class=\"eyebrow\">UI Mapper · Laporan peta UI &amp; navigasi</p>\n")
        w.write("<h1>" + h(appName()) + "</h1>\n")
        s.targetPkg?.let { w.write("<p class=\"pkg\"><code>" + h(it) + "</code></p>\n") }
        w.write("<dl class=\"meta\">")
        dt("Sesi", h(s.name))
        dt("Mode", h(modeLabel(s.mode)))
        dt("Perangkat", h(s.device ?: "–"))
        dt("Dibuat", h(date(s.createdAt)))
        dt("Diperbarui", h(date(s.updatedAt)))
        dt("Diekspor", h(date(exportedAt)))
        w.write("</dl>\n<ul class=\"stats\">")
        w.write("<li><b>${s.screens.size}</b> layar</li>")
        w.write("<li><b>${s.edges.size}</b> transisi</li>")
        w.write("<li><b>$totalClickable</b> elemen dapat diketuk</li>")
        w.write("<li><b>$totalNodes</b> node UI</li>")
        if (externalCount > 0) w.write("<li><b>$externalCount</b> aplikasi lain</li>")
        w.write("</ul>\n")
        w.write(
            "<nav class=\"toc\"><a href=\"#peta\">Peta navigasi</a><a href=\"#rute\">Rute dari layar awal</a>" +
                "<a href=\"#transisi\">Semua transisi</a><a href=\"#layar\">Detail layar</a></nav>\n",
        )
        w.write("</div></header>\n")
    }

    private fun footer() {
        w.write("<footer class=\"wrap\">Dibuat oleh UI Mapper pada ")
        w.write(h(date(exportedAt)))
        w.write(
            ". Laporan ini dibuat sepenuhnya di perangkat dan dapat dibuka tanpa internet. " +
                "Isi kolom sandi dan teks yang diketik pengguna tidak disimpan di data elemen, dan kolom isian " +
                "serta keyboard ditutup pada screenshot; teks lain yang tampil di layar tetap bisa terlihat di " +
                "screenshot.</footer>\n</body>\n</html>\n",
        )
    }

    // ------------------------------------------------------------------ map

    private fun mapSection() {
        w.write("<section id=\"peta\">\n<h2>Peta navigasi</h2>\n")
        if (s.screens.isEmpty() && s.edges.isEmpty()) {
            w.write("<p class=\"hint\">Belum ada layar yang direkam pada sesi ini.</p>\n</section>\n")
            return
        }
        w.write(
            "<p class=\"hint\">Kotak = layar, panah = aksi yang terekam. Ketuk kotak untuk membuka detail layar. " +
                "Geser peta ke samping bila lebih lebar dari layar.</p>\n",
        )
        w.write("<input type=\"checkbox\" id=\"fit\" class=\"fit\"><label for=\"fit\">Sesuaikan ke lebar layar</label>\n")
        w.write("<div class=\"map\">")
        NavMap(s, depths, startId).render(w)
        w.write("</div>\n")
        w.write(
            "<ul class=\"legend\">" +
                "<li><span class=\"lg\"></span>Aksi (ketuk, tekan lama, dll.)</li>" +
                "<li><span class=\"lg d\"></span>Kembali / tidak dikenali</li>" +
                "<li><span class=\"lg b h\"></span>Layar awal</li>" +
                "<li><span class=\"lg b u\"></span>Tak terjangkau dari layar awal</li>" +
                "<li><span class=\"lg b x\"></span>Aplikasi lain</li>" +
                "</ul>\n",
        )
        w.write("<details><summary>Sumber diagram Mermaid</summary>")
        w.write("<p class=\"hint\">Salin ke editor Mermaid atau dokumen Markdown yang mendukung Mermaid.</p><pre><code>")
        w.write(h(Exporters.mermaid(s)))
        w.write("</code></pre></details>\n</section>\n")
    }

    // ------------------------------------------------------------------ routes

    private fun routesSection() {
        w.write("<section id=\"rute\">\n<h2>Rute dari layar awal</h2>\n")
        val start = startId?.let { screenById[it] }
        if (start == null) {
            w.write("<p class=\"hint\">Belum ada layar.</p>\n</section>\n")
            return
        }
        w.write("<p class=\"hint\">Layar awal: ")
        w.write(nodeRef(start.id))
        if (s.edges.any { it.from == START_NODE && it.to == start.id }) w.write(" (tampil setelah aplikasi dibuka)")
        w.write(
            ". Tiap rute adalah urutan aksi terpendek yang terekam, tanpa tombol Kembali. Langkah \"Transisi\" " +
                "adalah perpindahan tanpa ketukan yang terekam (mis. splash atau pengalihan otomatis).</p>\n",
        )

        w.write("<ol class=\"routes\">\n")
        for (sc in s.screens) {
            val path = routes[sc.id] ?: continue
            w.write("<li id=\"" + routeAnchor(sc.id) + "\">")
            w.write(nodeRef(sc.id))
            if (path.isEmpty()) {
                w.write(" <span class=\"muted\">· layar awal</span></li>\n")
                continue
            }
            w.write(" <span class=\"muted\">· ${path.size} langkah</span><ol class=\"steps\">")
            for (e in path) {
                w.write("<li>Di ")
                w.write(nodeRef(e.from))
                w.write(": <b>")
                w.write(h(RouteFinder.stepText(e)))
                w.write("</b> → ")
                w.write(nodeRef(e.to))
                w.write("</li>")
            }
            w.write("</ol></li>\n")
        }
        w.write("</ol>\n")

        val unreachable = s.screens.filter { it.id !in routes }
        if (unreachable.isNotEmpty()) {
            w.write("<h3 class=\"sub\">Tidak terjangkau dari layar awal (${unreachable.size})</h3>\n")
            w.write(
                "<p class=\"hint\">Layar ini hanya tercapai lewat Kembali, dari aplikasi lain, " +
                    "atau belum ada transisi terekam yang menuju ke sana.</p>\n<ul class=\"plain\">",
            )
            for (sc in unreachable) {
                w.write("<li>")
                w.write(nodeRef(sc.id))
                w.write("</li>")
            }
            w.write("</ul>\n")
        }
        w.write("</section>\n")
    }

    // ------------------------------------------------------------------ transitions

    private fun transitionsSection() {
        w.write("<section id=\"transisi\">\n<h2>Semua transisi (${s.edges.size})</h2>\n")
        if (s.edges.isEmpty()) {
            w.write("<p class=\"hint\">Belum ada transisi yang terekam.</p>\n</section>\n")
            return
        }
        w.write(
            "<div class=\"tw\"><table><thead><tr><th>ID</th><th>Dari</th><th>Aksi</th><th>Ke</th>" +
                "<th>Elemen</th><th>Jumlah</th><th>Sumber</th><th>Terakhir</th></tr></thead><tbody>\n",
        )
        for (e in s.edges) {
            w.write("<tr id=\"" + edgeAnchor(e.id) + "\"><td class=\"num\">" + h(e.id) + "</td><td>")
            w.write(nodeRef(e.from))
            w.write("</td><td>")
            w.write(h(RouteFinder.stepText(e)))
            w.write("</td><td>")
            w.write(nodeRef(e.to))
            w.write("</td><td>")
            w.write(elementCell(e))
            w.write("</td><td class=\"num\">${e.count}</td><td>")
            w.write(sourceLabel(e.source))
            w.write("</td><td class=\"num\">")
            w.write(h(date(e.lastAt)))
            w.write("</td></tr>\n")
        }
        w.write("</tbody></table></div>\n</section>\n")
    }

    private fun elementCell(e: NavEdge): String {
        val el = e.element ?: return "<span class=\"muted\">–</span>"
        val sb = StringBuilder()
        el.resId?.let { sb.append("<code title=\"").append(h(it)).append("\">").append(h(it.substringAfter(":id/"))).append("</code> ") }
        el.cls?.let { sb.append("<span class=\"muted\">").append(h(it.substringAfterLast('.'))).append("</span>") }
        el.path?.let { sb.append("<br><small class=\"muted\">").append(h(ExportText.clip(it, 140))).append("</small>") }
        if (sb.isEmpty()) sb.append(h(el.display()))
        return sb.toString()
    }

    // ------------------------------------------------------------------ screens

    private fun screensSection() {
        w.write("<section id=\"layar\">\n<h2>Detail layar (${s.screens.size})</h2>\n")
        if (s.screens.isEmpty()) w.write("<p class=\"hint\">Belum ada layar yang direkam.</p>\n")
        for (sc in s.screens) screenCard(sc)
        w.write("</section>\n")
    }

    private fun screenCard(sc: ScreenSummary) {
        val snap = SessionStore.loadScreen(s.id, sc.id)

        w.write("<article class=\"card\" id=\"" + screenAnchor(sc.id) + "\">\n<div class=\"ch\">")
        w.write("<span class=\"sid\">" + h(sc.id) + "</span><h3>" + h(sc.label) + "</h3>")
        if (sc.id == startId) w.write("<span class=\"tag\">Layar awal</span>")
        if (sc.id !in routes) w.write("<span class=\"tag\">Tak terjangkau dari awal</span>")
        if (s.targetPkg != null && sc.pkg != s.targetPkg) w.write("<span class=\"tag\">" + h(sc.pkg) + "</span>")
        w.write("<a class=\"up\" href=\"#peta\">↑ Peta</a></div>\n")

        val sw = snap?.screenW?.takeIf { it > 0 } ?: sc.screenW
        val sh = snap?.screenH?.takeIf { it > 0 } ?: sc.screenH

        w.write("<dl class=\"kv\">")
        dt("Activity", (snap?.activity ?: sc.activity)?.let { "<code>" + h(it) + "</code>" } ?: "–")
        (snap?.title ?: sc.title)?.takeIf { it.isNotBlank() }?.let { dt("Judul", h(it)) }
        if (sw > 0 && sh > 0) dt("Ukuran layar", "$sw × $sh px")
        dt("Node", (if (sc.nodeCount > 0) sc.nodeCount else snap?.nodeCount ?: 0).toString())
        dt("Dapat diketuk", (if (sc.clickableCount > 0) sc.clickableCount else snap?.clickableCount ?: 0).toString())
        dt("Kunjungan", sc.visits.toString())
        dt("Pertama terlihat", h(date(sc.firstSeen)))
        dt("Terakhir terlihat", h(date(sc.lastSeen)))
        val route = routes[sc.id]
        dt(
            "Rute dari awal",
            when {
                route == null -> "tidak ada"
                route.isEmpty() -> "layar awal"
                else -> "<a href=\"#" + routeAnchor(sc.id) + "\">${route.size} langkah</a>"
            },
        )
        w.write("</dl>\n")

        if (snap == null) {
            w.write("<p class=\"hint\">Data pohon UI untuk layar ini tidak ditemukan.</p>\n")
            navLists(sc.id)
            w.write("</article>\n")
            return
        }

        // Edges resolved against this stored tree carry the exact node; only edges without one (raw event
        // info) fall back to the element key, which elements sharing a resource-id also share.
        val outgoingByIdx = HashMap<Int, MutableList<NavEdge>>()
        val outgoingByKey = HashMap<String, MutableList<NavEdge>>()
        for (e in s.outgoing(sc.id)) {
            val el = e.element ?: continue
            val idx = el.nodeIdx
            if (idx != null) {
                outgoingByIdx.getOrPut(idx) { ArrayList() }.add(e)
            } else {
                outgoingByKey.getOrPut(el.key) { ArrayList() }.add(e)
            }
        }
        val all = UiTree.actionable(snap.root)
        val items = all.take(MAX_ELEMENT_ROWS).mapIndexed { i, n ->
            val ref = UiTree.toElementRef(snap.root, n)
            ActionableItem(i + 1, n, ref.label ?: n.simpleCls, ref.key)
        }

        w.write("<div class=\"cb\">")
        shotFigure(sc, snap, items, sw, sh)
        w.write("<div class=\"side\">")
        w.write("<h4>Elemen yang dapat diketuk (${all.size})</h4>")
        if (items.isEmpty()) {
            w.write("<p class=\"hint\">Tidak ada elemen yang dapat diketuk pada layar ini.</p>")
        } else {
            elementsTable(sc.id, items, outgoingByIdx, outgoingByKey)
            if (all.size > items.size) {
                w.write("<p class=\"hint\">Ditampilkan ${items.size} dari ${all.size} elemen. Daftar lengkap ada di ekspor CSV/JSON.</p>")
            }
        }
        navLists(sc.id)
        w.write("</div></div>\n")
        tree(snap, sc)
        w.write("</article>\n")
    }

    private fun elementsTable(
        screenId: String,
        items: List<ActionableItem>,
        outgoingByIdx: Map<Int, List<NavEdge>>,
        outgoingByKey: Map<String, List<NavEdge>>,
    ) {
        w.write(
            "<div class=\"tw\"><table class=\"els\"><thead><tr><th>#</th><th>Label</th><th>resource-id</th>" +
                "<th>Kelas</th><th>Bounds</th><th>Aksi</th><th>Menuju</th></tr></thead><tbody>",
        )
        for (item in items) {
            val n = item.node
            w.write("<tr id=\"" + elementAnchor(screenId, n.idx) + "\"><td class=\"num\">${item.no}</td><td>")
            w.write(h(item.label))
            w.write("</td><td>")
            w.write(n.resId?.let { r -> "<code title=\"" + h(r) + "\">" + h(n.resIdEntry) + "</code>" } ?: "<span class=\"muted\">–</span>")
            w.write("</td><td title=\"" + h(n.cls) + "\">" + h(n.simpleCls) + "</td><td class=\"num\">" + h(n.bounds.toString()) + "</td><td>")
            w.write(actionsOf(n))
            w.write("</td><td>")
            w.write(targetsCell(outgoingByIdx[n.idx].orEmpty() + outgoingByKey[item.key].orEmpty()))
            w.write("</td></tr>")
        }
        w.write("</tbody></table></div>")
    }

    private fun actionsOf(n: UiNode): String {
        val list = ArrayList<String>(3)
        if (n.clickable) list += "ketuk"
        if (n.longClickable) list += "tekan lama"
        if (n.scrollable) list += "gulir"
        if (n.checkable) list += if (n.checked) "dicentang" else "centang"
        return if (list.isEmpty()) "–" else list.joinToString(", ")
    }

    /** Distinct destinations (grouped by target + action, counts summed) of edges fired by one element. */
    private fun targetsCell(edges: List<NavEdge>): String {
        if (edges.isEmpty()) return "<span class=\"muted\">–</span>"
        val grouped = LinkedHashMap<String, Pair<NavEdge, Int>>()
        for (e in edges) {
            val k = e.to + "|" + e.action.name
            val prev = grouped[k]
            grouped[k] = if (prev == null) e to e.count else prev.first to (prev.second + e.count)
        }
        val sb = StringBuilder()
        for ((e, count) in grouped.values) {
            if (sb.isNotEmpty()) sb.append("<br>")
            sb.append(nodeRef(e.to))
            if (e.action != ActionType.CLICK) {
                sb.append(" <span class=\"muted\">(").append(h(RouteFinder.actionVerb(e.action).lowercase(Locale.ROOT))).append(")</span>")
            }
            if (count > 1) sb.append(" <span class=\"muted\">×").append(count).append("</span>")
        }
        return sb.toString()
    }

    private fun navLists(screenId: String) {
        val out = s.outgoing(screenId)
        val inc = s.incoming(screenId)
        w.write("<h4>Navigasi</h4><ul class=\"nav\"><li><b>Keluar (${out.size}):</b> ")
        if (out.isEmpty()) w.write("<span class=\"muted\">belum ada</span>")
        out.forEachIndexed { i, e ->
            if (i > 0) w.write("; ")
            w.write(h(RouteFinder.stepText(e)))
            w.write(" → ")
            w.write(nodeRef(e.to))
            if (e.count > 1) w.write(" <span class=\"muted\">×${e.count}</span>")
        }
        w.write("</li><li><b>Masuk (${inc.size}):</b> ")
        if (inc.isEmpty()) w.write("<span class=\"muted\">belum ada</span>")
        inc.forEachIndexed { i, e ->
            if (i > 0) w.write("; ")
            w.write("dari ")
            w.write(nodeRef(e.from))
            w.write(" (")
            w.write(h(RouteFinder.stepText(e)))
            w.write(")")
        }
        w.write("</li></ul>")
    }

    // ------------------------------------------------------------------ screenshot + outlines

    private fun shotFigure(sc: ScreenSummary, snap: ScreenSnapshot, items: List<ActionableItem>, sw: Int, sh: Int) {
        val shotName = sc.screenshot ?: snap.screenshot
        val hasGeometry = sw > 0 && sh > 0
        // An image whose shape does not match the stored tree (older data) would put every outline in the
        // wrong place: fall back to the wireframe.
        val shot = shotName?.let { File(SessionStore.shotsDir(s.id), it) }?.takeIf { it.isFile }?.let { encodeShot(it) }
            ?.takeIf { !hasGeometry || abs(it.width.toFloat() / it.height - sw.toFloat() / sh) <= 0.02f * sw / sh }

        w.write("<figure class=\"shot\">")
        if (shot == null && !hasGeometry) {
            w.write("<figcaption>Tidak ada screenshot</figcaption></figure>")
            return
        }
        if (shot != null) {
            w.write("<div class=\"frame\"><img src=\"")
            w.write(shot.dataUri)
            w.write("\" width=\"${shot.width}\" height=\"${shot.height}\" alt=\"" + h("Screenshot " + sc.id) + "\">")
        } else {
            w.write("<div class=\"frame wire\" style=\"padding-top:" + pct(sh * 100f / sw) + "%\">")
        }
        if (hasGeometry) for (item in items) outlineBox(sc.id, item, sw, sh)
        w.write("</div><figcaption>")
        w.write(if (shot != null) "Kotak merah = elemen yang dapat diketuk" else "Tanpa screenshot · kerangka posisi elemen")
        w.write("</figcaption></figure>")
    }

    private fun outlineBox(screenId: String, item: ActionableItem, sw: Int, sh: Int) {
        val b = item.node.bounds
        val l = (b.l.toFloat() / sw).coerceIn(0f, 1f)
        val t = (b.t.toFloat() / sh).coerceIn(0f, 1f)
        val r = (b.r.toFloat() / sw).coerceIn(0f, 1f)
        val btm = (b.b.toFloat() / sh).coerceIn(0f, 1f)
        if (r <= l || btm <= t) return
        val title = item.node.resId?.let { item.label + " · " + it } ?: item.label
        w.write("<a class=\"box\" href=\"#" + elementAnchor(screenId, item.node.idx) + "\" title=\"" + h(title) + "\" style=\"left:")
        w.write(pct(l * 100f))
        w.write("%;top:")
        w.write(pct(t * 100f))
        w.write("%;width:")
        w.write(pct((r - l) * 100f))
        w.write("%;height:")
        w.write(pct((btm - t) * 100f))
        w.write("%\"><span>${item.no}</span></a>")
    }

    /** Downscales a stored screenshot to at most [SHOT_MAX_W] px wide, JPEG q[SHOT_QUALITY], as a data URI. */
    private fun encodeShot(file: File): Shot? {
        return try {
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, probe)
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
            var sample = 1
            while (probe.outWidth / (sample * 2) >= SHOT_MAX_W) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scaled = if (decoded.width > SHOT_MAX_W) {
                val hgt = (decoded.height.toLong() * SHOT_MAX_W / decoded.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, SHOT_MAX_W, hgt, true)
            } else {
                decoded
            }
            val bos = ByteArrayOutputStream(64 * 1024)
            val ok = scaled.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, bos)
            val width = scaled.width
            val height = scaled.height
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            if (!ok) return null
            Shot("data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), width, height)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // ------------------------------------------------------------------ hierarchy

    private fun tree(snap: ScreenSnapshot, sc: ScreenSummary) {
        val total = when {
            snap.nodeCount > 0 -> snap.nodeCount
            sc.nodeCount > 0 -> sc.nodeCount
            else -> UiTree.countNodes(snap.root)
        }
        w.write("<details class=\"tree\"><summary>Hierarki lengkap ($total node)</summary><div class=\"kids\">")
        val st = TreeState(treeBudget)
        treeNode(snap.root, 0, st)
        w.write("</div>")
        if (st.truncated) {
            w.write(
                "<p class=\"hint\">Hierarki dipotong: ditampilkan ${st.emitted} dari $total node " +
                    "(maks. $treeBudget node, kedalaman $TREE_MAX_DEPTH). Data lengkap ada di ekspor JSON/CSV.</p>",
            )
        }
        w.write("</details>\n")
    }

    private fun treeNode(n: UiNode, level: Int, st: TreeState) {
        if (st.emitted >= st.budget) {
            st.truncated = true
            return
        }
        st.emitted++
        if (n.children.isEmpty()) {
            w.write("<div class=\"lf\">")
            w.write(nodeLine(n))
            w.write("</div>")
            return
        }
        if (level >= TREE_MAX_DEPTH) {
            w.write("<div class=\"lf\">")
            w.write(nodeLine(n))
            w.write(" <span class=\"cnt\">(+${UiTree.countNodes(n) - 1} node lebih dalam)</span></div>")
            st.truncated = true
            return
        }
        w.write(if (level < 2) "<details open><summary>" else "<details><summary>")
        w.write(nodeLine(n))
        w.write(" <span class=\"cnt\">${n.children.size} anak</span></summary><div class=\"kids\">")
        for (c in n.children) {
            if (st.emitted >= st.budget) {
                st.truncated = true
                break
            }
            treeNode(c, level + 1, st)
        }
        w.write("</div></details>")
    }

    private fun nodeLine(n: UiNode): String {
        val sb = StringBuilder(192)
        sb.append("<span class=\"ix\">").append(n.idx).append("</span> ")
        sb.append("<code class=\"c\" title=\"").append(h(n.cls)).append("\">").append(h(n.simpleCls)).append("</code>")
        n.resIdEntry?.let {
            sb.append(" <span class=\"rid\" title=\"").append(h(n.resId)).append("\">#").append(h(it)).append("</span>")
        }
        ExportText.safeText(n)?.takeIf { it.isNotBlank() }?.let {
            sb.append(" <q>").append(h(ExportText.clip(it, 80))).append("</q>")
        }
        n.desc?.takeIf { it.isNotBlank() }?.let {
            sb.append(" <span class=\"ds\" title=\"content-desc\">").append(h(ExportText.clip(it, 80))).append("</span>")
        }
        n.hint?.takeIf { it.isNotBlank() }?.let {
            sb.append(" <span class=\"ds\">petunjuk: ").append(h(ExportText.clip(it, 60))).append("</span>")
        }
        n.paneTitle?.takeIf { it.isNotBlank() }?.let {
            sb.append(" <span class=\"ds\">panel: ").append(h(ExportText.clip(it, 60))).append("</span>")
        }
        sb.append(" <span class=\"bd\">").append(h(n.bounds.toString())).append("</span>")
        for (f in flagsOf(n)) sb.append(" <span class=\"fl\">").append(f).append("</span>")
        return sb.toString()
    }

    private fun flagsOf(n: UiNode): List<String> {
        val out = ArrayList<String>(4)
        if (n.clickable) out += "ketuk"
        if (n.longClickable) out += "tekan lama"
        if (n.scrollable) out += "gulir"
        if (n.editable) out += "input"
        if (n.password) out += "sandi"
        if (n.checkable) out += if (n.checked) "dicentang" else "centang"
        if (n.selected) out += "terpilih"
        if (n.heading) out += "judul"
        if (!n.enabled) out += "nonaktif"
        if (!n.visible) out += "tersembunyi"
        return out
    }
}

/**
 * Layered navigation map as inline SVG: START on top, screens by BFS depth from the start screen,
 * screens unreachable from it in a final layer, external apps one layer below their source. Layers
 * wider than [MAX_PER_ROW] wrap; nodes are ordered by the barycenter of their placed predecessors.
 */
private class NavMap(
    private val s: Session,
    private val depths: Map<String, Int>,
    private val startId: String?,
) {
    private class Node(
        val id: String,
        val kind: Int,
        val line1: String,
        val line2: String,
        val tip: String,
        val unreached: Boolean,
        val home: Boolean,
    ) {
        var layer = 0
        var row = 0
        var cx = 0f
        var cy = 0f
        var placed = false
    }

    private class Curve(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x3: Float, val y3: Float,
    ) {
        val midX: Float get() = (x0 + 3f * x1 + 3f * x2 + x3) / 8f
        val midY: Float get() = (y0 + 3f * y1 + 3f * y2 + y3) / 8f

        fun d(): String =
            "M${r(x0)} ${r(y0)} C${r(x1)} ${r(y1)}, ${r(x2)} ${r(y2)}, ${r(x3)} ${r(y3)}"

        private fun r(v: Float): Int = v.roundToInt()
    }

    fun render(w: Writer) {
        val nodes = buildNodes()
        if (nodes.isEmpty()) return
        val (width, height) = layout(nodes)

        w.write("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\" width=\"$width\" height=\"$height\" ")
        w.write("role=\"img\" aria-labelledby=\"map-title\"><title id=\"map-title\">")
        w.write(h("Peta navigasi: ${s.screens.size} layar, ${s.edges.size} transisi"))
        w.write("</title>")
        w.write(
            "<defs><marker id=\"ah\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" " +
                "orient=\"auto\"><path class=\"ahf\" d=\"M0,0 L10,5 L0,10 z\"/></marker></defs>",
        )

        // Pass 1: edges (under the nodes). Pass 3: labels (on top of everything).
        val labels = StringBuilder()
        val pairTotals = s.edges.groupingBy { pairKey(it) }.eachCount()
        val pairSeen = HashMap<String, Int>()
        w.write("<g class=\"edges\">")
        for (e in s.edges) {
            val a = nodes[e.from] ?: continue
            val b = nodes[e.to] ?: continue
            val key = pairKey(e)
            val k = pairSeen[key] ?: 0
            pairSeen[key] = k + 1
            val total = pairTotals[key] ?: 1
            val off = (k - (total - 1) / 2f) * PAIR_SPREAD
            val c = curve(a, b, off)
            w.write("<path class=\"")
            w.write(if (ExportText.isDashed(e)) "e dash" else "e")
            w.write("\" d=\"")
            w.write(c.d())
            w.write("\" marker-end=\"url(#ah)\"><title>")
            w.write(h(RouteFinder.describe(s, e) + if (e.count > 1) " ×${e.count}" else ""))
            w.write("</title></path>")

            val selfLoop = a === b
            labels.append("<text class=\"el\" x=\"").append(c.midX.roundToInt() + if (selfLoop) 4 else 0)
                .append("\" y=\"").append((c.midY + 3f).roundToInt())
                .append("\" text-anchor=\"").append(if (selfLoop) "start" else "middle").append("\">")
                .append(h(ExportText.edgeLabel(e, MAP_EDGE_CHARS)))
                .append("</text>")
        }
        w.write("</g>")

        // Pass 2: nodes.
        w.write("<g class=\"nodes\">")
        for (n in nodes.values) writeNode(w, n)
        w.write("</g>")

        w.write("<g class=\"labels\">")
        w.write(labels.toString())
        w.write("</g></svg>")
    }

    private fun buildNodes(): LinkedHashMap<String, Node> {
        val nodes = LinkedHashMap<String, Node>()
        val referenced = LinkedHashSet<String>()
        for (e in s.edges) {
            referenced += e.from
            referenced += e.to
        }
        val hasStart = START_NODE in referenced
        var maxLayer = -1
        if (hasStart) {
            nodes[START_NODE] = Node(START_NODE, K_START, "Mulai", "", "Mulai · aplikasi dibuka", false, false)
            maxLayer = 0
        }
        val base = if (hasStart) 1 else 0

        for (sc in s.screens) {
            val d = depths[sc.id]
            val act = sc.activity?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
            val tip = sc.id + " · " + sc.label + (act?.let { " ($it)" } ?: "")
            val n = Node(sc.id, K_SCREEN, sc.id, ExportText.clip(sc.label, MAP_LABEL_CHARS), tip, d == null, sc.id == startId)
            if (d != null) {
                n.layer = base + d
                maxLayer = maxOf(maxLayer, n.layer)
            }
            nodes[sc.id] = n
        }

        val deferred = ArrayList<Node>()
        for (id in referenced) {
            if (id in nodes) continue
            if (isExternalNode(id)) {
                val pkg = id.removePrefix(EXTERNAL_PREFIX)
                val n = Node(id, K_EXTERNAL, "Aplikasi lain", ExportText.clip(pkg, MAP_LABEL_CHARS), "Aplikasi lain: $pkg", false, false)
                var srcLayer: Int? = null
                for (e in s.edges) {
                    if (e.to != id || e.from == id) continue
                    val src = nodes[e.from] ?: continue
                    val placedSource = src.kind == K_START || (src.kind == K_SCREEN && !src.unreached)
                    if (placedSource) srcLayer = minOf(srcLayer ?: Int.MAX_VALUE, src.layer)
                }
                if (srcLayer != null) {
                    n.layer = srcLayer + 1
                    maxLayer = maxOf(maxLayer, n.layer)
                } else {
                    deferred += n
                }
                nodes[id] = n
            } else if (id != START_NODE) {
                val n = Node(id, K_OTHER, id, "", id, true, false)
                deferred += n
                nodes[id] = n
            }
        }

        val finalLayer = maxLayer + 1
        for (n in nodes.values) if (n.kind == K_SCREEN && n.unreached) n.layer = finalLayer
        for (n in deferred) {
            n.layer = if (n.kind == K_EXTERNAL && s.edges.any { it.to == n.id }) finalLayer + 1 else finalLayer
        }
        return nodes
    }

    /** Assigns rows and centers; returns the canvas size. */
    private fun layout(nodes: Map<String, Node>): Pair<Int, Int> {
        val layers = nodes.values.groupBy { it.layer }.toSortedMap()
        val cols = layers.values.maxOf { minOf(it.size, MAX_PER_ROW) }
        val innerW = cols * NODE_W + (cols - 1) * GAP_X

        val preds = HashMap<String, MutableList<String>>()
        for (e in s.edges) if (e.from != e.to) preds.getOrPut(e.to) { ArrayList() }.add(e.from)

        var row = 0
        for (list in layers.values) {
            val ordered = list
                .mapIndexed { i, n -> Triple(n, barycenter(n, preds, nodes), i) }
                .sortedWith(compareBy<Triple<Node, Float, Int>>({ it.second }, { it.third }))
                .map { it.first }
            for (chunk in ordered.chunked(MAX_PER_ROW)) {
                val rowW = chunk.size * NODE_W + (chunk.size - 1) * GAP_X
                var cx = PAD_L + (innerW - rowW) / 2f + NODE_W / 2f
                val cy = PAD_T + row * (NODE_H + GAP_Y) + NODE_H / 2f
                for (n in chunk) {
                    n.row = row
                    n.cx = cx
                    n.cy = cy
                    n.placed = true
                    cx += NODE_W + GAP_X
                }
                row++
            }
        }
        val width = PAD_L + innerW + PAD_R
        val height = PAD_T + row * NODE_H + (row - 1).coerceAtLeast(0) * GAP_Y + PAD_B
        return width to height
    }

    private fun barycenter(n: Node, preds: Map<String, List<String>>, nodes: Map<String, Node>): Float {
        var sum = 0f
        var count = 0
        for (p in preds[n.id].orEmpty()) {
            val pn = nodes[p] ?: continue
            if (pn.placed) {
                sum += pn.cx
                count++
            }
        }
        return if (count == 0) Float.MAX_VALUE else sum / count
    }

    private fun pairKey(e: NavEdge): String =
        if (e.from <= e.to) e.from + "\u0000" + e.to else e.to + "\u0000" + e.from

    private fun curve(a: Node, b: Node, off: Float): Curve {
        val hw = NODE_W / 2f
        val hh = NODE_H / 2f
        return when {
            a === b -> {
                val x = a.cx + hw
                Curve(x, a.cy - 10f, x + 46f, a.cy - 34f, x + 46f, a.cy + 34f, x, a.cy + 10f)
            }
            b.row > a.row -> {
                // Downwards: bottom of source -> top of target.
                val x0 = a.cx + off
                val y0 = a.cy + hh
                val x3 = b.cx + off
                val y3 = b.cy - hh
                val dy = (y3 - y0) * 0.5f
                Curve(x0, y0, x0, y0 + dy, x3, y3 - dy, x3, y3)
            }
            b.row < a.row -> {
                // Upwards (typically "back"): leave and enter on the right side, bulging outwards.
                val x0 = a.cx + hw
                val y0 = a.cy + off * 0.4f
                val x3 = b.cx + hw
                val y3 = b.cy + off * 0.4f
                val bulge = (28f + (a.row - b.row) * 14f + abs(off)).coerceAtMost(PAD_R - 12f)
                val xc = maxOf(x0, x3) + bulge
                Curve(x0, y0, xc, y0, xc, y3, x3, y3)
            }
            else -> {
                // Same row: arc above when going right, below when going left.
                val lift = (18f + abs(b.cx - a.cx) * 0.08f + abs(off)).coerceAtMost(GAP_Y * 0.8f)
                if (b.cx > a.cx) {
                    val y = a.cy - hh
                    Curve(a.cx + off, y, a.cx + off, y - lift, b.cx + off, y - lift, b.cx + off, y)
                } else {
                    val y = a.cy + hh
                    Curve(a.cx + off, y, a.cx + off, y + lift, b.cx + off, y + lift, b.cx + off, y)
                }
            }
        }
    }

    private fun writeNode(w: Writer, n: Node) {
        val x = (n.cx - NODE_W / 2f).roundToInt()
        val y = (n.cy - NODE_H / 2f).roundToInt()
        val cx = n.cx.roundToInt()
        val cy = n.cy.roundToInt()
        when (n.kind) {
            K_START -> {
                w.write("<g class=\"st\"><title>" + h(n.tip) + "</title>")
                w.write("<circle cx=\"$cx\" cy=\"$cy\" r=\"${NODE_H / 2}\"/>")
                w.write("<text x=\"$cx\" y=\"${cy + 4}\" text-anchor=\"middle\">Mulai</text></g>")
            }
            K_SCREEN -> {
                val cls = buildString {
                    append("n")
                    if (n.home) append(" home")
                    if (n.unreached) append(" unr")
                }
                w.write("<a href=\"#" + screenAnchor(n.id) + "\"><g class=\"$cls\"><title>" + h(n.tip) + "</title>")
                w.write("<rect class=\"nb\" x=\"$x\" y=\"$y\" width=\"$NODE_W\" height=\"$NODE_H\" rx=\"10\"/>")
                writeLines(w, n, cx, cy)
                w.write("</g></a>")
            }
            K_EXTERNAL -> {
                w.write("<g class=\"n ext\"><title>" + h(n.tip) + "</title>")
                val sk = 12
                w.write(
                    "<polygon class=\"nb\" points=\"${x + sk},$y ${x + NODE_W},$y ${x + NODE_W - sk},${y + NODE_H} $x,${y + NODE_H}\"/>",
                )
                writeLines(w, n, cx, cy)
                w.write("</g>")
            }
            else -> {
                w.write("<g class=\"n unr\"><title>" + h(n.tip) + "</title>")
                w.write("<rect class=\"nb\" x=\"$x\" y=\"$y\" width=\"$NODE_W\" height=\"$NODE_H\" rx=\"10\"/>")
                writeLines(w, n, cx, cy)
                w.write("</g>")
            }
        }
    }

    private fun writeLines(w: Writer, n: Node, cx: Int, cy: Int) {
        if (n.line2.isEmpty()) {
            w.write("<text class=\"t1\" x=\"$cx\" y=\"${cy + 4}\" text-anchor=\"middle\">" + h(n.line1) + "</text>")
        } else {
            w.write("<text class=\"t1\" x=\"$cx\" y=\"${cy - 5}\" text-anchor=\"middle\">" + h(n.line1) + "</text>")
            w.write("<text class=\"t2\" x=\"$cx\" y=\"${cy + 12}\" text-anchor=\"middle\">" + h(n.line2) + "</text>")
        }
    }

    private companion object {
        const val K_START = 0
        const val K_SCREEN = 1
        const val K_EXTERNAL = 2
        const val K_OTHER = 3

        const val NODE_W = 168
        const val NODE_H = 50
        const val GAP_X = 36
        const val GAP_Y = 78
        const val PAD_L = 24
        const val PAD_R = 104
        const val PAD_T = 36
        const val PAD_B = 60
        const val MAX_PER_ROW = 6
        const val PAIR_SPREAD = 16f
    }
}

private const val REPORT_CSS = """
:root{--bg:#f5f7fa;--fg:#18202b;--muted:#5b6778;--card:#ffffff;--line:#dde3ea;--accent:#1f6feb;--node:#eef4fb;--ns:#7ea6d8;--ext:#fff4e5;--es:#c77d00;--edge:#6a7b91;--hl:#e5484d;--hlbg:rgba(229,72,77,.10);--hlbg2:rgba(229,72,77,.30);--code:#eef1f5;--ok:#1a7f37;color-scheme:light dark}
@media (prefers-color-scheme:dark){:root{--bg:#0e1318;--fg:#e2e8f0;--muted:#98a4b5;--card:#161c24;--line:#29323e;--accent:#5ea2ff;--node:#1a2736;--ns:#406a9c;--ext:#33260f;--es:#e0a53a;--edge:#8795a8;--hl:#ff6b70;--hlbg:rgba(255,107,112,.14);--hlbg2:rgba(255,107,112,.34);--code:#1e2530;--ok:#56d364}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;overflow-wrap:anywhere}
a{color:var(--accent);text-decoration:none}
a:hover{text-decoration:underline}
code{font:12.5px/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:var(--code);padding:1px 4px;border-radius:4px}
.wrap{max-width:1120px;margin:0 auto;padding:0 16px}
.top{background:var(--card);border-bottom:1px solid var(--line);padding:20px 0 14px}
.eyebrow{margin:0;color:var(--muted);font-size:12px;letter-spacing:.06em;text-transform:uppercase}
h1{margin:4px 0 2px;font-size:26px;line-height:1.2}
h2{font-size:20px;margin:32px 0 8px}
h3{font-size:17px;margin:0}
h3.sub{font-size:15px;margin:18px 0 4px}
h4{font-size:12.5px;margin:14px 0 6px;color:var(--muted);text-transform:uppercase;letter-spacing:.04em}
.pkg{margin:0}
.meta{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:6px 18px;margin:14px 0}
.meta div,.kv div{min-width:0}
.meta dt,.kv dt{font-size:12px;color:var(--muted)}
.meta dd,.kv dd{margin:0}
.stats{display:flex;flex-wrap:wrap;gap:8px;list-style:none;padding:0;margin:10px 0}
.stats li{background:var(--code);border-radius:999px;padding:3px 12px;font-size:13px}
.toc{display:flex;flex-wrap:wrap;gap:4px 18px;font-size:14px;margin-top:6px}
.muted{color:var(--muted)}
.hint{color:var(--muted);font-size:13.5px;margin:4px 0 10px}
section{scroll-margin-top:12px}
.map{overflow:auto;border:1px solid var(--line);border-radius:12px;background:var(--card);margin-top:8px;-webkit-overflow-scrolling:touch}
.map svg{display:block;max-width:none;height:auto}
.fit{margin:0 6px 0 0;vertical-align:middle}
.fit+label{font-size:14px;vertical-align:middle}
.fit:checked~.map svg{width:100%}
.map text{fill:var(--fg);font-size:12px;font-family:inherit}
.map .nb{fill:var(--node);stroke:var(--ns);stroke-width:1.4}
.map .home .nb{stroke:var(--accent);stroke-width:2.6}
.map .unr .nb{stroke-dasharray:5 4}
.map .ext .nb{fill:var(--ext);stroke:var(--es);stroke-dasharray:4 3}
.map .t1{font-size:11px;font-weight:700;fill:var(--accent)}
.map .ext .t1{fill:var(--es)}
.map .st circle{fill:var(--accent)}
.map .st text{fill:#ffffff;font-weight:600}
.map a:hover .nb,.map a:focus .nb{stroke:var(--accent);stroke-width:2.6}
.map .e{fill:none;stroke:var(--edge);stroke-width:1.4}
.map .e.dash{stroke-dasharray:5 4}
.map .ahf{fill:var(--edge)}
.map .el{font-size:10px;fill:var(--muted);paint-order:stroke;stroke:var(--card);stroke-width:3px;stroke-linejoin:round}
.legend{display:flex;flex-wrap:wrap;gap:6px 18px;list-style:none;padding:0;margin:10px 0;font-size:13px;color:var(--muted)}
.lg{display:inline-block;width:26px;height:0;border-top:2px solid var(--edge);vertical-align:middle;margin-right:6px}
.lg.d{border-top-style:dashed}
.lg.b{width:18px;height:12px;border:1.5px solid var(--ns);border-radius:3px;background:var(--node)}
.lg.h{border:2.5px solid var(--accent)}
.lg.u{border-style:dashed}
.lg.x{background:var(--ext);border:1.5px dashed var(--es);border-radius:1px;transform:skewX(-15deg)}
details{margin:8px 0}
summary{cursor:pointer}
pre{background:var(--code);border-radius:8px;padding:12px;overflow:auto;font-size:12.5px;max-height:420px}
pre code{background:none;padding:0}
.tw{overflow-x:auto;border:1px solid var(--line);border-radius:10px;background:var(--card)}
table{border-collapse:collapse;width:100%;font-size:13.5px}
th,td{text-align:left;vertical-align:top;padding:6px 10px;border-bottom:1px solid var(--line)}
th{font-size:12px;color:var(--muted);font-weight:600;background:var(--code);white-space:nowrap}
tr:last-child td{border-bottom:0}
td.num{font-variant-numeric:tabular-nums;white-space:nowrap}
tr:target{background:var(--hlbg)}
.routes>li{margin:8px 0}
.steps{margin:4px 0 0;padding-left:22px;font-size:14px}
.plain{padding-left:20px}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin:16px 0;scroll-margin-top:12px}
.card:target{outline:2px solid var(--accent)}
.ch{display:flex;flex-wrap:wrap;align-items:center;gap:8px}
.sid{background:var(--accent);color:#ffffff;border-radius:6px;padding:1px 8px;font-weight:700;font-size:13px}
.tag{font-size:12px;border:1px solid var(--line);border-radius:999px;padding:0 8px;color:var(--muted)}
.up{margin-left:auto;font-size:13px}
.kv{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:4px 16px;margin:10px 0;font-size:13.5px}
.cb{display:grid;grid-template-columns:minmax(0,300px) minmax(0,1fr);gap:18px;align-items:start}
.shot{margin:0;width:100%}
.frame{position:relative;border:1px solid var(--line);border-radius:10px;overflow:hidden;background:var(--code)}
.frame img{display:block;width:100%;height:auto}
.box{position:absolute;display:block;border:1.5px solid var(--hl);border-radius:3px;background:var(--hlbg)}
.box span{position:absolute;left:0;top:0;background:var(--hl);color:#ffffff;font-size:9px;line-height:1;padding:1px 2px;border-radius:0 0 3px 0}
.box:hover,.box:focus{background:var(--hlbg2);z-index:2;text-decoration:none}
figcaption{font-size:12px;color:var(--muted);margin-top:4px;text-align:center}
.nav{padding-left:18px;margin:4px 0;font-size:14px}
.nav li{margin:4px 0}
.tree{font-size:13px;margin-top:14px}
.tree .kids{padding-left:12px;border-left:1px dashed var(--line);margin-left:5px}
.tree .lf{padding:1px 0 1px 16px}
.tree summary{padding:1px 0}
.tree .c{color:var(--accent);background:none;padding:0}
.tree .ix{color:var(--muted);font-size:11px;font-variant-numeric:tabular-nums}
.rid{color:var(--ok)}
.ds{font-style:italic;color:var(--muted)}
.bd{color:var(--muted);font-size:11.5px}
.fl{display:inline-block;font-size:10.5px;border:1px solid var(--line);border-radius:4px;padding:0 4px;color:var(--muted)}
.cnt{font-size:11px;color:var(--muted)}
footer{color:var(--muted);font-size:12.5px;padding:24px 16px 40px}
@media (max-width:760px){.cb{grid-template-columns:minmax(0,1fr)}.shot{max-width:340px;margin:0 auto}h1{font-size:22px}}
"""
