package app.uimapper.export

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.uimapper.core.RouteFinder
import app.uimapper.core.UiTree
import app.uimapper.data.SessionStore
import app.uimapper.model.ActionType
import app.uimapper.model.EXTERNAL_PREFIX
import app.uimapper.model.NavEdge
import app.uimapper.model.START_NODE
import app.uimapper.model.ScreenSnapshot
import app.uimapper.model.Session
import app.uimapper.model.UiNode
import app.uimapper.model.isExternalNode
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val title: String, val mime: String, val ext: String) {
    JSON("JSON lengkap", "application/json", "json"),
    MERMAID("Diagram Mermaid", "text/plain", "mmd"),
    DOT("Graphviz DOT", "text/vnd.graphviz", "dot"),
    CSV("CSV elemen", "text/csv", "csv"),
    HTML("Laporan HTML", "text/html", "html"),
    ZIP("Paket ZIP (semua + screenshot)", "application/zip", "zip"),
}

/**
 * Turns a recorded session into shareable files. Everything happens on the device; nothing is sent
 * anywhere unless the user picks a target in the share sheet.
 */
object Exporters {

    private const val EXPORT_DIR = "exports"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val DOWNLOAD_SUBDIR = "UiMapper"
    private const val BUF = 64 * 1024
    private const val BOM = "\uFEFF"
    private const val CRLF = "\r\n"

    private val ELEMENT_COLUMNS = listOf(
        "screen_id", "screen_label", "activity", "idx", "depth", "class", "resource_id", "text",
        "content_desc", "hint", "clickable", "long_clickable", "scrollable", "editable", "checkable",
        "checked", "enabled", "visible", "bounds", "xpath", "label",
    )

    private val ROUTE_COLUMNS = listOf(
        "edge_id", "from_id", "from_label", "action", "element_label", "element_resource_id",
        "element_xpath", "to_id", "to_label", "count", "source",
    )

