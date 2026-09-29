package app.uimapper.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Process-wide, in-memory activity log. The accessibility service (and any other component in the
 * same process) appends short, human-readable lines through [log]; the Live Log screen observes
 * [entries] and renders them as a scrolling, terminal-style feed.
 *
 * Entries live only in memory, are capped to [MAX_ENTRIES] (oldest dropped), and never touch disk or
 * the network. [log] is thread-safe so it can be called from the service's background handler as well
 * as the UI thread.
 */
object EventLog {

    /** Category of an event; the Live Log screen colours each line by its tag. */
    enum class Tag { REC, STOP, NEW, SEEN, TAP, EDGE, BACK, EXT, SNAP, INSPECT, INFO, WARN }

    /**
     * One log line. [id] is monotonic across the process lifetime (a stable list key), [timeMs] is
     * wall-clock time from [System.currentTimeMillis].
     */
    data class Entry(val id: Long, val timeMs: Long, val tag: Tag, val message: String)

    /** Maximum retained entries; appending past this drops the oldest. */
    const val MAX_ENTRIES = 800

    private val lock = Any()
    private var nextId = 0L
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Oldest first, capped to [MAX_ENTRIES]. Starts empty and emits a new snapshot on every [log]. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /**
     * Appends one line. Thread-safe and callable from any thread. Assigns a monotonic id and the
     * current wall-clock time, trims the buffer to [MAX_ENTRIES] (dropping the oldest), and publishes
     * a new immutable snapshot to [entries]. Publishing inside the lock keeps snapshots monotonic even
     * under concurrent callers.
     */
    fun log(tag: Tag, message: String) {
        synchronized(lock) {
            buffer.addLast(Entry(id = nextId++, timeMs = System.currentTimeMillis(), tag = tag, message = message))
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    /** Removes every entry. The id counter keeps advancing so list keys stay unique afterwards. */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /**
     * The current log as plain text, one line per entry: `"HH:mm:ss  TAG  message\n"` (24-hour, device
     * default time zone and locale). Used for copy and share.
     */
    fun formatPlain(): String {
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val snapshot = _entries.value
        val sb = StringBuilder(snapshot.size * 32)
        for (entry in snapshot) {
            sb.append(format.format(entry.timeMs))
                .append("  ")
                .append(entry.tag.name)
                .append("  ")
                .append(entry.message)
                .append('\n')
        }
        return sb.toString()
    }
}
