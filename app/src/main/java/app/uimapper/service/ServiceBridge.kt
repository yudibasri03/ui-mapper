package app.uimapper.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServiceMode { IDLE, RECORDING }

/** Live state published by [InspectorService]; observed by the app UI and the overlay. */
data class ServiceState(
    val connected: Boolean = false,
    val mode: ServiceMode = ServiceMode.IDLE,
    val inspecting: Boolean = false,
    val overlayVisible: Boolean = false,
    val sessionId: String? = null,
    val targetPkg: String? = null,
    val foregroundPkg: String? = null,
    val foregroundActivity: String? = null,
    val currentScreenId: String? = null,
    val screenCount: Int = 0,
    val edgeCount: Int = 0,
    /** Latest human-readable status line (Indonesian). */
    val status: String? = null,
)

/** Commands from the app UI / overlay to the running service. */
sealed interface ServiceCommand {
    /** Passive route recording: the user taps around, the service maps screens + transitions. */
    data class StartRecording(val sessionId: String, val targetPkg: String?, val launchTarget: Boolean = true) : ServiceCommand

    /** Stop recording (keeps the overlay). */
    data object Stop : ServiceCommand

    /**
     * Capture the current foreground screen once. With [sessionId] null the service uses the active
     * session, else a per-app SNAPSHOT session it creates on demand.
     */
    data class CaptureNow(val sessionId: String? = null) : ServiceCommand

    data class SetOverlay(val visible: Boolean) : ServiceCommand
    data class SetInspect(val enabled: Boolean) : ServiceCommand
}

/** Process-wide channel between the Activity/UI and the AccessibilityService. */
object ServiceBridge {

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    fun update(fn: (ServiceState) -> ServiceState) = _state.update(fn)

    private val _commands = MutableSharedFlow<ServiceCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<ServiceCommand> = _commands.asSharedFlow()

    /** Returns false when the service is not connected (the command would be lost). */
    fun send(cmd: ServiceCommand): Boolean {
        if (!_state.value.connected) return false
        return _commands.tryEmit(cmd)
    }

    fun componentName(context: Context) = ComponentName(context, InspectorService::class.java)

    /** True when the user has enabled our service in system Accessibility settings. */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = componentName(context)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (item in splitter) {
            val cn = ComponentName.unflattenFromString(item) ?: continue
            if (cn == me) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** App-info page: on Android 13+ sideloaded apps must "Allow restricted settings" here first. */
    fun openAppInfo(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
