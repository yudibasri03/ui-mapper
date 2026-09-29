@file:OptIn(ExperimentalSerializationApi::class)

package app.uimapper.export

import app.uimapper.core.RouteFinder
import app.uimapper.data.SessionStore
import app.uimapper.model.ScreenSnapshot
import app.uimapper.model.Session
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream

/**
 * Complete, self-describing export of one session (schema "uimapper/1").
 *
 * - [session]: the session with screen summaries and every navigation edge.
 * - [screens]: the full UI tree of every screen that still has a snapshot on disk.
 * - [routesFromStart]: screenId -> ids of the edges of the shortest tap-route from the start screen
 *   (empty list for the start screen itself; unreachable screens are absent).
 *
 * Big sessions are written with [BundleWriter], which streams exactly this shape one screen at a time
 * so the whole bundle never has to live in memory. It can be read back with
 * `SessionStore.json.decodeFromString(ExportBundle.serializer(), text)`.
 */
@Serializable
data class ExportBundle(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val format: String = FORMAT,
    val exportedAt: Long,
    val session: Session,
    val screens: List<ScreenSnapshot>,
    val routesFromStart: Map<String, List<String>>,
) {
    companion object {
        const val FORMAT: String = "uimapper/1"

        /** screenId -> edge ids of the shortest tap-route from the start screen. */
        fun routeIds(session: Session): Map<String, List<String>> =
            RouteFinder.routesFromStart(session).mapValues { (_, path) -> path.map { it.id } }
    }
}

/**
 * JSON for exported files: unlike the compact storage format it writes every field, defaults included
 * (enabled/visible = true, count = 1, empty children, ...), so external tools never see a missing key
 * for a default value. Null values are still omitted.
 */
internal val ExportJson: Json = Json(from = SessionStore.json) { encodeDefaults = true }

/**
 * Streams an [ExportBundle]-shaped, pretty-printed JSON document. Only one [ScreenSnapshot] is
 * encoded at a time. Blocking: call from Dispatchers.IO. The stream is flushed but not closed.
 */
internal object BundleWriter {

    private const val INDENT = "    "

    private val pretty: Json = Json(from = ExportJson) { prettyPrint = true }

    private val routesSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    fun write(session: Session, exportedAt: Long, out: OutputStream) {
        // Nested values are pretty-printed from column 0; these wrappers re-indent them in place.
        val level1 = IndentingOutputStream(out, INDENT)
        val level2 = IndentingOutputStream(out, INDENT + INDENT)

        out.writeUtf8("{\n")
        out.writeUtf8(INDENT + "\"format\": " + pretty.encodeToString(String.serializer(), ExportBundle.FORMAT) + ",\n")
        out.writeUtf8(INDENT + "\"exportedAt\": " + exportedAt + ",\n")
        out.writeUtf8(INDENT + "\"session\": ")
        pretty.encodeToStream(Session.serializer(), session, level1)

        out.writeUtf8(",\n" + INDENT + "\"screens\": [")
        var written = 0
        for (summary in session.screens) {
            val snap = SessionStore.loadScreen(session.id, summary.id) ?: continue
            out.writeUtf8(if (written == 0) "\n$INDENT$INDENT" else ",\n$INDENT$INDENT")
            pretty.encodeToStream(ScreenSnapshot.serializer(), snap, level2)
            written++
        }
        out.writeUtf8(if (written == 0) "]" else "\n$INDENT]")

        out.writeUtf8(",\n" + INDENT + "\"routesFromStart\": ")
        pretty.encodeToStream(routesSerializer, ExportBundle.routeIds(session), level1)
        out.writeUtf8("\n}\n")
        out.flush()
    }

    private fun OutputStream.writeUtf8(s: String) = write(s.toByteArray(Charsets.UTF_8))
}

/**
 * Inserts [indent] after every line feed. Safe on UTF-8 bytes: 0x0A never occurs inside a multi-byte
 * sequence, and pretty-printed JSON strings never contain a raw line feed (it is escaped as \n).
 * Closing it only flushes the target.
 */
private class IndentingOutputStream(private val target: OutputStream, indent: String) : OutputStream() {

    private val indentBytes = indent.toByteArray(Charsets.UTF_8)

    override fun write(b: Int) {
        target.write(b)
        if (b == LF) target.write(indentBytes)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var start = off
        val end = off + len
        for (i in off until end) {
            if (b[i].toInt() == LF) {
                target.write(b, start, i - start + 1)
                target.write(indentBytes)
                start = i + 1
            }
        }
        if (start < end) target.write(b, start, end - start)
    }

    override fun flush() = target.flush()

    override fun close() = target.flush()

    private companion object {
        const val LF = '\n'.code
    }
}
