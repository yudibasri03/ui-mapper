package app.uimapper

import android.app.Application
import app.uimapper.data.AppSettings
import app.uimapper.data.SessionStore

class UiMapperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        SessionStore.init(this)
    }
}
