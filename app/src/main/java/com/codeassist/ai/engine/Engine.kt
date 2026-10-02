package com.codeassist.ai.engine

import android.content.Context

/** Central engine bootstrap/recovery entry point used by the app shell. */
object Engine {
    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        EngineStore.init(app)
        Sandbox.init(app)
        CredentialStore.init(app)
        ToolBootstrap.registerAll()
        ModelLifecycle.init(app)
        RunRecovery.reconcileOnStartup()
        EngineStore.lastSessionClean = false
        initialized = true
    }
}
