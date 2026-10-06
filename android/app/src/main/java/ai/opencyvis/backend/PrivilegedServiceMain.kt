package ai.opencyvis.backend

import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Entry point when launched (as shell via ADB, or as root via su) with:
 *   CLASSPATH=<apk> app_process /system/bin \
 *     ai.opencyvis.backend.PrivilegedServiceMain --token=<token> --authority=<authority> [--user=<id>]
 *
 * Creates PrivilegedService, sends Binder to app via ContentProvider, enters Looper.
 */
object PrivilegedServiceMain {
    private const val TAG = "PrivilegedServiceMain"

    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "Starting privileged service process (uid=${android.os.Process.myUid()})")
        Looper.prepareMainLooper()

        val token = args.find { it.startsWith("--token=") }?.substringAfter("=")
        val authority = args.find { it.startsWith("--authority=") }?.substringAfter("=")
        // The app's user. Defaults to our own, which is wrong for root (always user 0)
        // when the app runs in a secondary user or work profile.
        val userId = args.find { it.startsWith("--user=") }?.substringAfter("=")?.toIntOrNull()
            ?: (android.os.Process.myUid() / 100000)

        if (token == null || authority == null) {
            Log.e(TAG, "Missing --token or --authority argument")
            return
        }

        val service = PrivilegedService()
        val binder = service.asBinder()

        val reply = try {
            sendBinderViaHiddenApi(authority, token, binder, userId).also {
                Log.i(TAG, "Binder sent to app via getContentProviderExternal")
            }
        } catch (e: Exception) {
            Log.w(TAG, "getContentProviderExternal failed, trying ActivityThread fallback", e)
            try {
                sendBinderViaActivityThread(authority, token, binder).also {
                    Log.i(TAG, "Binder sent to app via ActivityThread fallback")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to send binder to app", e2)
                return
            }
        }

        // Self-terminate when the app dies (prevents orphaned privileged processes).
        // The app only replies with its token when it accepted our binder, so a
        // missing token means the exchange was rejected (stale/mismatched token).
        val appToken = reply?.getBinder("app_token")
        if (appToken == null) {
            Log.e(TAG, "Binder exchange rejected by app, exiting")
            System.exit(1)
        }
        try {
            appToken!!.linkToDeath({
                Log.i(TAG, "App died, exiting privileged service")
                System.exit(0)
            }, 0)
        } catch (e: android.os.RemoteException) {
            Log.i(TAG, "App already dead, exiting privileged service")
            System.exit(0)
        }

        Log.i(TAG, "Entering Looper")
        Looper.loop()
    }

    /**
     * Shizuku's approach: use IActivityManager.getContentProviderExternal() hidden API.
     * This works at shell uid without needing a full Application context.
     */
    private fun sendBinderViaHiddenApi(authority: String, token: String, binder: IBinder, userId: Int): Bundle? {
        val bundle = Bundle().apply {
            putBinder("binder", binder)
            putString("token", token)
        }

        val amBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity") as IBinder

        val am = Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, amBinder)

        // IActivityManager.getContentProviderExternal(String name, int userId, IBinder token, String callingTag)
        val providerHolder = try {
            am.javaClass.getMethod(
                "getContentProviderExternal",
                String::class.java, Int::class.javaPrimitiveType,
                IBinder::class.java, String::class.java
            ).invoke(am, authority, userId, null, TAG)
        } catch (_: NoSuchMethodException) {
            // Older API: getContentProviderExternal(String, int, IBinder)
            am.javaClass.getMethod(
                "getContentProviderExternal",
                String::class.java, Int::class.javaPrimitiveType,
                IBinder::class.java
            ).invoke(am, authority, userId, null)
        }

        val provider = providerHolder!!.javaClass.getField("provider").get(providerHolder)

        // Log all call() methods to understand the API
        val callMethods = provider!!.javaClass.methods.filter { it.name == "call" }
        for (m in callMethods) {
            Log.d(TAG, "call variant: ${m.parameterTypes.joinToString { it.name }}")
        }

        // Identify as ourselves: shell (2000) under ADB, root (0) under su.
        // AttributionSource validation accepts any uid when the caller is root.
        val callingUid = android.os.Process.myUid()
        val callingPkg = if (callingUid == 2000) "com.android.shell" else null

        // Try each call() variant until one works
        var succeeded = false
        var reply: Bundle? = null
        for (m in callMethods.sortedByDescending { it.parameterCount }) {
            try {
                val paramTypes = m.parameterTypes
                when {
                    paramTypes.size >= 5 && paramTypes[0].name.contains("AttributionSource") -> {
                        val attrSource = newAttributionSource(paramTypes[0], callingUid, callingPkg)
                        reply = m.invoke(provider, attrSource, authority, "exchangeBinder", null, bundle) as Bundle?
                        succeeded = true
                    }
                    paramTypes.size == 5 && paramTypes[0] == String::class.java -> {
                        reply = m.invoke(provider, callingPkg, authority, "exchangeBinder", null, bundle) as Bundle?
                        succeeded = true
                    }
                    paramTypes.size == 4 && paramTypes[0].name.contains("AttributionSource") -> {
                        val attrSource = newAttributionSource(paramTypes[0], callingUid, callingPkg)
                        reply = m.invoke(provider, attrSource, "exchangeBinder", null, bundle) as Bundle?
                        succeeded = true
                    }
                    paramTypes.size == 4 && paramTypes[0] == String::class.java -> {
                        reply = m.invoke(provider, callingPkg, "exchangeBinder", null, bundle) as Bundle?
                        succeeded = true
                    }
                }
                if (succeeded) {
                    Log.i(TAG, "call() succeeded: ${paramTypes.joinToString { it.simpleName }}")
                    break
                }
            } catch (e: Exception) {
                val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
                Log.d(TAG, "call() failed (${m.parameterTypes.joinToString { it.simpleName }}): ${cause?.message}")
            }
        }
        if (!succeeded) throw IllegalStateException("All IContentProvider.call variants failed")
        return reply
    }

    private fun newAttributionSource(attrSourceClass: Class<*>, uid: Int, packageName: String?): Any =
        attrSourceClass.getConstructor(
            Int::class.javaPrimitiveType, String::class.java, String::class.java
        ).newInstance(uid, packageName, null)

    private fun sendBinderViaActivityThread(authority: String, token: String, binder: IBinder): Bundle? {
        val bundle = Bundle().apply {
            putBinder("binder", binder)
            putString("token", token)
        }
        val uri = android.net.Uri.parse("content://$authority")
        val atClass = Class.forName("android.app.ActivityThread")
        val at = atClass.getMethod("systemMain").invoke(null)
        val app = atClass.getMethod("getApplication").invoke(at) as android.app.Application
        return app.contentResolver.call(uri, "exchangeBinder", null, bundle)
    }
}