    /**
     * Writes [format] for [sessionId] into `cacheDir/exports/` and returns the file. Blocking: call on
     * Dispatchers.IO. Export files older than 24 hours are removed first.
     *
     * @throws IllegalArgumentException("Sesi tidak ditemukan") when the session does not exist.
     * @throws IOException when writing fails (no partial file is left behind).
     */
    fun export(context: Context, sessionId: String, format: ExportFormat): File {
        SessionStore.init(context.applicationContext ?: context)
        val session = SessionStore.get(sessionId) ?: throw IllegalArgumentException("Sesi tidak ditemukan")

        val dir = File(context.cacheDir, EXPORT_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Folder ekspor tidak dapat dibuat")
        purgeOld(dir)

        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(now))
        val target = File(dir, "${fileBase(session)}_$stamp.${format.ext}")
        val tmp = File.createTempFile("export_", ".part", dir)
        try {
            when (format) {
                ExportFormat.JSON ->
                    BufferedOutputStream(FileOutputStream(tmp), BUF).use { BundleWriter.write(session, now, it) }
                ExportFormat.MERMAID -> writeText(tmp) { it.write(mermaid(session)) }
                ExportFormat.DOT -> writeText(tmp) { it.write(dot(session)) }
                ExportFormat.CSV -> writeText(tmp) { writeElementsCsv(session, it) }
                ExportFormat.HTML -> writeText(tmp) { HtmlReport.write(session, now, it) }
                ExportFormat.ZIP -> writeZip(session, now, tmp)
            }
            if (target.exists() && !target.delete()) throw IOException("File ekspor lama tidak dapat diganti")
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return target
    }

    /**
     * Opens the system share sheet for [file] (which must live in `cacheDir/exports/`).
     *
     * @throws IllegalArgumentException when [file] is outside the FileProvider paths.
     */
    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Bagikan ekspor")
        val activity = context.findActivity()
        if (activity == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (activity ?: context).startActivity(chooser)
    }

    /**
     * Copies [file] into Downloads/UiMapper via MediaStore (Android 10+). Returns the new item's Uri,
     * or null below API 29 or on any failure. Blocking: call on Dispatchers.IO.
     */
    fun saveToDownloads(context: Context, file: File, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, downloadMime(file, mime))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Tidak dapat menulis ke Unduhan")
                out.use { o -> file.inputStream().use { it.copyTo(o, BUF) } }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Mermaid "flowchart TD" source of the navigation map. */
    fun mermaid(session: Session): String {
        val sb = StringBuilder(512 + (session.screens.size + session.edges.size) * 96)
        sb.append("flowchart TD\n")
        if (session.screens.isEmpty() && session.edges.isEmpty()) {
            sb.append("    kosong[\"Belum ada layar yang direkam\"]\n")
            return sb.toString()
        }
        val ids = MermaidIds()
        val screenIds = session.screens.mapTo(HashSet()) { it.id }
        val referenced = LinkedHashSet<String>()
        for (e in session.edges) {
            referenced += e.from
            referenced += e.to
        }
        val reachable = RouteFinder.depths(session).keys
        val hasStart = START_NODE in referenced

        if (hasStart) sb.append("    ").append(ids.of(START_NODE)).append("((Mulai))\n")

        val unreached = ArrayList<String>()
        for (s in session.screens) {
            val id = ids.of(s.id)
            sb.append("    ").append(id).append("[\"")
                .append(mmd(s.id)).append(" · ").append(mmd(ExportText.clip(s.label, 48)))
            val act = s.activity?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
            if (act != null) sb.append("<br/><small>").append(mmd(ExportText.clip(act, 48))).append("</small>")
            sb.append("\"]\n")
            if (s.id !in reachable) unreached += id
        }

        val externals = referenced.filter { isExternalNode(it) }
        for (x in externals) {
            sb.append("    ").append(ids.of(x)).append("[/\"Aplikasi lain: ")
                .append(mmd(x.removePrefix(EXTERNAL_PREFIX))).append("\"/]\n")
        }
        val unknown = referenced.filter { it != START_NODE && !isExternalNode(it) && it !in screenIds }
        for (u in unknown) sb.append("    ").append(ids.of(u)).append("[\"").append(mmd(u)).append("\"]\n")

        if (session.edges.isNotEmpty()) sb.append('\n')
        for (e in session.edges) {
            sb.append("    ").append(ids.of(e.from))
                .append(if (ExportText.isDashed(e)) " -.->|\"" else " -->|\"")
                .append(mmd(ExportText.edgeLabel(e, 60)))
                .append("\"| ").append(ids.of(e.to)).append('\n')
        }

        sb.append('\n')
        sb.append("    classDef startNode fill:#1f6feb,stroke:#1f6feb,color:#ffffff\n")
        sb.append("    classDef extNode fill:#fff4e5,stroke:#c77d00,color:#4a3000,stroke-dasharray: 4 3\n")
        sb.append("    classDef unreached stroke-dasharray: 5 4\n")
        if (hasStart) sb.append("    class ").append(ids.of(START_NODE)).append(" startNode\n")
        if (externals.isNotEmpty()) {
            sb.append("    class ").append(externals.joinToString(",") { ids.of(it) }).append(" extNode\n")
        }
        if (unreached.isNotEmpty()) sb.append("    class ").append(unreached.joinToString(",")).append(" unreached\n")
        return sb.toString()
    }

    /** Graphviz DOT source of the navigation map (render with `dot -Tsvg graph.dot -o graph.svg`). */
    fun dot(session: Session): String {
        val sb = StringBuilder(512 + (session.screens.size + session.edges.size) * 96)
        sb.append("digraph UiMap {\n")
        sb.append("    rankdir=TB;\n")
        val title = listOfNotNull(
            session.appLabel?.takeIf { it.isNotBlank() } ?: session.targetPkg,
            session.name.takeIf { it.isNotBlank() },
        ).joinToString(" — ")
        if (title.isNotEmpty()) {
            sb.append("    graph [label=\"").append(dotEsc(title))
                .append("\", labelloc=t, fontsize=16, fontname=\"Helvetica\"];\n")
        }
        sb.append("    node [shape=box, style=\"rounded,filled\", fillcolor=\"#eef4fb\", fontname=\"Helvetica\"];\n")
        sb.append("    edge [fontname=\"Helvetica\", fontsize=10, color=\"#5b6b80\", fontcolor=\"#394656\"];\n")

        val screenIds = session.screens.mapTo(HashSet()) { it.id }
        val referenced = LinkedHashSet<String>()
        for (e in session.edges) {
            referenced += e.from
            referenced += e.to
        }
        val reachable = RouteFinder.depths(session).keys
        val startId = RouteFinder.startScreenId(session)

        if (START_NODE in referenced) {
            sb.append("    \"").append(dotEsc(START_NODE))
                .append("\" [label=\"Mulai\", shape=circle, style=filled, fillcolor=\"#1f6feb\", fontcolor=\"white\"];\n")
        }
        for (s in session.screens) {
            sb.append("    \"").append(dotEsc(s.id)).append("\" [label=\"")
                .append(dotEsc(s.id + " · " + ExportText.clip(s.label, 48)))
            val act = s.activity?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
            if (act != null) sb.append("\\n").append(dotEsc(ExportText.clip(act, 48)))
            sb.append('"')
            if (s.id == startId) sb.append(", penwidth=2, color=\"#1f6feb\"")
            if (s.id !in reachable) sb.append(", style=\"rounded,filled,dashed\"")
            sb.append("];\n")
        }
        for (x in referenced) {
            if (isExternalNode(x)) {
                sb.append("    \"").append(dotEsc(x)).append("\" [label=\"Aplikasi lain\\n")
                    .append(dotEsc(x.removePrefix(EXTERNAL_PREFIX)))
                    .append("\", shape=parallelogram, style=\"filled,dashed\", fillcolor=\"#fff4e5\", color=\"#c77d00\"];\n")
            } else if (x != START_NODE && x !in screenIds) {
                sb.append("    \"").append(dotEsc(x)).append("\" [label=\"").append(dotEsc(x))
                    .append("\", style=\"rounded,dashed\"];\n")
            }
        }
        for (e in session.edges) {
            sb.append("    \"").append(dotEsc(e.from)).append("\" -> \"").append(dotEsc(e.to))
                .append("\" [label=\"").append(dotEsc(ExportText.edgeLabel(e, 60))).append('"')
            if (ExportText.isDashed(e)) sb.append(", style=dashed")
            sb.append("];\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    // ---------------------------------------------------------------- CSV

    /** One row per captured node of every screen (RFC 4180, UTF-8 with BOM, CRLF). */
    internal fun writeElementsCsv(session: Session, w: Writer) {
        w.write(BOM)
        val csv = CsvWriter(w)
        for (c in ELEMENT_COLUMNS) csv.field(c)
        csv.end()
        for (summary in session.screens) {
            val snap = SessionStore.loadScreen(session.id, summary.id) ?: continue
            val activity = snap.activity ?: summary.activity
            ExportText.walkWithXpath(snap.root) { n, xpath ->
                csv.text(summary.id)
                csv.text(summary.label)
                csv.text(activity)
                csv.int(n.idx)
                csv.int(n.depth)
                csv.text(n.cls)
                csv.text(n.resId)
                csv.text(ExportText.safeText(n))
                csv.text(n.desc)
                csv.text(n.hint)
                csv.bool(n.clickable)
                csv.bool(n.longClickable)
                csv.bool(n.scrollable)
                csv.bool(n.editable)
                csv.bool(n.checkable)
                csv.bool(n.checked)
                csv.bool(n.enabled)
                csv.bool(n.visible)
                csv.field(n.bounds.toString())
                csv.text(xpath)
                csv.text(UiTree.labelOf(n))
                csv.end()
            }
        }
        w.flush()
    }

    /** One row per navigation edge (RFC 4180, UTF-8 with BOM, CRLF). */
    internal fun writeRoutesCsv(session: Session, w: Writer) {
        w.write(BOM)
        val csv = CsvWriter(w)
        for (c in ROUTE_COLUMNS) csv.field(c)
        csv.end()
        for (e in session.edges) {
            csv.text(e.id)
            csv.text(e.from)
            csv.text(ExportText.nodeTitle(session, e.from))
            csv.field(e.action.name)
            csv.text(e.element?.display())
            csv.text(e.element?.resId)
            csv.text(e.element?.path)
            csv.text(e.to)
            csv.text(ExportText.nodeTitle(session, e.to))
            csv.int(e.count)
            csv.field(e.source.name)
            csv.end()
        }
        w.flush()
    }

    // ---------------------------------------------------------------- ZIP

    private fun writeZip(session: Session, exportedAt: Long, target: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target), BUF)).use { zip ->
            textEntry(zip, "README.txt", exportedAt) { writeReadme(session, exportedAt, it) }
            streamEntry(zip, "session.json", exportedAt) { BundleWriter.write(session, exportedAt, it) }
            textEntry(zip, "graph.mmd", exportedAt) { it.write(mermaid(session)) }
            textEntry(zip, "graph.dot", exportedAt) { it.write(dot(session)) }
            textEntry(zip, "elements.csv", exportedAt) { writeElementsCsv(session, it) }
            textEntry(zip, "routes.csv", exportedAt) { writeRoutesCsv(session, it) }
            textEntry(zip, "report.html", exportedAt) { HtmlReport.write(session, exportedAt, it) }

            // Re-encoded (not copied) so these files carry every field, like session.json.
            val usedNames = HashSet<String>()
            for (s in session.screens) {
                val snap = SessionStore.loadScreen(session.id, s.id) ?: continue
                val name = uniqueEntry(usedNames, "screens/" + entrySafe(s.id) + ".json")
                textEntry(zip, name, exportedAt) {
                    it.write(ExportJson.encodeToString(ScreenSnapshot.serializer(), snap))
                }
            }

            val shotsDir = SessionStore.shotsDir(session.id)
            val copied = HashSet<String>()
            for (s in session.screens) {
                val shotName = s.screenshot ?: continue
                val f = File(shotsDir, shotName)
                if (!f.isFile || !copied.add(f.name)) continue
                fileEntry(zip, uniqueEntry(usedNames, "shots/" + entrySafe(f.name)), f, exportedAt)
            }
            zip.finish()
        }
    }

    private fun writeReadme(session: Session, exportedAt: Long, w: Writer) {
        val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("id-ID"))
        val app = session.appLabel?.takeIf { it.isNotBlank() } ?: session.targetPkg ?: "-"
        val lines = listOf(
            "UI Mapper - paket ekspor (${ExportBundle.FORMAT})",
            "",
            "Sesi      : ${session.name}",
            "Aplikasi  : $app" + (session.targetPkg?.let { " ($it)" } ?: ""),
            "Perangkat : ${session.device ?: "-"}",
            "Diekspor  : ${fmt.format(Date(exportedAt))}",
            "Layar     : ${session.screens.size}",
            "Transisi  : ${session.edges.size}",
            "",
            "Isi paket:",
            "  report.html   Laporan lengkap: peta navigasi, rute, transisi, screenshot dan elemen.",
            "                Buka di browser mana pun, tidak butuh internet.",
            "  session.json  Data lengkap (format ${ExportBundle.FORMAT}): sesi, pohon UI tiap layar, rute.",
            "  graph.mmd     Diagram Mermaid peta navigasi.",
            "  graph.dot     Diagram Graphviz (contoh: dot -Tsvg graph.dot -o graph.svg).",
            "  elements.csv  Semua elemen dari semua layar (UTF-8, bisa dibuka di Excel/Sheets).",
            "  routes.csv    Semua transisi navigasi antar layar.",
            "  screens/      Snapshot UI per layar (JSON).",
            "  shots/        Screenshot per layar (jika diambil saat merekam).",
            "",
            "Privasi: isi kolom sandi dan teks yang diketik pengguna tidak disimpan di data elemen; kolom",
            "isian, keyboard dan bilah status ditutup pada screenshot. Teks lain yang tampil di layar tetap",
            "bisa terlihat di screenshot.",
        )
        for (l in lines) {
            w.write(l)
            w.write(CRLF)
        }
    }

