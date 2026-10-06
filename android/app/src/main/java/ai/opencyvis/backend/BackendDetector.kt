package ai.opencyvis.backend

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

sealed class DetectionResult {
    data class Ready(val backend: PrivilegeBackend) : DetectionResult()
    data class SetupRequired(val availableConnectors: List<ServiceConnector>) : DetectionResult()
    object NoneAvailable : DetectionResult()
}

object BackendDetector {
    private const val TAG = "BackendDetector"

    fun isSystemUid(uid: Int = Process.myUid()): Boolean = uid == 1000

    suspend fun detect(context: Context): DetectionResult {
        if (isSystemUid()) {
            Log.i(TAG, "Running as system app (uid=${Process.myUid()})")
            return DetectionResult.Ready(SystemBackend())
        }

        val connectors = buildConnectorList(context)
        for (connector in connectors) {
            // A running Shizuku that has not been authorized yet must lead to the
            // permission UI, not to a weaker fallback such as wireless ADB.
            if (connector is ShizukuConnector &&
                ShizukuConnector.status() == ShizukuStatus.PERMISSION_REQUIRED
            ) {
                Log.i(TAG, "Shizuku is running and needs app permission")
                return DetectionResult.SetupRequired(connectors)
            }
            if (!connector.isAvailable()) continue
            Log.i(TAG, "Trying ${connector.name}...")
            connector.connect()

            val result = withTimeoutOrNull(connector.connectTimeoutMs) {
                connector.state.first {
                    it is ConnectionState.Connected || it is ConnectionState.Failed || it is ConnectionState.NeedsPairing
                }
            }

            when (result) {
                is ConnectionState.Connected -> {
                    val svc = IPrivilegedService.Stub.asInterface(result.serviceBinder)
                    Log.i(TAG, "Connected via ${connector.name} (uid=${svc.serviceUid})")
                    // Pin the first working backend so a rooted device whose owner denied
                    // root does not re-prompt on every launch.
                    BackendPreference.setIfAbsent(context, connector.name)
                    return DetectionResult.Ready(RemoteBackend(connector, svc))
                }
                is ConnectionState.NeedsPairing -> {
                    Log.i(TAG, "${connector.name} needs pairing")
                    // Return setup required — UI will handle pairing flow
                    return DetectionResult.SetupRequired(connectors.filter { it.isAvailable() })
                }
                else -> {
                    Log.w(TAG, "${connector.name} failed or timed out")
                    connector.disconnect()
                }
            }
        }

        Log.w(TAG, "No privileged backend available")
        return DetectionResult.NoneAvailable
    }

    private fun buildConnectorList(context: Context): List<ServiceConnector> {
        val names = connectorOrder(
            preferred = BackendPreference.get(context),
            rootLikely = RootConnector.isRootLikely(context),
        )
        return names.map { name ->
            when (name) {
                RootConnector.NAME -> RootConnector(context)
                SHIZUKU -> ShizukuConnector(context)
                else -> DirectConnector(context)
            }
        }
    }

    /**
     * Order in which to try standard-app backends. Root is the most capable, so it
     * goes first on a rooted device — unless the user picked another backend, in
     * which case root is left out entirely rather than silently taking over.
     * The user's pick, when set, is always tried first.
     */
    fun connectorOrder(preferred: String?, rootLikely: Boolean): List<String> {
        val includeRoot = preferred == RootConnector.NAME || (preferred == null && rootLikely)
        val defaults = buildList {
            if (includeRoot) add(RootConnector.NAME)
            add(SHIZUKU)
            add(ADB_DIRECT)
        }
        return defaults.sortedByDescending { it == preferred }
    }

    /** Whether detection would try root on this device (see [connectorOrder]). */
    fun shouldTryRoot(context: Context): Boolean =
        RootConnector.NAME in connectorOrder(BackendPreference.get(context), RootConnector.isRootLikely(context))

    private const val SHIZUKU = "shizuku"
    private const val ADB_DIRECT = "adb-direct"
}
