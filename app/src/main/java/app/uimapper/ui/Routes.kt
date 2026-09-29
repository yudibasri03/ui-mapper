package app.uimapper.ui

/** Navigation routes shared by every screen. */
object Routes {
    const val HOME = "home"
    const val APPS = "apps"
    const val SETTINGS = "settings"
    const val SESSIONS = "sessions"
    const val SESSION = "session/{sessionId}"
    const val SCREEN = "screen/{sessionId}/{screenId}"
    const val HELP = "help"

    fun session(sessionId: String) = "session/$sessionId"
    fun screen(sessionId: String, screenId: String) = "screen/$sessionId/$screenId"
}