    private inline fun textEntry(zip: ZipOutputStream, name: String, time: Long, body: (Writer) -> Unit) {
        zip.putNextEntry(ZipEntry(name).apply { this.time = time })
        BufferedWriter(OutputStreamWriter(NonClosingOutputStream(zip), Charsets.UTF_8), BUF).use { body(it) }
        zip.closeEntry()
    }

    private inline fun streamEntry(zip: ZipOutputStream, name: String, time: Long, body: (OutputStream) -> Unit) {
        zip.putNextEntry(ZipEntry(name).apply { this.time = time })
        BufferedOutputStream(NonClosingOutputStream(zip), BUF).use { body(it) }
        zip.closeEntry()
    }

    private fun fileEntry(zip: ZipOutputStream, name: String, file: File, time: Long) {
        zip.putNextEntry(ZipEntry(name).apply { this.time = time })
        file.inputStream().use { it.copyTo(zip, BUF) }
        zip.closeEntry()
    }

    // ---------------------------------------------------------------- helpers

    private inline fun writeText(file: File, body: (Writer) -> Unit) {
        BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), BUF).use { body(it) }
    }

    private fun purgeOld(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
        }
    }

    /** ASCII letters, digits, '-' and '_' only, max 40 chars (accents are folded: "é" -> "e"). */
    private fun fileBase(session: Session): String {
        val raw = session.name.ifBlank { session.appLabel ?: session.targetPkg ?: "" }
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_'
            when {
                ok -> sb.append(ch)
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                sb.isNotEmpty() && sb[sb.length - 1] != '_' -> sb.append('_')
            }
        }
        val base = sb.toString().trim('_').take(40).trim('_')
        return base.ifEmpty { "uimapper" }
    }

    private fun entrySafe(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name) {
            val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.'
            sb.append(if (ok) ch else '_')
        }
        return sb.toString().trimStart('.').ifEmpty { "file" }
    }

    private fun uniqueEntry(used: MutableSet<String>, name: String): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "${stem}_$i$ext"
            if (used.add(candidate)) return candidate
            i++
        }
    }

    /**
     * MediaProvider appends an extension when [mime] does not match the file extension (e.g. "x.mmd"
     * as text/plain would become "x.mmd.txt"). Fall back to a generic type so the name is kept.
     */
    private fun downloadMime(file: File, mime: String): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        val map = MimeTypeMap.getSingleton()
        val fromExt = map.getMimeTypeFromExtension(ext)
        val extFromMime = map.getExtensionFromMimeType(mime)
        return if (fromExt == mime || extFromMime == ext) mime else "application/octet-stream"
    }

    /** Mermaid label escaping: entity codes for # " < > |, newlines and control chars become spaces. */
    private fun mmd(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '#' -> sb.append("#35;")
                '"' -> sb.append("#quot;")
                '<' -> sb.append("#lt;")
                '>' -> sb.append("#gt;")
                '|' -> sb.append("#124;")
                else -> sb.append(if (ch < ' ' || ch == '\u007F') ' ' else ch)
            }
        }
        return sb.toString()
    }

    /** DOT quoted-string escaping: backslash and double quote escaped, newlines/control chars -> space. */
    private fun dotEsc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                else -> sb.append(if (ch < ' ' || ch == '\u007F') ' ' else ch)
            }
        }
        return sb.toString()
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/** Text helpers shared by the exporters of this package. */
internal object ExportText {

