package ai.opencyvis

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log

class App : Application() {

    companion object {
        private const val TAG = "OpencyvisApp"

        @Volatile
        var agentService: AgentService? = null
    }

    override fun onCreate() {
        super.onCreate()
        if (isDebuggableBuild()) {
            TestShellService.register()
        }
    }

    private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
