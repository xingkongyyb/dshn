package dev.dshn.app

import android.app.Application
import dev.dshn.app.di.AppContainer

class DshnApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
}
