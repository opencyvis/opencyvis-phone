package ai.opencyvis.backend

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.opencyvis.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull

class SetupActivity : AppCompatActivity() {

    companion object {
        const val RESULT_BACKEND_READY = 100
    }

    private enum class SetupState {
        CHOOSE_METHOD,       // Choose between Shizuku and ADB Direct
        NEED_WIFI,           // WiFi not connected
        SHIZUKU_CHECK,       // Check if Shizuku is installed and running
        SHIZUKU_PERMISSION,  // Request Shizuku permission
        ADB_CHECK_OS,        // Check Android version for wireless debugging
        ADB_ENABLE_DEV,      // Guide: enable developer options
        ADB_ENABLE_WIRELESS, // Guide: enable wireless debugging
        ADB_PAIR,            // Enter pairing code
        CONNECTING,          // Connecting to backend
        CONNECTED,           // Success
        FAILED               // Something went wrong
    }

    private lateinit var titleView: TextView
    private lateinit var descView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var inputLayout: TextInputLayout
    private lateinit var inputField: TextInputEditText
    private lateinit var actionButton: MaterialButton
    private lateinit var secondaryButton: MaterialButton

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentState = SetupState.CHOOSE_METHOD
    private var directConnector: DirectConnector? = null
    private var resumeAfterCreate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        titleView = findViewById(R.id.setup_title)
        descView = findViewById(R.id.setup_description)
        progressBar = findViewById(R.id.setup_progress)
        inputLayout = findViewById(R.id.setup_input_layout)
        inputField = findViewById(R.id.setup_input)
        actionButton = findViewById(R.id.setup_action_button)
        secondaryButton = findViewById(R.id.setup_secondary_button)

        actionButton.setOnClickListener { onActionClick() }
        secondaryButton.setOnClickListener { onSecondaryClick() }

