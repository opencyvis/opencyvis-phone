package ai.opencyvis.engine

import android.graphics.Bitmap
import android.util.Log
import ai.opencyvis.action.Action
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.db.GlobalMemoryEntity
import ai.opencyvis.db.GlobalMemoryRepository
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LLMException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Core agent engine: observe-think-act loop.
 * Ported from cli_demo.py run_instruction() and run_auto().
 */
class AgentEngine(
    private val llmClient: LLMClientInterface,
    private val actionExecutor: ActionExecutor,
    private val maxSteps: Int = 100,
    private val virtualDisplayManager: VirtualDisplayManager? = null,
    private val debugMode: Boolean = false,
    private val memoryRepository: GlobalMemoryRepository? = null,
    private val viewTreeProvider: ((Int, Int, Int) -> String?)? = null
) {

    companion object {
        private const val TAG = "AgentEngine"
        val SIDE_EFFECT_ACTIONS = setOf("tap", "swipe", "type_text", "key_event", "open_app", "long_press")
        private const val MAX_HISTORY_MESSAGES = 100
        private const val VD_CAPTURE_MAX_ATTEMPTS = 8
        private const val VD_CAPTURE_RETRY_DELAY_MS = 300L
        private const val VD_IMAGE_READER_TIMEOUT_MS = 600L

        /**
         * System prompt. Ported from cli_demo.py SYSTEM_PROMPT (lines 286-302).
         */
        val SYSTEM_PROMPT: String get() = LlmPrompts.systemPrompt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var agentJob: Job? = null
    private val actionRepeatGuard = ActionRepeatGuard()

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _stepResults = MutableSharedFlow<StepResult>(replay = 0, extraBufferCapacity = 20)
    val stepResults: SharedFlow<StepResult> = _stepResults.asSharedFlow()

    @Volatile
    private var paused = false

    @Volatile
    private var userResponseDeferred: CompletableDeferred<String?>? = null

    @Volatile
    private var userHandoffDeferred: CompletableDeferred<String?>? = null

    private val pendingUserSupplements = mutableListOf<String>()

    /**
     * Test-only: jump the engine straight into WaitingForUser state without running the LLM loop.
     * Used by ADB test broadcasts to verify ask_user UI / overlay / heads-up plumbing.
     */
    fun debugSimulateAskUser(question: String) {
        Log.i(TAG, "debugSimulateAskUser: $question")
        _state.value = AgentState.WaitingForUser(question, 1)
    }

    fun debugSimulateHandoff(reason: String) {
        Log.i(TAG, "debugSimulateHandoff: $reason")
        _state.value = AgentState.WaitingForHandoff(reason, 1)
    }

    fun debugSimulateRunning(step: Int = 1, thought: String = "debug running") {
        Log.i(TAG, "debugSimulateRunning: step=$step thought=$thought")
        paused = false
        _state.value = AgentState.Running(step, thought)
    }

    /**
     * Start the agent with the given instruction.
     */
    fun start(instruction: String) {
        if (agentJob?.isActive == true) {
            Log.w(TAG, "Agent already running")
            return
        }
        paused = false
        agentJob = scope.launch {
            runAgentLoop(instruction)
        }
    }

    /**
     * Pause the agent loop.
     */
    fun pause() {
        paused = true
        _state.value = AgentState.Paused
    }

    /**
     * Resume the agent loop.
     */
    fun resume() {
        paused = false
        // State will be updated in the loop
    }

    /**
     * Submit user response when engine is in WaitingForUser state.
     */
    fun submitUserResponse(response: String) {
        userResponseDeferred?.complete(response)
    }

    fun completeUserHandoff(source: String = "user_returned_control") {
        userHandoffDeferred?.complete(source)
    }

    fun submitUserSupplement(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        synchronized(pendingUserSupplements) {
            pendingUserSupplements.add(trimmed)
        }
        Log.i(TAG, "submitUserSupplement queued (${trimmed.length} chars)")
    }

    /**
     * Stop the agent.
     */
    fun stop() {
        paused = false
        userResponseDeferred?.complete(null)
        userResponseDeferred = null
        userHandoffDeferred?.complete(null)
        userHandoffDeferred = null
        agentJob?.cancel()
        agentJob = null
        _state.value = AgentState.Idle()
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stop()
        llmClient.shutdown()
    }

    /**
     * Main observe-think-act loop. Ported from cli_demo.py run_instruction().
     */
    private suspend fun runAgentLoop(instruction: String) {
        val messages = mutableListOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT)
        )
        val notes = mutableMapOf<String, String>()
        var pendingUserAnswer: String? = null
        var pendingActionFeedback: String? = null
        var prevViewTree: String? = null
        var prevActionType: String? = null

        try {
            // Wait for virtual display to render its first frame
            if (virtualDisplayManager != null) {
                delay(500)
            }

            for (step in 1..maxSteps) {
                // Check for pause
                while (paused) {
                    delay(200)
                    if (!scope.isActive) return
                }

                val stepStartTime = System.currentTimeMillis()
                _state.value = AgentState.Running(step, "Capturing screenshot...")

                // === OBSERVE ===
                val t0 = System.currentTimeMillis()
                val vdm = virtualDisplayManager
                val screenshotBase64: String? = if (vdm != null) {
                    captureVirtualDisplay(vdm, step)
                } else {
                    Log.w(TAG, "No VirtualDisplayManager — capturing physical screen")
                    ScreenCapture.captureBase64()
                }
                val captureMs = System.currentTimeMillis() - t0

                // Debug: save screenshot to file for inspection
                if (debugMode && screenshotBase64 != null) {
                    try {
                        val bytes = android.util.Base64.decode(screenshotBase64, android.util.Base64.DEFAULT)
                        val file = java.io.File("/sdcard/opencyvis_step_${step}.jpg")
                        file.writeBytes(bytes)
                        Log.i(TAG, "Debug screenshot saved to ${file.absolutePath} (${bytes.size} bytes)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to save debug screenshot", e)
                    }
                }

                if (screenshotBase64 == null) {
                    _state.value = AgentState.Error("Failed to capture screenshot")
                    _stepResults.emit(
                        StepResult(step, "error", "Screenshot failed", false,
                            "Failed to capture screenshot", 0, true)
                    )
                    return
                }
                val screenFingerprint = ScreenFingerprint.fromBase64(screenshotBase64)

                // === VIEW TREE ===
                val viewTree: String? = if (vdm != null && viewTreeProvider != null) {
                    try {
                        viewTreeProvider.invoke(vdm.displayId, vdm.width, vdm.height)
                    } catch (e: Exception) {
                        Log.w(TAG, "ViewTree capture failed", e)
                        null
                    }
                } else null
                if (viewTree != null) {
                    Log.i(TAG, "Step $step ViewTree: ${viewTree.length} chars, ${viewTree.lines().size} nodes")
                }

                // Detect unchanged screen after side-effect action
                if (step > 1 && prevActionType in SIDE_EFFECT_ACTIONS &&
                    prevViewTree != null && viewTree != null &&
                    prevViewTree == viewTree && pendingActionFeedback == null) {
                    pendingActionFeedback = String.format(
                        LlmPrompts.agentFeedback("screen_unchanged"), prevActionType
                    )
                    Log.w(TAG, "Step $step: screen unchanged after $prevActionType action")
                }

                // Build user message — on step 1, hint that the VD may be blank
                val t1 = System.currentTimeMillis()
                val effectiveInstruction = if (step == 1 && vdm != null) {
                    "$instruction\n${LlmPrompts.agentFeedback("vd_blank_hint")}"
                } else {
                    instruction
                }
                val userSupplements = drainUserSupplements()
                val globalMemories = memoryRepository?.getEnabled().orEmpty()
                val userMsg = buildUserMessage(
                    screenshotBase64 = screenshotBase64,
                    instruction = effectiveInstruction,
                    notes = notes,
                    userAnswer = pendingUserAnswer,
                    actionFeedback = pendingActionFeedback,
                    userSupplements = userSupplements,
                    globalMemories = globalMemories,
                    viewTree = viewTree
                )
                pendingUserAnswer = null
                pendingActionFeedback = null
                messages.add(userMsg)

                // Strip images from older messages
                stripImagesFromHistory(messages)
                val encodeMs = System.currentTimeMillis() - t1

                // === THINK ===
                _state.value = AgentState.Running(step, "Thinking...")
                val t2 = System.currentTimeMillis()

                val resultData: Map<String, Any?>
                try {
                    resultData = llmClient.chatWithTools(messages)
                } catch (e: LLMException) {
                    val llmMs = System.currentTimeMillis() - t2
                    Log.w(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms (ERROR)")
                    _state.value = AgentState.Error("LLM error: ${e.message}")
                    _stepResults.emit(
                        StepResult(step, "error", e.message ?: "LLM error", false,
                            "LLM API error: ${e.message}", llmMs, true)
                    )
                    return
                }
                val llmMs = System.currentTimeMillis() - t2

                Log.i(TAG, "Step $step LLM response: thought=${resultData["thought"]}, action_type=${resultData["action_type"]}, completed=${resultData["completed"]}, reason=${resultData["reason"]}, question=${resultData["question"]}")
                val debugInfo = if (debugMode) formatDebugInfo(resultData) else null

                val thought = resultData["thought"] as? String ?: ""
                // Infer action_type when the model omits it but includes action-specific fields
                val rawActionType = resultData["action_type"] as? String
                val actionType = when {
                    !rawActionType.isNullOrBlank() -> rawActionType
                    (resultData["question"] as? String).isNullOrBlank().not() -> "ask_user"
                    (resultData["handoff_reason"] as? String).isNullOrBlank().not() -> "handoff_user"
                    (resultData["reason"] as? String).isNullOrBlank().not() -> "fail"
                    else -> "unknown"
                }
                if (rawActionType.isNullOrBlank()) {
                    Log.w(TAG, "Step $step: action_type missing, inferred '$actionType'. resultData=$resultData")
                } else if (actionType == "unknown") {
                    Log.w(TAG, "Step $step: LLM returned unrecognized action_type. resultData=$resultData")
                }
                val completed = when (val raw = resultData["completed"]) {
                    is Boolean -> raw
                    is String -> raw.equals("true", ignoreCase = true)
                    else -> false  // default to NOT completed — let the agent keep going
                }

                _state.value = AgentState.Running(step, thought)

                // Append assistant response to history
                val resultJson = JSONObject(resultData.mapValues { it.value }).toString()
                messages.add(mapOf("role" to "assistant", "content" to resultJson))

                // Check for terminal actions
                if (actionType == "finish" || actionType == "fail") {
                    val detail = if (actionType == "finish") {
                        "Task finished: $thought"
                    } else {
                        "Task failed: ${resultData["reason"] ?: thought}"
                    }
                    val totalMs = System.currentTimeMillis() - stepStartTime
                    Log.i(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms action=0ms total=${totalMs}ms [$actionType]")
                    Log.i(TAG, "Task completed at step $step [$actionType]: ${detail.take(100)}")
                    _stepResults.emit(
                        StepResult(step, actionType, thought, actionType == "finish",
                            detail, totalMs, true, debugInfo)
                    )
                    _state.value = if (actionType == "finish") {
                        AgentState.Idle(resultMessage = thought)
                    } else {
                        AgentState.Error(resultData["reason"] as? String ?: thought)
                    }
                    return
                }

                // Handle ask_user: suspend until user answers
                if (actionType == "ask_user") {
                    val question = resultData["question"] as? String ?: thought
                    val totalMsAsk = System.currentTimeMillis() - stepStartTime
                    Log.i(TAG, "WaitingForUser(question=$question, step=$step)")
                    _state.value = AgentState.WaitingForUser(question, step)
                    _stepResults.emit(StepResult(step, "ask_user", thought, true, "Asking: $question", totalMsAsk, false))

                    val deferred = CompletableDeferred<String?>()
                    userResponseDeferred = deferred
                    val userAnswer = deferred.await()
                    userResponseDeferred = null

                    if (userAnswer == null) {
                        _state.value = AgentState.Idle()
                        return
                    }

                    // Store answer to be merged into the next user message (with screenshot).
                    // Avoids two consecutive user messages which confuses the LLM.
                    pendingUserAnswer = userAnswer
                    continue  // go to next loop iteration to take screenshot
                }

                // Handle handoff_user: suspend while user enters sensitive data directly on device.
                if (actionType == "handoff_user") {
                    val reason = resultData["handoff_reason"] as? String
                        ?: resultData["reason"] as? String
                        ?: thought.ifBlank { LlmPrompts.agentFeedback("handoff_default_reason") }
                    val totalMsHandoff = System.currentTimeMillis() - stepStartTime
                    Log.i(TAG, "WaitingForHandoff(reason=$reason, step=$step)")
                    _state.value = AgentState.WaitingForHandoff(reason, step)
                    _stepResults.emit(
                        StepResult(
                            step,
                            "handoff_user",
                            thought,
                            true,
                            "Handing off to user: $reason",
                            totalMsHandoff,
                            false,
                            debugInfo
                        )
                    )

                    val deferred = CompletableDeferred<String?>()
                    userHandoffDeferred = deferred
                    val handoffSource = deferred.await()
                    userHandoffDeferred = null

                    if (handoffSource == null) {
                        _state.value = AgentState.Idle()
                        return
                    }

                    pendingActionFeedback = String.format(LlmPrompts.agentFeedback("handoff_completed"), handoffSource)
                    continue
                }

                // === NOTE (side-effect) ===
                // Any action can carry an optional "note" field to record information
                val noteValue = resultData["note"] as? String
                if (!noteValue.isNullOrBlank()) {
                    val (noteKey, noteVal) = parseNote(noteValue)
                    // Enforce max 10 notes — evict oldest when full
                    if (notes.size >= 10 && !notes.containsKey(noteKey)) {
                        val oldest = notes.keys.first()
                        notes.remove(oldest)
                    }
                    notes[noteKey] = noteVal
                    Log.i(TAG, "Step $step note recorded: $noteKey = $noteVal (total: ${notes.size})")
                }

                val memoryKey = resultData["memory_key"] as? String
                val memoryValue = resultData["memory_value"] as? String
                if (!memoryKey.isNullOrBlank() && !memoryValue.isNullOrBlank()) {
                    val memoryCategory = resultData["memory_category"] as? String ?: ""
                    memoryRepository?.upsert(
                        key = memoryKey,
                        value = memoryValue,
                        category = memoryCategory,
                        source = GlobalMemoryEntity.SOURCE_AI
                    )
                    Log.i(TAG, "Step $step global memory recorded: $memoryKey (${memoryCategory.ifBlank { "uncategorized" }})")
                }

                // Handle standalone note action: record and continue to next observe cycle
                if (actionType == "note") {
                    val totalMsNote = System.currentTimeMillis() - stepStartTime
                    Log.i(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms [note]")
                    _stepResults.emit(StepResult(step, "note", thought, true,
                        "Note: ${noteValue ?: thought}", totalMsNote, false, debugInfo))
                    delay(200) // brief delay before next observe
                    continue
                }

                if (actionType == "remember") {
                    val totalMsRemember = System.currentTimeMillis() - stepStartTime
                    _stepResults.emit(
                        StepResult(
                            step,
                            "remember",
                            thought,
                            true,
                            "Remembered: ${memoryKey ?: thought}",
                            totalMsRemember,
                            false,
                            debugInfo
                        )
                    )
                    delay(200)
                    continue
                }

                // === ACT ===
                val t3 = System.currentTimeMillis()
                val action = Action.fromMap(resultData)
                _state.value = AgentState.Running(step, "Executing: $actionType")

                when (val repeatDecision = actionRepeatGuard.evaluate(action, screenFingerprint)) {
                    is ActionRepeatGuard.Decision.Block -> {
                        val totalMs = System.currentTimeMillis() - stepStartTime
                        pendingActionFeedback = repeatDecision.feedback
                        Log.w(TAG, "Step $step action blocked by repeat guard: ${repeatDecision.feedback}")
                        _stepResults.emit(
                            StepResult(
                                step = step,
                                actionType = action.typeName,
                                thought = thought,
                                success = false,
                                detail = repeatDecision.feedback,
                                durationMs = totalMs,
                                completed = false,
                                debugInfo = debugInfo
                            )
                        )
                        delay(300)
                        continue
                    }
                    ActionRepeatGuard.Decision.Allow -> Unit
                }

                val stepResult = actionExecutor.execute(action, step)
                if (stepResult.success) {
                    actionRepeatGuard.recordExecuted(action, screenFingerprint)
                }

                // Feed action failure back to LLM in the next iteration
                if (!stepResult.success) {
                    pendingActionFeedback = String.format(LlmPrompts.agentFeedback("action_failed"), stepResult.detail)
                    Log.w(TAG, "Step $step action failed, will feed back to LLM: ${stepResult.detail}")
                }

                val actionMs = System.currentTimeMillis() - t3

                val totalMs = System.currentTimeMillis() - stepStartTime

                // Override completed from LLM response
                val finalResult = stepResult.copy(
                    completed = completed,
                    durationMs = totalMs,
                    debugInfo = debugInfo
                )
                _stepResults.emit(finalResult)

                Log.i(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms action=${actionMs}ms total=${totalMs}ms [$actionType]")

                if (completed) {
                    if (actionType in SIDE_EFFECT_ACTIONS) {
                        Log.i(TAG, "Step $step: completed=true with side-effect action [$actionType], continuing one more step to verify")
                        pendingActionFeedback = String.format(LlmPrompts.agentFeedback("completed_side_effect"), actionType)
                    } else {
                        Log.i(TAG, "Task completed at step $step [completed]: $thought")
                        _state.value = AgentState.Idle(resultMessage = thought)
                        return
                    }
                }

                // Wait for screen update before next step
                prevViewTree = viewTree
                prevActionType = actionType
                delay(1000)

                // Trim conversation history
                if (messages.size > MAX_HISTORY_MESSAGES) {
                    val systemMsg = messages[0]
                    val recent = messages.takeLast(MAX_HISTORY_MESSAGES - 1)
                    messages.clear()
                    messages.add(systemMsg)
                    messages.addAll(recent)
                }
            }

            // Max steps reached
            Log.w(TAG, "max_steps reached at step $maxSteps, stopping agent")
            _state.value = AgentState.Idle(resultMessage = String.format(LlmPrompts.agentFeedback("max_steps_reached"), maxSteps))
            _stepResults.emit(
                StepResult(maxSteps, "max_steps", "Reached max steps ($maxSteps)",
                    false, "Max steps reached", 0, true)
            )

        } catch (e: CancellationException) {
            Log.i(TAG, "Agent loop cancelled")
            _state.value = AgentState.Idle()
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop error", e)
            _state.value = AgentState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Capture a frame from the virtual display.
     *
     * Strategy:
     * 1. ScreenCapture API (SurfaceFlinger, API 36+) — fast, works for most windows
     * 2. If ScreenCapture returns null (API <36) or all-black (FLAG_SECURE),
     *    fall back to ImageReader capture which reads directly from the VD's
     *    own surface and works on all API levels.
     */
    private suspend fun captureVirtualDisplay(vdm: VirtualDisplayManager, step: Int): String? {
        // Activity and ad-window transitions can briefly leave the VD without a
        // readable frame. Retry before treating capture as a task-ending error.
        for (attempt in 1..VD_CAPTURE_MAX_ATTEMPTS) {
            val bitmap = ScreenCapture.captureBitmap(displayId = vdm.displayId)
            if (bitmap != null) {
                if (!isBitmapBlack(bitmap)) {
                    // Normal frame — use it
                    return ScreenCapture.captureBase64(virtualDisplayBitmap = bitmap)
                }
                // Frame is all-black — likely FLAG_SECURE window
                bitmap.recycle()
                Log.w(TAG, "ScreenCapture returned black frame (FLAG_SECURE?), trying ImageReader fallback")
            } else {
                // ScreenCapture API unavailable (API <36) — use ImageReader
                Log.d(TAG, "ScreenCapture returned null for VD, trying ImageReader fallback")
            }

            // ImageReader fallback — works on all API levels
            val irBitmap = withContext(Dispatchers.IO) {
                vdm.captureViaImageReader(timeoutMs = VD_IMAGE_READER_TIMEOUT_MS)
            }
            if (irBitmap != null) {
                Log.i(TAG, "ImageReader fallback succeeded for virtual display")
                return ScreenCapture.captureBase64(virtualDisplayBitmap = irBitmap)
            }
            Log.w(TAG, "ImageReader fallback also returned null")

            if (attempt < VD_CAPTURE_MAX_ATTEMPTS) {
                Log.d(TAG, "VD capture failed at step $step (attempt $attempt/$VD_CAPTURE_MAX_ATTEMPTS), waiting ${VD_CAPTURE_RETRY_DELAY_MS}ms...")
                delay(VD_CAPTURE_RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "VD capture failed at step $step after $VD_CAPTURE_MAX_ATTEMPTS attempts")
        return null
    }

    private fun formatDebugInfo(resultData: Map<String, Any?>): String {
        return try {
            JSONObject(resultData).toString(2).take(2000)
        } catch (_: Exception) {
            resultData.toString().take(2000)
        }
    }

    /**
     * Quick check if a bitmap is all-black (or nearly so).
     * Samples a grid of pixels rather than checking every pixel.
     */
    private fun isBitmapBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return true
        val sampleCount = 20
        for (i in 0 until sampleCount) {
            val x = (w * (i + 1)) / (sampleCount + 1)
            val y = (h * (i + 1)) / (sampleCount + 1)
            val pixel = bitmap.getPixel(x, y)
            // Check if pixel has any non-black color (ignore alpha)
            if ((pixel and 0x00FFFFFF) != 0) return false
        }
        return true
    }

    /**
     * Build user message with screenshot in vision-LLM format.
     * If userAnswer is provided, it is prepended to the instruction text so the LLM
     * sees the user's reply and the current screenshot in a single message — avoiding
     * two consecutive user messages which can confuse the model.
     */
    /**
     * Parse a note string into key-value pair.
     * Format: "key: value" → Pair(key, value). If no colon, uses the full string as both.
     */
    private fun parseNote(note: String): Pair<String, String> {
        val colonIndex = note.indexOf(':')
        return if (colonIndex > 0) {
            val key = note.substring(0, colonIndex).trim()
            val value = note.substring(colonIndex + 1).trim()
            key to value
        } else {
            note.trim() to note.trim()
        }
    }

    private fun drainUserSupplements(): List<String> {
        synchronized(pendingUserSupplements) {
            if (pendingUserSupplements.isEmpty()) return emptyList()
            val drained = pendingUserSupplements.toList()
            pendingUserSupplements.clear()
            return drained
        }
    }

    private fun buildUserMessage(
        screenshotBase64: String,
        instruction: String,
        notes: Map<String, String> = emptyMap(),
        userAnswer: String? = null,
        actionFeedback: String? = null,
        userSupplements: List<String> = emptyList(),
        globalMemories: List<GlobalMemoryEntity> = emptyList(),
        viewTree: String? = null
    ): Map<String, Any> {
        var text = instruction
        if (userAnswer != null) {
            text = String.format(LlmPrompts.agentFeedback("user_answer_prefix"), userAnswer) + text
        }
        if (actionFeedback != null) {
            text = String.format(LlmPrompts.agentFeedback("system_feedback_prefix"), actionFeedback) + text
        }
        if (!viewTree.isNullOrBlank()) {
            text = "$text${LlmPrompts.agentFeedback("ui_elements_header")}$viewTree"
        }
        if (userSupplements.isNotEmpty()) {
            val supplementBlock = userSupplements.joinToString("\n") { "- $it" }
            text = "$text${LlmPrompts.agentFeedback("user_supplement_header")}$supplementBlock"
        }
        if (globalMemories.isNotEmpty()) {
            val memoryBlock = globalMemories.joinToString("\n") {
                val category = if (it.category.isBlank()) "" else "[${it.category}] "
                "- $category${it.key}: ${it.value}"
            }
            text = "$text${LlmPrompts.agentFeedback("global_memory_header")}$memoryBlock"
        }
        if (notes.isNotEmpty()) {
            val notesBlock = notes.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
            text = "$text${LlmPrompts.agentFeedback("notes_header")}$notesBlock"
        }
        val content = listOf(
            mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:image/jpeg;base64,$screenshotBase64")
            ),
            mapOf(
                "type" to "text",
                "text" to text
            )
        )
        return mapOf("role" to "user", "content" to content)
    }

    /**
     * Remove base64 images from older messages to reduce token usage.
     * Only keeps the image in the very last user message.
     * Ported from cli_demo.py _strip_images_from_history().
     */
    private fun stripImagesFromHistory(messages: MutableList<Map<String, Any>>) {
        if (messages.size < 2) return

        for (i in 0 until messages.size - 1) {
            val msg = messages[i]
            val content = msg["content"]
            if (content is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val contentList = content as List<Map<String, Any>>
                val filtered = contentList.filter { it["type"] != "image_url" }

                val newMsg = msg.toMutableMap()
                if (filtered.size == 1 && filtered[0]["type"] == "text") {
                    // Simplify to plain string
                    newMsg["content"] = filtered[0]["text"] as? String ?: ""
                } else {
                    newMsg["content"] = filtered
                }
                messages[i] = newMsg
            }
        }
    }
}
