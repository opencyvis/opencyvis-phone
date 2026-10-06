package ai.opencyvis.backend

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Launches PrivilegedService as root (uid 0) through the device's `su`, then
 * receives its Binder via [BinderExchangeProvider] — the same app_process
 * handshake as [DirectConnector], with `su` in place of the ADB shell.
 *
 * Works with any `su` that reads commands from stdin: Magisk (and forks such as
 * Magisk Alpha / Kitsune), KernelSU (and KernelSU Next / SukiSU), APatch, SuperSU.
 * The first connection triggers the root manager's grant prompt (Magisk); KernelSU
 * and APatch instead require OpenCyvis to be allowed in the manager app beforehand.
 */
class RootConnector(context: Context) : ServiceConnector {

    companion object {
        private const val TAG = "RootConnector"
        const val NAME = "root"
        private const val PROCESS_NAME = "opencyvis:root"

        /** Covers the root manager's grant prompt (Magisk times out after 10s by default) plus startup. */
        private const val BINDER_TIMEOUT_MS = 30_000L

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/system_ext/bin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/su/bin/su",
            "/vendor/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
        )

        /** Manager apps whose presence means the device is rooted even if `su` is hidden from us. */
        private val ROOT_MANAGER_PACKAGES = listOf(
            "com.topjohnwu.magisk",      // Magisk
            "io.github.vvb2060.magisk",  // Magisk Alpha
            "io.github.huskydg.magisk",  // Kitsune Mask
            "me.weishu.kernelsu",        // KernelSU
            "com.rifsxd.ksunext",        // KernelSU Next
            "com.sukisu.ultra",          // SukiSU Ultra
            "me.bmax.apatch",            // APatch
        )

        /** First `su` found in the well-known locations or on [pathEnv], or null. */
        fun findSuBinary(
            pathEnv: String? = System.getenv("PATH"),
            exists: (String) -> Boolean = { File(it).exists() },
        ): String? {
            val fromPath = pathEnv.orEmpty().split(':').filter { it.isNotEmpty() }.map { "$it/su" }
            return (SU_PATHS + fromPath).distinct().firstOrNull(exists)
        }

        private fun hasRootManager(context: Context): Boolean = ROOT_MANAGER_PACKAGES.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        /** Whether the device looks rooted. A hit does not mean OpenCyvis has been granted root. */
        fun isRootLikely(context: Context): Boolean =
            findSuBinary() != null || hasRootManager(context)

        /**
         * Script fed to the root shell on stdin. `exec` turns the shell into the
         * service process, which keeps running until the app dies or calls exit().
         */
        fun buildLaunchScript(apkPath: String, token: String, authority: String, userId: Int): String =
            "export CLASSPATH='$apkPath'\n" +
                "exec /system/bin/app_process /system/bin --nice-name=$PROCESS_NAME " +
                "ai.opencyvis.backend.PrivilegedServiceMain " +
                "--token=$token --authority=$authority --user=$userId\n"
    }

    private val context = context.applicationContext

    override val name = NAME
    override val connectTimeoutMs = BINDER_TIMEOUT_MS + 5_000L

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var suProcess: Process? = null
    @Volatile private var connectedBinder: IBinder? = null
    /** Set by [disconnect] while a launch is still waiting for the grant / binder. */
    @Volatile private var cancelled = false

    override fun isAvailable(): Boolean = isRootLikely(context)

    override fun connect() {
        if (_state.value is ConnectionState.Connecting || _state.value is ConnectionState.Connected) return
        cancelled = false
        _state.value = ConnectionState.Connecting
        scope.launch { launchService() }
    }

    private fun launchService() {
        val token = BinderExchangeProvider.prepare()
        val suPath = findSuBinary() ?: "su"
        val process = try {
            ProcessBuilder(suPath).redirectErrorStream(true).start()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot run su ($suPath): ${e.message}")
            _state.value = ConnectionState.Failed("su not found — is this device rooted?")
            return
        }
        suProcess = process

        // Drain su output (it would otherwise block on a full pipe) and keep the tail
        // for the error message. When su exits without handing over a binder (denied,
        // not granted in the manager), wake the waiter below instead of timing out.
        val outputTail = ArrayDeque<String>()
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "su: $line")
                    synchronized(outputTail) {
                        outputTail.addLast(line)
                        if (outputTail.size > 5) outputTail.removeFirst()
                    }
                }
            } catch (_: IOException) {}
            val exit = try { process.waitFor() } catch (_: InterruptedException) { -1 }
            Log.i(TAG, "su process exited (code=$exit)")
            BinderExchangeProvider.abort(token)
        }, "root-su-output").apply { isDaemon = true }.start()

        val script = buildLaunchScript(
            apkPath = context.applicationInfo.sourceDir,
            token = token,
            authority = "${context.packageName}.binder_exchange",
            userId = android.os.Process.myUid() / 100_000,
        )
        try {
            process.outputStream.bufferedWriter().use { it.write(script) }
        } catch (e: IOException) {
            // su already exited (e.g. denied before reading stdin); handled below.
            Log.w(TAG, "Writing launch script to su failed: ${e.message}")
        }

        val binder = BinderExchangeProvider.awaitBinder(token, BINDER_TIMEOUT_MS)
        if (binder == null) {
            val reason = if (process.isAlive) "timed out waiting for root access" else "root access denied"
            process.destroy()
            suProcess = null
            val detail = synchronized(outputTail) { outputTail.joinToString(" ").trim() }
            Log.w(TAG, "Root service did not start: $reason ($detail)")
            _state.value = ConnectionState.Failed(if (detail.isEmpty()) reason else "$reason: $detail")
            return
        }

        if (cancelled) {
            Log.i(TAG, "Connect was cancelled; stopping the root service that just started")
            try { IPrivilegedService.Stub.asInterface(binder).exit() } catch (_: Exception) {}
            return
        }
        try {
            binder.linkToDeath({
                Log.w(TAG, "Root service died")
                if (connectedBinder === binder) {
                    connectedBinder = null
                    suProcess = null
                    _state.value = ConnectionState.Disconnected
                }
            }, 0)
        } catch (e: android.os.RemoteException) {
            _state.value = ConnectionState.Failed("Root service died during startup")
            return
        }
        connectedBinder = binder
        _state.value = ConnectionState.Connected(binder)
        Log.i(TAG, "Root service started, binder received")
    }

    override fun disconnect() {
        cancelled = true
        val binder = connectedBinder
        connectedBinder = null
        if (binder != null) {
            // The root process outlives the su client, so ask it to exit itself.
            try {
                IPrivilegedService.Stub.asInterface(binder).exit()
            } catch (_: Exception) {}
        }
        suProcess?.destroy()
        suProcess = null
        _state.value = ConnectionState.Disconnected
    }
}
