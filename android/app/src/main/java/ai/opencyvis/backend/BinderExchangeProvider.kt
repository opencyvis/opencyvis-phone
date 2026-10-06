package ai.opencyvis.backend

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ContentProvider that receives IBinder from the PrivilegedService process.
 * The privileged process calls ContentResolver.call() with a Bundle
 * containing the Binder + a one-time token for authentication.
 */
class BinderExchangeProvider : ContentProvider() {
    companion object {
        private const val TAG = "BinderExchange"

        /** One slot per launch, so concurrent launches (e.g. root + ADB) can't steal each other's binder. */
        private class Pending {
            val latch = CountDownLatch(1)
            @Volatile var binder: IBinder? = null
        }

        private val pending = ConcurrentHashMap<String, Pending>()

        /**
         * Lives as long as the app process. Handed back to the privileged process,
         * which links to its death and exits with the app — nothing else reaps a
         * root process (no ADB session or Shizuku server owns it).
         */
        private val appToken = android.os.Binder()

        fun prepare(): String {
            val token = java.util.UUID.randomUUID().toString()
            pending[token] = Pending()
            return token
        }

        /** Waits for the binder sent with [token]; the token is single-use either way. */
        fun awaitBinder(token: String, timeoutMs: Long): IBinder? {
            val slot = pending[token] ?: return null
            slot.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            pending.remove(token)
            return slot.binder
        }

        /** Wake [awaitBinder] early when the launch for [token] has already failed. */
        fun abort(token: String) {
            pending[token]?.latch?.countDown()
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Only accept binder exchanges from shell (uid 2000), root (0), or system (1000)
        val callerUid = android.os.Binder.getCallingUid()
        if (callerUid != 2000 && callerUid != 0 && callerUid != 1000) {
            Log.w(TAG, "Rejected binder exchange from uid=$callerUid")
            return null
        }

        if (method == "exchangeBinder") {
            val slot = extras?.getString("token")?.let { pending[it] }
            if (slot != null) {
                slot.binder = extras.getBinder("binder")
                Log.i(TAG, "Binder received (token matched)")
                slot.latch.countDown()
                return Bundle().apply { putBinder("app_token", appToken) }
            } else {
                Log.w(TAG, "Binder exchange rejected: token mismatch")
            }
        }
        return null
    }

    // Required ContentProvider overrides (unused)
    override fun query(
        uri: Uri,
        proj: Array<String>?,
        sel: String?,
        selArgs: Array<String>?,
        order: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, selArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        sel: String?,
        selArgs: Array<String>?
    ): Int = 0
}
