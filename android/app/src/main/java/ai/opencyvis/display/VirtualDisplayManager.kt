package ai.opencyvis.display

import android.content.Context
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.Surface
import java.lang.reflect.Method
import java.nio.ByteBuffer

data class TaskSnapshot(
    val taskId: Int,
    val displayId: Int,
    val basePackage: String?,
    val topPackage: String?,
    val lastActiveTime: Long
) {
    fun containsPackage(packageName: String): Boolean {
        return basePackage == packageName || topPackage == packageName
    }
}

/**
 * Creates and manages a VirtualDisplay for agent operation.
 * The agent runs apps on this virtual screen while the user uses the physical screen.
 *
 * Supports task reparenting: moving Activity tasks between the VD and physical display
 * via moveTaskToDisplay() for view/takeover mode.
 */
class VirtualDisplayManager(private val context: Context) {

    companion object {
        private const val TAG = "VirtualDisplayManager"
        private const val DISPLAY_NAME = "OpenCyvis-Agent"

        // Default virtual display size (compact to reduce rendering + LLM token cost)
        const val DEFAULT_WIDTH = 540
        const val DEFAULT_HEIGHT = 1170
        const val DEFAULT_DPI = 240

        // Hidden VirtualDisplay flags (not in public API)
        // VirtualDisplay flags: mFlags=120533
        private const val VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 shl 4            // 16
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6         // 64
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7   // 128
        private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9  // 512
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10              // 1024
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12      // 4096
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14            // 16384
        private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 15 // 32768
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 16 // 65536
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var readerHandlerThread: HandlerThread? = null
    private var readerHandler: Handler? = null

    private val cacheLock = Any()
    private var cachedBitmap: Bitmap? = null

    /**
     * The display ID of the virtual display, or -1 if not created.
     */
    val displayId: Int
        get() = virtualDisplay?.display?.displayId ?: -1

    val isCreated: Boolean
        get() = virtualDisplay != null

    val width: Int
        get() = imageReader?.width ?: DEFAULT_WIDTH

    val height: Int
        get() = imageReader?.height ?: DEFAULT_HEIGHT

    // ── Task reparenting API (hidden Android APIs via reflection) ────────

    private val activityTaskManager: Any? by lazy {
        try {
            Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ActivityTaskManager service", e)
            null
        }
    }

    private val moveTaskMethod: Method? by lazy {
        val atm = activityTaskManager ?: return@lazy null
        // Try method names in order of preference (varies by Android version)
        val methodNames = listOf("moveRootTaskToDisplay", "moveTaskToDisplay")
        for (name in methodNames) {
            try {
                return@lazy atm.javaClass.getMethod(
                    name,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            } catch (_: NoSuchMethodException) {
                continue
            }
        }
        Log.e(TAG, "No moveTaskToDisplay method found on ${atm.javaClass.name}")
        null
    }

    /**
     * Get the top non-OpenCyvis task ID running on the specified display.
     * Uses ActivityTaskManager.getTasks() via reflection.
     * Returns null if no suitable task is found.
     */
    fun getTopTaskIdOnDisplay(targetDisplayId: Int): Int? {
        return getTopTaskOnDisplay(targetDisplayId)?.taskId
    }

    fun getTopTaskOnDisplay(targetDisplayId: Int): TaskSnapshot? {
        return getRunningTasks(limit = 100)
            .filter { it.displayId == targetDisplayId }
            .firstOrNull { !isOpenCyvisTask(it) }
    }

    fun getTaskSnapshot(taskId: Int): TaskSnapshot? {
        return getRunningTasks(limit = 100).firstOrNull { it.taskId == taskId }
    }

    fun toTaskSnapshot(taskInfo: Any?): TaskSnapshot? {
        return taskInfoToSnapshot(taskInfo)
    }

    fun getRunningTasks(limit: Int = 50): List<TaskSnapshot> {
        val atm = activityTaskManager ?: return emptyList()
        return try {
            val tasks = try {
                val method = atm.javaClass.getMethod(
                    "getTasks",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                val result = mutableListOf<Any?>()
                val defaultTasks = method.invoke(
                    atm, limit, false, false, Display.DEFAULT_DISPLAY
                ) as? List<*>
                result.addAll(defaultTasks.orEmpty())
                val vdDisplayId = displayId
                if (vdDisplayId != -1 && vdDisplayId != Display.DEFAULT_DISPLAY) {
                    val vdTasks = method.invoke(atm, limit, false, false, vdDisplayId) as? List<*>
                    result.addAll(vdTasks.orEmpty())
                }
                result
            } catch (e: NoSuchMethodException) {
                try {
                    atm.javaClass.getMethod(
                        "getTasks",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    ).invoke(atm, limit, false, false) as? List<*>
                } catch (e2: NoSuchMethodException) {
                    // Fallback: getTasks without visibility flags.
                    Log.w(TAG, "getTasks extended forms not found, falling back to getTasks(int)")
                    atm.javaClass.getMethod(
                        "getTasks", Int::class.javaPrimitiveType
                    ).invoke(atm, limit) as? List<*>
                }
            }

            tasks.orEmpty().mapNotNull { taskInfoToSnapshot(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get running tasks", e)
            emptyList()
        }
    }

    private fun isOpenCyvisTask(task: TaskSnapshot): Boolean {
        return task.containsPackage(context.packageName)
    }

    private fun taskInfoToSnapshot(taskInfo: Any?): TaskSnapshot? {
        if (taskInfo == null) return null
        return try {
            val taskId = readIntField(taskInfo, "taskId") ?: return null
            val displayId = readIntField(taskInfo, "displayId") ?: -1
            val baseActivity = readComponent(taskInfo, "baseActivity")
                ?: readComponentFromGetter(taskInfo, "getBaseActivity")
            val topActivity = readComponent(taskInfo, "topActivity")
                ?: readComponentFromGetter(taskInfo, "getTopActivity")
            val lastActiveTime = readLongField(taskInfo, "lastActiveTime") ?: 0L
            TaskSnapshot(
                taskId = taskId,
                displayId = displayId,
                basePackage = baseActivity?.packageName,
                topPackage = topActivity?.packageName,
                lastActiveTime = lastActiveTime
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse task snapshot: ${e.message}")
            null
        }
    }

    private fun readComponent(taskInfo: Any, fieldName: String): ComponentName? {
        return try {
            readField(taskInfo, fieldName) as? ComponentName
        } catch (_: Exception) { null }
    }

    private fun readComponentFromGetter(taskInfo: Any, methodName: String): ComponentName? {
        return try {
            taskInfo.javaClass.getMethod(methodName).invoke(taskInfo) as? ComponentName
        } catch (_: Exception) {
            null
        }
    }

    private fun readIntField(target: Any, fieldName: String): Int? {
        return try {
            readField(target, fieldName) as? Int
        } catch (_: Exception) { null }
    }

    private fun readLongField(target: Any, fieldName: String): Long? {
        return try {
            readField(target, fieldName) as? Long
        } catch (_: Exception) { null }
    }

    private fun readField(target: Any, fieldName: String): Any? {
        val cls = target.javaClass
        return try {
            cls.getField(fieldName).get(target)
        } catch (_: NoSuchFieldException) {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)
        }
    }

    /**
     * Move a task to a different display using ActivityTaskManager.moveTaskToDisplay().
     * Returns true on success.
     */
    fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean {
        return try {
            moveTaskMethod?.invoke(activityTaskManager, taskId, targetDisplayId)
            Log.i(TAG, "Moved task $taskId to display $targetDisplayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "moveTaskToDisplay($taskId, $targetDisplayId) failed", e)
            false
        }
    }

    // ── IME policy ────────────────────────────────────────────────────

    /**
     * Set the display's IME policy to LOCAL so the soft keyboard renders
     * on the VD itself (visible through SurfaceView) rather than falling
     * back to Display 0.
     *
     * Bind IME directly to VD display.
     * DISPLAY_IME_POLICY_LOCAL = 0 (Android 13+)
     */
    private fun setDisplayImePolicy(dm: DisplayManager, displayId: Int) {
        // DISPLAY_IME_POLICY_LOCAL = 0: keyboard renders on this display
        val DISPLAY_IME_POLICY_LOCAL = 0

        // Try IWindowManager.setDisplayImePolicy() (Android 12+)
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as? android.os.IBinder ?: throw Exception("No window service")

            val stub = Class.forName("android.view.IWindowManager\$Stub")
            val wm = stub.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)

            wm!!.javaClass.getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(wm, displayId, DISPLAY_IME_POLICY_LOCAL)

            Log.i(TAG, "Set DISPLAY_IME_POLICY_LOCAL on display $displayId via IWindowManager")
            return
        } catch (e: Exception) {
            Log.w(TAG, "IWindowManager.setDisplayImePolicy failed: ${e.message}")
        }

        // Fallback: try DisplayManager.setDisplayImePolicy() (Android 14+)
        try {
            dm.javaClass.getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(dm, displayId, DISPLAY_IME_POLICY_LOCAL)
            Log.i(TAG, "Set DISPLAY_IME_POLICY_LOCAL on display $displayId via DisplayManager")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set display IME policy: ${e.message}")
        }
    }

    // ── Surface switching ──────────────────────────────────────────────

    /** True when the VD surface is set to SurfaceView (VIEW mode). */
    @Volatile
    var isUsingExternalSurface = false
        private set

    private var externalSurface: Surface? = null

    /**
     * Switch the VD's rendering target between SurfaceView and ImageReader.
     * Pass a SurfaceView's surface to render VD content on Display 0 (VIEW mode).
     * Pass null to switch back to ImageReader surface (BACKGROUND mode).
     *
     * ScreenCapture API (SurfaceFlinger) is unaffected — it captures directly
     * from the compositor regardless of which surface the VD renders to.
     */
    fun setSurface(surface: Surface?) {
        val vd = virtualDisplay ?: return
        if (surface != null) {
            externalSurface = surface
            isUsingExternalSurface = true
            vd.surface = surface
            Log.i(TAG, "VD surface switched to SurfaceView")
        } else {
            externalSurface = null
            isUsingExternalSurface = false
            vd.surface = imageReader?.surface
            Log.i(TAG, "VD surface switched back to ImageReader")
        }
    }

    /**
     * Capture a frame via ImageReader, temporarily switching the VD surface
     * from SurfaceView if needed. This bypasses SurfaceFlinger's FLAG_SECURE
     * blackout since the ImageReader is the VD's own rendering surface.
     *
     * Returns null if no frame is available within the timeout.
     */
    fun captureViaImageReader(timeoutMs: Long = 150): Bitmap? {
        val vd = virtualDisplay ?: return null
        val reader = imageReader ?: return null
        val wasExternal = isUsingExternalSurface
        val savedSurface = externalSurface

        try {
            if (wasExternal) {
                // Cache is stale (was populated before switching to SurfaceView) —
                // drop it so we wait for a fresh frame from the listener.
                val old: Bitmap?
                synchronized(cacheLock) {
                    old = cachedBitmap
                    cachedBitmap = null
                }
                old?.recycle()
                vd.surface = reader.surface
            }

            // Wait for a fresh frame
            val deadline = System.currentTimeMillis() + timeoutMs
            var bitmap: Bitmap? = captureLatestBitmap()
            while (bitmap == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(16) // ~1 frame at 60fps
                bitmap = captureLatestBitmap()
            }
            return bitmap
        } finally {
            if (wasExternal && savedSurface != null) {
                // Restore SurfaceView
                vd.surface = savedSurface
            }
        }
    }

    // ── VirtualDisplay lifecycle ─────────────────────────────────────────

    /**
     * Create the virtual display with an ImageReader surface.
     * Returns the displayId or -1 on failure.
     */
    fun create(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI
    ): Int {
        if (virtualDisplay != null) {
            Log.w(TAG, "Virtual display already created (displayId=$displayId)")
            return displayId
        }

        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            // ImageReader: 8 buffers, RGBA_8888 for direct Bitmap conversion.
            // Large buffer count prevents stall between agent steps — frames produced
            // between captures won't cause the producer to block.
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 8)

            // Drain frames as they arrive on a background thread and cache the latest
            // bitmap. acquireLatestImage() only returns NEW frames since last acquire,
            // so on a static screen subsequent calls return null. The cache lets capture
            // return the most recent frame even when the producer is idle.
            readerHandlerThread = HandlerThread("VDImageReader").also { it.start() }
            readerHandler = Handler(readerHandlerThread!!.looper)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    Log.w(TAG, "acquireLatestImage in listener failed: ${e.message}")
                    null
                } ?: return@setOnImageAvailableListener
                val bmp = try {
                    imageToBitmap(image)
                } finally {
                    image.close()
                }
                if (bmp != null) {
                    val old: Bitmap?
                    synchronized(cacheLock) {
                        old = cachedBitmap
                        cachedBitmap = bmp
                    }
                    old?.recycle()
                }
            }, readerHandler)

            // VD config flags (mFlags=120533)
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE or
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                    VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
                    VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or
                    VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                    VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                    VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED or
                    VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED

            virtualDisplay = dm.createVirtualDisplay(
                DISPLAY_NAME, width, height, dpi,
                imageReader!!.surface, flags
            )

            val id = virtualDisplay?.display?.displayId ?: -1
            if (id == -1) {
                Log.e(TAG, "createVirtualDisplay returned null display")
                destroy()
                return -1
            }

            Log.i(TAG, "Virtual display created: displayId=$id, ${width}x${height} @${dpi}dpi")

            // Set IME policy to LOCAL so the keyboard renders inside the VD
            // (visible through SurfaceView in VIEW/TAKEOVER mode).
            // Without this, keyboard appears on Display 0 and input doesn't reach the VD.
            setDisplayImePolicy(dm, id)

            return id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display", e)
            destroy()
            return -1
        }
    }

    /**
     * Return a copy of the most recent frame captured by the OnImageAvailableListener.
     * Returns null if no frame has been received yet (e.g. immediately after create()
     * or right after switching back from an external surface).
     */
    fun captureLatestBitmap(): Bitmap? {
        synchronized(cacheLock) {
            val src = cachedBitmap ?: return null
            if (src.isRecycled) return null
            val cfg = src.config ?: Bitmap.Config.ARGB_8888
            return src.copy(cfg, false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        try {
            val hardwareBuffer = image.hardwareBuffer
            if (hardwareBuffer != null) {
                try {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                        hardwareBuffer,
                        ColorSpace.get(ColorSpace.Named.SRGB)
                    )
                    val copy = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap?.recycle()
                    if (copy != null) return copy
                } finally {
                    hardwareBuffer.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HardwareBuffer image copy failed, falling back: ${e.message}")
        }

        return imageToBitmapViaHeapCopy(image)
    }

    private fun imageToBitmapViaHeapCopy(image: Image): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            if (rowStride <= 0 || pixelStride <= 0) {
                Log.w(TAG, "Invalid image plane stride: rowStride=$rowStride pixelStride=$pixelStride")
                return null
            }
            if (pixelStride != 4 || rowStride % pixelStride != 0) {
                Log.w(TAG, "Unsupported image plane layout: rowStride=$rowStride pixelStride=$pixelStride")
                return null
            }

            val strideWidth = rowStride / pixelStride
            if (strideWidth < image.width) {
                Log.w(TAG, "Image stride narrower than width: strideWidth=$strideWidth width=${image.width}")
                return null
            }

            val byteCount = rowStride * image.height
            val src = plane.buffer.duplicate()
            src.rewind()
            if (src.remaining() < byteCount) {
                Log.w(TAG, "Image buffer too small: remaining=${src.remaining()} expected=$byteCount")
                return null
            }

            val bitmap = Bitmap.createBitmap(
                strideWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            val bytes = ByteArray(byteCount)
            src.get(bytes)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

            // Crop if there's row padding
            if (strideWidth > image.width) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert Image to Bitmap via heap copy", e)
            null
        }
    }

    /**
     * Destroy the virtual display and release resources.
     */
    fun destroy() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        readerHandlerThread?.quitSafely()
        readerHandlerThread = null
        readerHandler = null
        synchronized(cacheLock) {
            cachedBitmap?.recycle()
            cachedBitmap = null
        }
        Log.i(TAG, "Virtual display destroyed")
    }
}
