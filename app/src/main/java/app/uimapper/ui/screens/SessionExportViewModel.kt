package app.uimapper.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.uimapper.export.ExportFormat
import app.uimapper.export.Exporters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs session exports outside the composition (scoped to the session-detail back-stack entry), so a
 * rotation, a theme change or switching tabs neither drops the result nor unlocks the buttons while an
 * export is still being written.
 */
class SessionExportViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Event {
        /** Open the share sheet for [file]. */
        class Share(val file: File, val mime: String) : Event

        class Message(val text: String) : Event
    }

    private val _busy = MutableStateFlow<String?>(null)

    /** "share:<FORMAT>" or "save:<FORMAT>" while an export runs, else null. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    // Buffered: an event produced while no one collects (e.g. during a configuration change) is delivered
    // once the screen collects again.
    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    fun share(sessionId: String, format: ExportFormat) {
        if (_busy.value != null) return
        _busy.value = "share:${format.name}"
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { Exporters.export(getApplication(), sessionId, format) }
                _events.send(Event.Share(file, format.mime))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(Event.Message("Ekspor ${format.title} gagal: ${errorText(e)}"))
            } finally {
                _busy.value = null
            }
        }
    }

    fun save(sessionId: String, format: ExportFormat) {
        if (_busy.value != null) return
        _busy.value = "save:${format.name}"
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val savedName = withContext(Dispatchers.IO) {
                    val file = Exporters.export(app, sessionId, format)
                    Exporters.saveToDownloads(app, file, format.mime)?.let { file.name }
                }
                _events.send(
                    Event.Message(
                        if (savedName != null) "Tersimpan di Unduhan: $savedName" else "Gagal menyimpan ke Unduhan. Coba Bagikan.",
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(Event.Message("Simpan ${format.title} gagal: ${errorText(e)}"))
            } finally {
                _busy.value = null
            }
        }
    }

    private fun errorText(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
