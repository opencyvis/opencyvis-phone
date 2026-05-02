package ai.opencyvis.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.AgentService
import ai.opencyvis.R
import ai.opencyvis.SettingsActivity
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.engine.AgentState
import ai.opencyvis.voice.SherpaOnnxSpeechInputEngine
import ai.opencyvis.voice.VoiceInputController
import ai.opencyvis.voice.VoiceInputTestBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Main control panel activity — chat-style interface for instructions and agent status.
 * "View" button launches ViewActivity with SurfaceView rendering VD content.
 */
class ControlPanelActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ControlPanel"
        private const val REQUEST_RECORD_AUDIO = 2001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agentService: AgentService? = null
    private var bound = false
    private var stateCollectionJob: Job? = null
    private var stepResultCollectionJob: Job? = null
    private var engineSubscriptionJob: Job? = null
    private lateinit var historyRepo: ChatHistoryRepository
    private lateinit var config: ConfigRepository

    // Views
    private lateinit var statusText: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var inputBar: LinearLayout
    private lateinit var actionButtons: LinearLayout
    private lateinit var editInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnVoiceInput: Button
    private lateinit var btnViewOp: Button
    private lateinit var btnStop: Button
    private lateinit var recentTasksContainer: LinearLayout
    private lateinit var recentTasksRecycler: RecyclerView
    private lateinit var recentTasksAdapter: ConversationHistoryAdapter
    private lateinit var voiceInputController: VoiceInputController
    private var voiceTestReceiverRegistered = false
    private var demoReceiverRegistered = false

    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra("demo") ?: return
            val delay = intent.getLongExtra("char_delay", 50L)
            Log.i(TAG, "Demo broadcast: typing '$text' then sending")
            editInput.text.clear()
            editInput.requestFocus()
            var index = 0
            val handler = editInput.handler ?: return
            val typeRunnable = object : Runnable {
                override fun run() {
                    if (index < text.length) {
                        editInput.append(text[index].toString())
                        index++
                        handler.postDelayed(this, delay)
                    } else {
                        handler.postDelayed({ btnSend.performClick() }, 500L)
                    }
                }
            }
            handler.postDelayed(typeRunnable, 300L)
        }
    }

    private val voiceTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val target = intent.getStringExtra(VoiceInputTestBridge.EXTRA_TARGET)
            if (target != VoiceInputTestBridge.TARGET_COMMAND &&
                target != VoiceInputTestBridge.TARGET_CONTROL_ANSWER
            ) return
            val result = intent.getStringExtra(VoiceInputTestBridge.EXTRA_RESULT) ?: return
            voiceInputController.injectFinalResult(result)
            Log.i(TAG, "Voice input injected target=$target text=$result")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AgentService.AgentBinder)?.getService()
            agentService = service
            bound = true
            Log.i(TAG, "Bound to AgentService")
            subscribeToEngineFlow()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            engineSubscriptionJob?.cancel()
            stateCollectionJob?.cancel()
            stepResultCollectionJob?.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control_panel)
        historyRepo = ChatHistoryRepository(this)
        config = ConfigRepository(this)

        // Bind views
        statusText = findViewById(R.id.text_status)
        chatRecycler = findViewById(R.id.recycler_chat)
        inputBar = findViewById(R.id.input_bar)
        actionButtons = findViewById(R.id.action_buttons)
        editInput = findViewById(R.id.edit_input)
        btnSend = findViewById(R.id.btn_send)
        btnVoiceInput = findViewById(R.id.btn_voice_input)
        btnViewOp = findViewById(R.id.btn_view_operation)
        btnStop = findViewById(R.id.btn_stop)

        voiceInputController = VoiceInputController(
            SherpaOnnxSpeechInputEngine(this),
            editTextTarget(editInput),
            object : VoiceInputController.Listener {
                override fun onListeningChanged(isListening: Boolean) {
                    btnVoiceInput.text = if (isListening) "■" else "🎙"
                    btnVoiceInput.isSelected = isListening
                }

                override fun onError(message: String) {
                    Toast.makeText(this@ControlPanelActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        )
        registerVoiceTestReceiver()

        // Chat setup
        chatAdapter = ChatAdapter()
        chatRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecycler.adapter = chatAdapter

        // Send button — start agent or submit answer
        btnSend.setOnClickListener {
            val text = editInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            val service = agentService
            if (service == null) {
                Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentState = service.stateFlow?.value
            when (currentState) {
                is AgentState.WaitingForUser -> {
                    chatAdapter.addMessage(ChatMessage(MessageType.USER_ANSWER, text))
                    service.submitUserResponse(text)
                    editInput.text.clear()
                    editInput.hint = getString(R.string.supplement_hint)
                }
                is AgentState.Running, is AgentState.Paused, is AgentState.WaitingForHandoff -> {
                    chatAdapter.addMessage(ChatMessage(MessageType.USER_SUPPLEMENT, getString(R.string.supplement_prefix, text)))
                    service.submitUserSupplement(text)
                    editInput.text.clear()
                    editInput.hint = getString(R.string.supplement_hint)
                }
                else -> {
                    chatAdapter.addMessage(ChatMessage(MessageType.USER_INPUT, text))
                    ensureServiceStarted()
                    service.startAgent(text)
                    editInput.text.clear()
                    actionButtons.visibility = View.VISIBLE
                    hideRecentTasks()
                }
            }
            scrollChatToBottom()
        }

        btnVoiceInput.setOnClickListener {
            if (voiceInputController.isListening) {
                voiceInputController.stop()
            } else if (ensureRecordAudioPermission()) {
                voiceInputController.start()
            }
        }

        // History button
        findViewById<Button>(R.id.btn_minimize).setOnClickListener {
            // Send OpenCyvis to background — OverlayService will show the floating pill.
            moveTaskToBack(true)
        }

        findViewById<Button>(R.id.btn_history).setOnClickListener {
            startActivity(Intent(this, ConversationHistoryActivity::class.java))
        }

        findViewById<Button>(R.id.btn_memory).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        // Settings button
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Recent tasks (idle state)
        recentTasksContainer = findViewById(R.id.recent_tasks_container)
        recentTasksRecycler = findViewById(R.id.recycler_recent_tasks)
        recentTasksAdapter = ConversationHistoryAdapter(
            onClick = { conv -> openConversationDetail(conv) },
            onLongClick = { }  // no long-press delete on main screen
        )
        recentTasksRecycler.layoutManager = LinearLayoutManager(this)
        recentTasksRecycler.adapter = recentTasksAdapter

        findViewById<TextView>(R.id.btn_view_all_history).setOnClickListener {
            startActivity(Intent(this, ConversationHistoryActivity::class.java))
        }

        // View/Resume button — context-dependent
        btnViewOp.setOnClickListener {
            val service = agentService ?: return@setOnClickListener
            val currentState = service.stateFlow?.value
            if (currentState is AgentState.Paused) {
                // Resume the paused agent
                service.resumeAgent()
            } else {
                // View operation — launch ViewActivity with SurfaceView
                service.enterViewMode()
            }
        }

        // Stop agent
        btnStop.setOnClickListener { stopAgent() }

        // Start and bind AgentService
        ensureServiceStarted()

        // Show ready message only if no recent history (otherwise idle state shows recent tasks)
        scope.launch {
            val recent = historyRepo.getRecentConversations(1)
            if (recent.isEmpty()) {
                chatAdapter.addMessage(ChatMessage(MessageType.SYSTEM, "Ready. Enter an instruction to start."))
            } else {
                showRecentTasks()
            }
        }

        // Start OverlayService so the floating pill is available when the user
        // backgrounds OpenCyvis with an active agent.
        startService(Intent(this, ai.opencyvis.OverlayService::class.java))

        // Handle ask_user notification tap from cold start.
        if (intent.getBooleanExtra(AgentService.EXTRA_FOCUS_ASK, false)) {
            chatRecycler.post { scrollChatToBottom() }
        }
    }

    override fun onDestroy() {
        engineSubscriptionJob?.cancel()
        stateCollectionJob?.cancel()
        stepResultCollectionJob?.cancel()
        voiceInputController.destroy()
        if (voiceTestReceiverRegistered) {
            unregisterReceiver(voiceTestReceiver)
            voiceTestReceiverRegistered = false
        }
        if (demoReceiverRegistered) {
            unregisterReceiver(demoReceiver)
            demoReceiverRegistered = false
        }
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureRecordAudioPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun registerVoiceTestReceiver() {
        val filter = IntentFilter(VoiceInputTestBridge.ACTION)
        val demoFilter = IntentFilter("ai.opencyvis.TEST")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceTestReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(demoReceiver, demoFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(voiceTestReceiver, filter)
            registerReceiver(demoReceiver, demoFilter)
        }
        voiceTestReceiverRegistered = true
        demoReceiverRegistered = true
    }

    private fun editTextTarget(editText: EditText): VoiceInputController.TextTarget =
        object : VoiceInputController.TextTarget {
            override fun getText(): String = editText.text.toString()
            override fun setText(text: String) {
                editText.setText(text)
                editText.setSelection(editText.text.length)
            }
        }

    private fun ensureServiceStarted() {
        val intent = Intent(this, AgentService::class.java)
        startForegroundService(intent)
        if (!bound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopAgent() {
        agentService?.stopAgent()
        actionButtons.visibility = View.GONE
        chatAdapter.addMessage(ChatMessage(MessageType.SYSTEM, "Agent stopped"))
        scrollChatToBottom()
    }

    // ── State collection ───────────────────────────────────────────────

    /**
     * Subscribe to AgentService.engineFlow and re-bind state collectors
     * each time a new AgentEngine is created. Multi-subscriber StateFlow
     * means we no longer fight OverlayService for the same callback slot.
     */
    private fun subscribeToEngineFlow() {
        engineSubscriptionJob?.cancel()
        val service = agentService ?: return
        engineSubscriptionJob = scope.launch {
            service.engineFlow.collect { engine ->
                if (engine != null) {
                    collectStateUpdates()
                } else {
                    stateCollectionJob?.cancel()
                    stepResultCollectionJob?.cancel()
                }
            }
        }
    }

    private fun collectStateUpdates() {
        stateCollectionJob?.cancel()
        stepResultCollectionJob?.cancel()

        stateCollectionJob = agentService?.stateFlow?.let { flow ->
            scope.launch {
                flow.collect { state ->
                    onStateChanged(state)
                }
            }
        }

        stepResultCollectionJob = agentService?.stepResultFlow?.let { flow ->
            scope.launch {
                flow.collect { result ->
                    if (config.debugMode) {
                        val prefix = if (result.success) "" else "[FAIL] "
                        val msg = "[Step ${result.step}] ${prefix}${result.actionType}: ${result.detail}"
                        chatAdapter.addMessage(ChatMessage(MessageType.AGENT_STATUS, msg))
                        if (!result.debugInfo.isNullOrBlank()) {
                            chatAdapter.addMessage(
                                ChatMessage(
                                    MessageType.AGENT_DEBUG,
                                    "[LLM]\n${result.debugInfo}"
                                )
                            )
                        }
                    } else if (!result.completed) {
                        // Keep cycle animation running; update thought in-place
                        val userMsg = result.thought.ifBlank { result.detail }
                        if (!chatAdapter.hasCycle()) {
                            chatAdapter.startCycle()
                        }
                        chatAdapter.updateCycleText(userMsg)
                    }

                    // Show completion summary when task ends
                    if (result.completed) {
                        val summary = when (result.actionType) {
                            "finish" -> "✅ ${getString(R.string.task_complete, result.thought)}"
                            "fail" -> "❌ ${getString(R.string.task_failed, result.detail.removePrefix("Task failed: "))}"
                            "max_steps" -> "⚠️ ${getString(R.string.task_max_steps, result.step)}"
                            "error" -> "❌ ${getString(R.string.task_error, result.detail)}"
                            else -> "✅ ${getString(R.string.task_complete_step, result.step)}"
                        }
                        if (config.debugMode) {
                            chatAdapter.addMessage(ChatMessage(MessageType.AGENT_RESULT, summary))
                        } else {
                            chatAdapter.convertCycleToResult(summary)
                        }
                    }
                    scrollChatToBottom()
                }
            }
        }
    }

    private fun onStateChanged(state: AgentState) {
        when (state) {
            is AgentState.Idle -> {
                stopCycleAnimation()
                statusText.text = "OpenCyvis"
                // Keep View button visible if VD is still alive for inspection
                val hasVd = agentService?.getVirtualDisplayManager()?.isCreated == true
                actionButtons.visibility = if (hasVd) View.VISIBLE else View.GONE
                btnStop.visibility = if (hasVd) View.VISIBLE else View.GONE
                btnViewOp.text = "View"
                editInput.hint = "Ask me anything..."
                editInput.isEnabled = true
                btnSend.text = "▲"
                // Show recent tasks if no active conversation
                if (agentService?.currentConversationId == null && chatAdapter.itemCount <= 1) {
                    showRecentTasks()
                }
            }
            is AgentState.Running -> {
                hideRecentTasks()
                statusText.text = if (config.debugMode) {
                    "Running (${state.step}/${config.maxSteps})"
                } else {
                    "Running"
                }
                if (config.debugMode) {
                    chatAdapter.updateLastAgentStatus(state.thought)
                }
                scrollChatToBottom()
                actionButtons.visibility = View.VISIBLE
                btnViewOp.text = "View"
                editInput.hint = getString(R.string.supplement_hint)
                editInput.isEnabled = true
                btnSend.text = "▲"
            }
            is AgentState.Paused -> {
                statusText.text = "Paused"
                actionButtons.visibility = View.VISIBLE
                btnViewOp.text = "Resume"
                btnStop.visibility = View.VISIBLE
                editInput.hint = getString(R.string.supplement_hint)
                editInput.isEnabled = true
            }
            is AgentState.Error -> {
                stopCycleAnimation()
                statusText.text = "Error"
                chatAdapter.addMessage(ChatMessage(MessageType.AGENT_RESULT, "Error: ${state.message}"))
                actionButtons.visibility = View.GONE
                editInput.isEnabled = true
                btnSend.text = "▲"
                scrollChatToBottom()
            }
            is AgentState.WaitingForUser -> {
                stopCycleAnimation()
                statusText.text = "Waiting for your answer..."
                chatAdapter.addMessage(ChatMessage(MessageType.AGENT_QUESTION, state.question))
                editInput.hint = "Your answer..."
                editInput.isEnabled = true
                btnSend.text = "▲"
                actionButtons.visibility = View.VISIBLE
                scrollChatToBottom()
            }
            is AgentState.WaitingForHandoff -> {
                stopCycleAnimation()
                statusText.text = "Waiting for handoff..."
                chatAdapter.addMessage(
                    ChatMessage(
                        MessageType.AGENT_STATUS,
                        getString(R.string.handoff_sensitive_input, state.reason)
                    )
                )
                editInput.hint = getString(R.string.handoff_waiting_hint)
                editInput.isEnabled = true
                btnSend.text = "▲"
                actionButtons.visibility = View.VISIBLE
                btnViewOp.text = "View"
                scrollChatToBottom()
            }
        }
    }

    private fun stopCycleAnimation() {
        chatAdapter.removeCycle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(AgentService.EXTRA_FOCUS_ASK, false)) {
            scrollChatToBottom()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh recent tasks if idle
        val state = agentService?.stateFlow?.value
        if (state is AgentState.Idle || state == null) {
            if (agentService?.currentConversationId == null && chatAdapter.itemCount <= 1) {
                showRecentTasks()
            }
        }
    }

    private fun openConversationDetail(conv: ai.opencyvis.db.ConversationEntity) {
        val intent = Intent(this, ConversationDetailActivity::class.java).apply {
            putExtra("conversation_id", conv.id)
            putExtra("conversation_title", conv.title)
            putExtra("conversation_status", conv.status)
            putExtra("conversation_created_at", conv.createdAt)
        }
        startActivity(intent)
    }

    private fun showRecentTasks() {
        scope.launch {
            val recent = historyRepo.getRecentConversations(3)
            if (recent.isNotEmpty()) {
                recentTasksAdapter.submitList(recent)
                recentTasksContainer.visibility = View.VISIBLE
                chatRecycler.visibility = View.GONE
            } else {
                recentTasksContainer.visibility = View.GONE
                chatRecycler.visibility = View.VISIBLE
            }
        }
    }

    private fun hideRecentTasks() {
        recentTasksContainer.visibility = View.GONE
        chatRecycler.visibility = View.VISIBLE
    }

    private fun scrollChatToBottom() {
        chatRecycler.post {
            val count = chatAdapter.itemCount
            if (count > 0) chatRecycler.smoothScrollToPosition(count - 1)
        }
    }
}
