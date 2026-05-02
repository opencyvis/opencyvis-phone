package ai.opencyvis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.config.ConfigDeepLink
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentState
import ai.opencyvis.input.InputInjector
import ai.opencyvis.ui.MemoryActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var config: ConfigRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var agentService: AgentService? = null
    private var bound = false

    private var spinnerReady = false
    private lateinit var spinnerProvider: Spinner
    private lateinit var editApiKey: EditText
    private lateinit var editModel: EditText
    private lateinit var editBaseUrl: EditText
    private lateinit var editMaxSteps: EditText
    private lateinit var textMaxSteps: TextView
    private lateinit var switchDebugMode: Switch
    private lateinit var textStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnTestScreenshot: Button
    private lateinit var btnTestTap: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            agentService = (binder as? AgentService.AgentBinder)?.getService()
            bound = true
            updateConnectionStatus("Connected to AgentService")
            collectState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            updateConnectionStatus("Disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        config = ConfigRepository(this)

        // Bind views
        editApiKey = findViewById(R.id.edit_api_key)
        editModel = findViewById(R.id.edit_model)
        editBaseUrl = findViewById(R.id.edit_base_url)
        editMaxSteps = findViewById(R.id.edit_max_steps)
        textMaxSteps = findViewById(R.id.text_max_steps)
        switchDebugMode = findViewById(R.id.switch_debug_mode)
        textStatus = findViewById(R.id.text_status)
        btnStart = findViewById(R.id.btn_start)
        btnTestScreenshot = findViewById(R.id.btn_test_screenshot)
        btnTestTap = findViewById(R.id.btn_test_tap)
        findViewById<Button>(R.id.btn_memory).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        // Provider spinner — order must match R.array.api_providers:
        // 0 = OpenAI Compatible, 1 = Anthropic, 2 = Ollama (Local)
        spinnerProvider = findViewById(R.id.spinner_provider)
        val providerAdapter = ArrayAdapter.createFromResource(
            this, R.array.api_providers, android.R.layout.simple_spinner_item
        )
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProvider.adapter = providerAdapter
        spinnerProvider.setSelection(providerToIndex(config.apiProvider))
        spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!spinnerReady) { spinnerReady = true; return }
                val currentModel = editModel.text.toString()
                val currentUrl = editBaseUrl.text.toString()
                val isDefaultConfig = ConfigRepository.isDefaultModelOrUrl(currentModel, currentUrl)
                if (isDefaultConfig) {
                    val (defModel, defUrl) = providerDefaults(position)
                    editModel.setText(defModel)
                    editBaseUrl.setText(defUrl)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Load saved config
        editApiKey.setText(config.apiKey)
        editModel.setText(config.model)
        editBaseUrl.setText(config.baseUrl)
        editMaxSteps.setText(config.maxSteps.toString())
        textMaxSteps.text = "Max steps: ${config.maxSteps}"
        switchDebugMode.isChecked = config.debugMode

        // Start button
        btnStart.setOnClickListener {
            saveConfig()

            if (config.apiProvider != ConfigRepository.PROVIDER_OLLAMA && config.apiKey.isEmpty()) {
                Toast.makeText(this, "Please set API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Start AgentService
            val serviceIntent = Intent(this, AgentService::class.java)
            startForegroundService(serviceIntent)

            // Bind to it
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

            Toast.makeText(this, "Agent service started", Toast.LENGTH_SHORT).show()
            updateConnectionStatus("Starting...")
        }

        // Test screenshot
        btnTestScreenshot.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                val b64 = ScreenCapture.captureBase64()
                launch(Dispatchers.Main) {
                    if (b64 != null) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Screenshot OK (${b64.length} chars base64)",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Screenshot FAILED",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        // Test tap center
        btnTestTap.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                val injector = InputInjector(this@SettingsActivity)
                val result = injector.tap(500, 500)
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        if (result) "Tap OK" else "Tap FAILED",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        handleConfigIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConfigIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        saveConfig()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun saveConfig() {
        val provider = indexToProvider(spinnerProvider.selectedItemPosition)
        val (defModel, defUrl) = providerDefaults(spinnerProvider.selectedItemPosition)
        config.apiProvider = provider
        config.apiKey = editApiKey.text.toString().trim()
        config.model = editModel.text.toString().trim().ifEmpty { defModel }
        config.baseUrl = editBaseUrl.text.toString().trim().ifEmpty { defUrl }
        val maxSteps = editMaxSteps.text.toString().trim().toIntOrNull()
            ?: ConfigRepository.DEFAULT_MAX_STEPS
        config.maxSteps = maxOf(1, maxSteps)
        config.debugMode = switchDebugMode.isChecked
        editMaxSteps.setText(config.maxSteps.toString())
        textMaxSteps.text = "Max steps: ${config.maxSteps}"
    }

    private fun handleConfigIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (!isConfigUri(data)) return
        intent.data = null

        val params = data.queryParameterNames.associateWith { data.getQueryParameter(it) }
        val imported = ConfigDeepLink.parse(params).getOrElse { error ->
            Toast.makeText(this, error.message ?: "Invalid config link", Toast.LENGTH_LONG).show()
            return
        }

        if (isDebuggableBuild()) {
            applyImportedConfig(imported)
            Toast.makeText(this, "API configuration imported", Toast.LENGTH_SHORT).show()
        } else {
            showImportConfirmation(imported)
        }
    }

    private fun isConfigUri(uri: Uri): Boolean =
        uri.scheme == "opencyvis" && uri.host == "config"

    private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun showImportConfirmation(imported: ConfigDeepLink.ImportedConfig) {
        val message = """
            Provider: ${imported.provider}
            Model: ${imported.model}
            Base URL: ${imported.baseUrl}
            Max steps: ${imported.maxSteps ?: config.maxSteps}
            API key: ${ConfigDeepLink.redactedApiKey(imported.apiKey)}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Import API configuration?")
            .setMessage(message)
            .setPositiveButton("Import") { _, _ ->
                applyImportedConfig(imported)
                Toast.makeText(this, "API configuration imported", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyImportedConfig(imported: ConfigDeepLink.ImportedConfig) {
        config.apiProvider = imported.provider
        config.apiKey = imported.apiKey
        config.model = imported.model
        config.baseUrl = imported.baseUrl
        imported.maxSteps?.let { config.maxSteps = it }
        refreshConfigFields()
    }

    private fun refreshConfigFields() {
        spinnerProvider.setSelection(providerToIndex(config.apiProvider))
        editApiKey.setText(config.apiKey)
        editModel.setText(config.model)
        editBaseUrl.setText(config.baseUrl)
        editMaxSteps.setText(config.maxSteps.toString())
        textMaxSteps.text = "Max steps: ${config.maxSteps}"
        switchDebugMode.isChecked = config.debugMode
    }

    private fun providerToIndex(provider: String): Int = when (provider) {
        ConfigRepository.PROVIDER_ANTHROPIC -> 1
        ConfigRepository.PROVIDER_OLLAMA -> 2
        else -> 0
    }

    private fun indexToProvider(index: Int): String = when (index) {
        1 -> ConfigRepository.PROVIDER_ANTHROPIC
        2 -> ConfigRepository.PROVIDER_OLLAMA
        else -> ConfigRepository.PROVIDER_OPENAI
    }

    private fun providerDefaults(index: Int): Pair<String, String> = when (index) {
        1 -> ConfigRepository.DEFAULT_ANTHROPIC_MODEL to ConfigRepository.DEFAULT_ANTHROPIC_BASE_URL
        2 -> ConfigRepository.DEFAULT_OLLAMA_MODEL to ConfigRepository.DEFAULT_OLLAMA_BASE_URL
        else -> ConfigRepository.DEFAULT_MODEL to ConfigRepository.DEFAULT_BASE_URL
    }

    private fun updateConnectionStatus(status: String) {
        textStatus.text = "Status: $status"
    }

    private fun collectState() {
        agentService?.stateFlow?.let { flow ->
            scope.launch {
                flow.collect { state ->
                    val text = when (state) {
                        is AgentState.Idle -> "Idle"
                        is AgentState.Running -> "Running step ${state.step}"
                        is AgentState.Paused -> "Paused"
                        is AgentState.Error -> "Error: ${state.message}"
                        is AgentState.WaitingForUser -> "Waiting: ${state.question.take(30)}"
                        is AgentState.WaitingForHandoff -> "Handoff: ${state.reason.take(30)}"
                    }
                    updateConnectionStatus(text)
                }
            }
        }
    }
}