    /** Single line, at most [max] chars (ellipsis included), never splitting a surrogate pair. */
    fun clip(s: String, max: Int): String {
        val one = s.replace('\n', ' ').replace('\r', ' ').trim()
        if (one.length <= max) return one
        var end = (max - 1).coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(one[end - 1])) end--
        return one.substring(0, end).trimEnd() + "…"
    }

    /** Captured text, but never for password or editable fields (defence in depth; capture drops it too). */
    fun safeText(n: UiNode): String? = if (n.password || n.editable) null else n.text

    /** BACK and UNKNOWN transitions are drawn dashed. */
    fun isDashed(e: NavEdge): Boolean = e.action == ActionType.BACK || e.action == ActionType.UNKNOWN

    /** RouteFinder.stepText clipped to [max], plus " ×N" when the edge was seen more than once. */
    fun edgeLabel(e: NavEdge, max: Int): String {
        val base = clip(RouteFinder.stepText(e), max)
        return if (e.count > 1) "$base ×${e.count}" else base
    }

    /** Label without the id prefix: "Mulai", "Aplikasi lain (pkg)", the screen label, or the raw id. */
    fun nodeTitle(session: Session, id: String): String = when {
        id == START_NODE -> "Mulai"
        isExternalNode(id) -> "Aplikasi lain (${id.removePrefix(EXTERNAL_PREFIX)})"
        else -> session.screen(id)?.label ?: id
    }

    /** Spreadsheet formula-injection guard for untrusted cells (OWASP): prefix with an apostrophe. */
    fun neutralizeFormula(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        return when (s[0]) {
            '=', '+', '-', '@', '\t', '\r' -> "'$s"
            else -> s
        }
    }

    /** Pre-order walk with the same XPath-like locator as [UiTree.xpath], computed in O(n). */
    fun walkWithXpath(root: UiNode, visit: (UiNode, String) -> Unit) {
        fun rec(n: UiNode, path: String) {
            visit(n, path)
            if (n.children.isEmpty()) return
            val seen = HashMap<String, Int>()
            for (c in n.children) {
                val pos = (seen[c.cls] ?: 0) + 1
                seen[c.cls] = pos
                rec(c, path + "/" + c.simpleCls + "[" + pos + "]")
            }
        }
        rec(root, "/" + root.simpleCls + "[1]")
    }
}