        val detected = SetupStateDetector.detect(this, false)
        Log.i("SetupActivity", "onCreate: detected=$detected taskId=$taskId")
        val startState = when (detected) {
            ai.opencyvis.backend.SetupState.NEED_WIFI -> SetupState.NEED_WIFI
            ai.opencyvis.backend.SetupState.UNSUPPORTED_VERSION -> SetupState.ADB_CHECK_OS
            ai.opencyvis.backend.SetupState.NEED_DEVELOPER_OPTIONS -> SetupState.CHOOSE_METHOD
            ai.opencyvis.backend.SetupState.NEED_WIRELESS_DEBUGGING -> SetupState.ADB_ENABLE_WIRELESS
            ai.opencyvis.backend.SetupState.NEED_PAIRING -> {
                // Wireless debugging is on — try reconnecting with existing keys first
                // before asking user to re-pair. Keys survive wireless debugging toggle
                // on most devices.
                tryAutoReconnect()
                return // tryAutoReconnect manages its own UI; don't call updateUi here
            }
            ai.opencyvis.backend.SetupState.ALREADY_CONNECTED -> {
                setResult(RESULT_BACKEND_READY); finish(); return
            }
        }
        Log.i("SetupActivity", "onCreate: startState=$startState")
        updateUi(startState)
        resumeAfterCreate = true
    }

    override fun onResume() {
        super.onResume()
        if (resumeAfterCreate) {
            Log.i("SetupActivity", "onResume: skipping (after onCreate)")
            resumeAfterCreate = false
            return
        }
        val detected = SetupStateDetector.detect(this, false)
        Log.i("SetupActivity", "onResume: detected=$detected currentState=$currentState")
        val newState = when (detected) {
            ai.opencyvis.backend.SetupState.NEED_WIFI -> SetupState.NEED_WIFI
            ai.opencyvis.backend.SetupState.UNSUPPORTED_VERSION -> SetupState.ADB_CHECK_OS
            ai.opencyvis.backend.SetupState.NEED_DEVELOPER_OPTIONS -> SetupState.CHOOSE_METHOD
            ai.opencyvis.backend.SetupState.NEED_WIRELESS_DEBUGGING -> SetupState.ADB_ENABLE_WIRELESS
            ai.opencyvis.backend.SetupState.NEED_PAIRING -> {
                tryAutoReconnect()
                return // tryAutoReconnect manages its own UI
            }
            ai.opencyvis.backend.SetupState.ALREADY_CONNECTED -> {
                setResult(RESULT_BACKEND_READY); finish(); return
            }
        }
        if (newState != currentState) updateUi(newState)
    }

    private fun updateUi(state: SetupState) {
        Log.i("SetupActivity", "updateUi: $state", Throwable("stack"))
        currentState = state
        progressBar.visibility = View.GONE
        inputLayout.visibility = View.GONE
        secondaryButton.visibility = View.GONE
        actionButton.isEnabled = true

        when (state) {
            SetupState.CHOOSE_METHOD -> {
                // Check if Shizuku is installed — if so, offer it as secondary option
                val shizukuAvailable = try {
                    ShizukuConnector(this).isAvailable()
                } catch (_: Exception) { false }

                if (shizukuAvailable) {
                    // Shizuku detected — offer both options
                    titleView.text = "Setup"
                    descView.text = "OpenCyvis needs permissions to control the device.\n\n" +
                        "Shizuku is detected on your device — you can use it for a quick setup, " +
                        "or use the built-in Wireless ADB method (no extra apps needed)."
                    actionButton.text = "Use Shizuku"
                    secondaryButton.text = "Use Wireless ADB"
                    secondaryButton.visibility = View.VISIBLE
                } else {
                    // No Shizuku — go directly to wireless ADB flow
                    updateUi(SetupState.ADB_CHECK_OS)
                    return
                }
            }
            SetupState.NEED_WIFI -> {
                titleView.text = getString(R.string.setup_need_wifi_title)
                descView.text = getString(R.string.setup_need_wifi_desc)
                actionButton.text = getString(R.string.setup_action_retry)
            }
            SetupState.SHIZUKU_CHECK -> {
                titleView.text = "Shizuku Setup"
                descView.text = "Checking if Shizuku is available..."
                progressBar.visibility = View.VISIBLE
                actionButton.text = "Checking..."
                actionButton.isEnabled = false
                checkShizuku()
            }
            SetupState.SHIZUKU_PERMISSION -> {
                titleView.text = "Shizuku Permission"
                descView.text = "Shizuku is running. Grant permission to OpenCyvis to continue."
                actionButton.text = "Grant Permission"
            }
            SetupState.ADB_CHECK_OS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    titleView.text = getString(R.string.setup_unsupported_title)
                    descView.text = getString(R.string.setup_unsupported_desc, Build.VERSION.SDK_INT)
                    actionButton.text = "Back"
                } else {
                    updateUi(SetupState.ADB_ENABLE_DEV)
                    return
                }
            }
            SetupState.ADB_ENABLE_DEV -> {
                titleView.text = getString(R.string.setup_dev_options_title)
                val desc = StringBuilder(getString(R.string.setup_dev_options_desc, 7))
                if (OemHelper.isMiui()) desc.append("\n\n").append(getString(R.string.setup_miui_sim_warning))
                desc.append("\n\n").append(getString(R.string.setup_dev_options_reassure))
                descView.text = desc
                actionButton.text = getString(R.string.setup_dev_options_btn)
                secondaryButton.text = getString(R.string.setup_dev_options_skip)
                secondaryButton.visibility = View.VISIBLE
            }
            SetupState.ADB_ENABLE_WIRELESS -> {
                startPairingService()
                titleView.text = getString(R.string.setup_wireless_title)
                descView.text = getString(R.string.setup_wireless_desc)
                actionButton.text = getString(R.string.setup_wireless_btn)
                secondaryButton.text = getString(R.string.setup_action_manual_input)
                secondaryButton.visibility = View.VISIBLE
            }
            SetupState.ADB_PAIR -> {
                startPairingService()
                titleView.text = getString(R.string.setup_pair_title)
                descView.text = getString(R.string.setup_pair_desc, 6)
                inputLayout.visibility = View.VISIBLE
                inputLayout.hint = getString(R.string.setup_pair_hint, 6)
                inputField.text?.clear()
                actionButton.text = getString(R.string.setup_pair_btn)
                secondaryButton.text = getString(R.string.setup_wireless_btn)
                secondaryButton.visibility = View.VISIBLE
            }
            SetupState.CONNECTING -> {
                titleView.text = "Connecting..."
                descView.text = "Establishing connection to the privileged service."
                progressBar.visibility = View.VISIBLE
                actionButton.isEnabled = false
            }
            SetupState.CONNECTED -> {
                titleView.text = getString(R.string.setup_success_title)
                descView.text = getString(R.string.setup_success_desc)
                if (OemHelper.isColorOS()) {
                    descView.append("\n\n" + getString(R.string.setup_coloros_auto_close))
                }
                actionButton.text = getString(R.string.setup_done_btn)
            }
            SetupState.FAILED -> {
                titleView.text = getString(R.string.setup_error_wrong_code)
                descView.text = getString(R.string.setup_error_connect_fail)
                actionButton.text = getString(R.string.setup_action_retry)
            }
        }
    }

    private fun onActionClick() {
        when (currentState) {
            SetupState.CHOOSE_METHOD -> updateUi(SetupState.SHIZUKU_CHECK)
            SetupState.NEED_WIFI -> {
                // Re-check WiFi
                if (SetupStateDetector.hasWifi(this)) {
                    updateUi(SetupState.CHOOSE_METHOD)
                } else {
                    Toast.makeText(this, getString(R.string.setup_need_wifi_title), Toast.LENGTH_SHORT).show()
                }
            }
            SetupState.SHIZUKU_CHECK -> {}
            SetupState.SHIZUKU_PERMISSION -> requestShizukuPermission()
            SetupState.ADB_CHECK_OS -> updateUi(SetupState.CHOOSE_METHOD)
            SetupState.ADB_ENABLE_DEV -> {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
            SetupState.ADB_ENABLE_WIRELESS -> {
                openWirelessDebuggingSettings()
            }
            SetupState.ADB_PAIR -> {
                val code = inputField.text?.toString()
                if (code.isNullOrBlank() || code.length < 6) {
                    inputLayout.error = "Enter a 6-digit code"
                } else {
                    inputLayout.error = null
                    submitPairingCode(code)
                }
            }
            SetupState.CONNECTING -> {} // wait
            SetupState.CONNECTED -> {
                setResult(RESULT_BACKEND_READY)
                finish()
            }
            SetupState.FAILED -> updateUi(SetupState.CHOOSE_METHOD)
        }
    }

    private fun onSecondaryClick() {
        when (currentState) {
            SetupState.CHOOSE_METHOD -> updateUi(SetupState.ADB_CHECK_OS)
            SetupState.ADB_ENABLE_DEV -> updateUi(SetupState.ADB_ENABLE_WIRELESS)
            SetupState.ADB_ENABLE_WIRELESS -> {
                // "Enter Code Manually"
                updateUi(SetupState.ADB_PAIR)
            }
            SetupState.ADB_PAIR -> {
                openWirelessDebuggingSettings()
            }
            else -> {}
        }
    }

    private fun openWirelessDebuggingSettings() {
        // Trigger heads-up notification to guide user while in Settings
        startService(AdbPairingService.alertIntent(this))

        val wirelessIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        if (wirelessIntent.resolveActivity(packageManager) != null) {
            startActivity(wirelessIntent)
        } else {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", "toggle_adb_wireless")
                })
            })
        }
    }

    private fun checkShizuku() {
        scope.launch {
            try {
                val connector = ShizukuConnector(this@SetupActivity)
                if (connector.isAvailable()) {
                    // Shizuku running + permission granted -- try connecting
                    connector.connect()
                    val result = withTimeoutOrNull(10_000L) {
                        connector.state.first {
                            it is ConnectionState.Connected || it is ConnectionState.Failed
                        }
                    }
                    if (result is ConnectionState.Connected) {
                        updateUi(SetupState.CONNECTED)
                    } else {
                        updateUi(SetupState.FAILED)
                    }
                } else {
                    // Check if Shizuku is running but we lack permission
                    val shizukuRunning = try {
                        rikka.shizuku.Shizuku.pingBinder()
                    } catch (_: Exception) { false }

                    if (shizukuRunning) {
                        updateUi(SetupState.SHIZUKU_PERMISSION)
                    } else {
                        descView.text = "Shizuku is not installed or not running.\n\n" +
                            "Install Shizuku from the Play Store and start it, then try again.\n\n" +
                            "Or use Wireless ADB instead."
                        actionButton.text = "Retry"
                        actionButton.isEnabled = true
                        secondaryButton.text = "Use Wireless ADB"
                        secondaryButton.visibility = View.VISIBLE
                        secondaryButton.setOnClickListener { updateUi(SetupState.ADB_CHECK_OS) }
                    }
                }
            } catch (e: Exception) {
                descView.text = "Error checking Shizuku: ${e.message}"
                actionButton.text = "Retry"
                actionButton.isEnabled = true
            }
        }
    }

    private fun requestShizukuPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(0)
            // Permission result is async -- re-check after a delay
            scope.launch {
                kotlinx.coroutines.delay(2000)
                checkShizuku()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to request permission: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun submitPairingCode(code: String) {
        updateUi(SetupState.CONNECTING)
        scope.launch {
            val connector = DirectConnector(this@SetupActivity)
            directConnector = connector

            // Initiate connection -- this will discover the pairing port
            connector.connect()

            // Wait for NeedsPairing or fast-path Connected
            val pairingState = withTimeoutOrNull(10_000L) {
                connector.state.first {
                    it is ConnectionState.NeedsPairing ||
                        it is ConnectionState.Connected ||
                        it is ConnectionState.Failed
                }
            }

            if (pairingState is ConnectionState.Connected) {
                updateUi(SetupState.CONNECTED)
                return@launch
            }
            if (pairingState is ConnectionState.Failed) {
                descView.text = "Connection failed: ${pairingState.error}"
                updateUi(SetupState.FAILED)
                return@launch
            }

            // Submit the pairing code
            connector.submitPairingCode(code)

            val result = withTimeoutOrNull(15_000L) {
                connector.state.first {
                    it is ConnectionState.Connected || it is ConnectionState.Failed
                }
            }
            if (result is ConnectionState.Connected) {
                updateUi(SetupState.CONNECTED)
            } else {
                val error = (result as? ConnectionState.Failed)?.error ?: "Timed out"
                descView.text = "Connection failed: $error"
                updateUi(SetupState.FAILED)
            }
        }
    }

    private fun tryAutoReconnect() {
        val service = ai.opencyvis.App.agentService
        Log.i("SetupActivity", "Attempting auto-reconnect before showing pairing UI (service=${service != null})")
        titleView.text = getString(R.string.setup_pair_title)
        descView.text = getString(R.string.setup_reconnecting)
        progressBar.visibility = View.VISIBLE
        actionButton.isEnabled = false
        inputLayout.visibility = View.GONE
        secondaryButton.visibility = View.GONE

        if (service == null) {
            Log.i("SetupActivity", "No AgentService, showing pairing UI")
            progressBar.visibility = View.GONE
            actionButton.isEnabled = true
            updateUi(SetupState.ADB_PAIR)
            return
        }

        scope.launch {
            val result = withTimeoutOrNull(15000L) {
                service.retryBackendDetection()
            }
            if (result == true) {
                Log.i("SetupActivity", "Auto-reconnect succeeded!")
                setResult(RESULT_BACKEND_READY)
                finish()
            } else {
                Log.i("SetupActivity", "Auto-reconnect failed, showing pairing UI")
                progressBar.visibility = View.GONE
                actionButton.isEnabled = true
                updateUi(SetupState.ADB_PAIR)
            }
        }
    }

    private fun startPairingService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = AdbPairingService.startIntent(this)
        try {
            startForegroundService(intent)
        } catch (e: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && e is ForegroundServiceStartNotAllowedException
            ) {
                startService(intent)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