/** RFC 4180 row writer: quotes fields containing comma, quote, CR or LF and doubles inner quotes. */
private class CsvWriter(private val w: Writer) {
    private var first = true

    fun field(value: String?) {
        if (!first) w.write(",")
        first = false
        if (value.isNullOrEmpty()) return
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            w.write("\"")
            w.write(value.replace("\"", "\"\""))
            w.write("\"")
        } else {
            w.write(value)
        }
    }

    /** Untrusted text from a captured app. */
    fun text(value: String?) = field(ExportText.neutralizeFormula(value))

    fun int(value: Int) = field(value.toString())

    fun bool(value: Boolean) = field(if (value) "true" else "false")

    fun end() {
        w.write("\r\n")
        first = true
    }
}

/** Stable Mermaid node ids ([A-Za-z0-9_], not a keyword, not starting with a digit, collision-free). */
private class MermaidIds {
    private val byRaw = HashMap<String, String>()
    private val used = HashSet<String>()

    fun of(raw: String): String {
        byRaw[raw]?.let { return it }
        var base = when {
            raw == START_NODE -> "START"
            isExternalNode(raw) -> "ext_" + sanitize(raw.removePrefix(EXTERNAL_PREFIX))
            else -> sanitize(raw)
        }
        if (base.isEmpty() || base[0].isDigit() || base.lowercase(Locale.ROOT) in RESERVED) base = "n_$base"
        var id = base
        var i = 2
        while (!used.add(id)) {
            id = "${base}_$i"
            i++
        }
        byRaw[raw] = id
        return id
    }

    private fun sanitize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'
            sb.append(if (ok) ch else '_')
        }
        return sb.toString()
    }

    private companion object {
        val RESERVED = setOf(
            "end", "graph", "flowchart", "subgraph", "style", "classdef", "class", "click", "call",
            "href", "default", "linkstyle", "interpolate", "direction", "accdescr", "acctitle",
        )
    }
}

/** Lets a Writer/stream be closed without closing the ZIP it writes into (close() only flushes). */
private class NonClosingOutputStream(private val target: OutputStream) : OutputStream() {
    override fun write(b: Int) = target.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
    override fun flush() = target.flush()
    override fun close() = target.flush()
}
