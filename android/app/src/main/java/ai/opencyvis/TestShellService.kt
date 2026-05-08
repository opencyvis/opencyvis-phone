package ai.opencyvis

import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import ai.opencyvis.engine.AgentState
import ai.opencyvis.voice.VoiceInputTestBridge
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shell command handler for `adb shell dumpsys opencyvis`.
 * Registered only on debuggable builds via [App.onCreate].
 *
 * Usage:
 *   adb shell dumpsys opencyvis                             → state JSON (default)
 *   adb shell dumpsys opencyvis state                      → state JSON
 *   adb shell dumpsys opencyvis start <instruction>        → start agent
 *   adb shell dumpsys opencyvis reset                      → stop engine
 *   adb shell dumpsys opencyvis inject ask_user_response <text>
 *   adb shell dumpsys opencyvis inject supplement <text>
 *   adb shell dumpsys opencyvis debug <command>            → debug commands
 *   adb shell dumpsys opencyvis simulate ask_user <question>
 *   adb shell dumpsys opencyvis simulate handoff <reason>
 *   adb shell dumpsys opencyvis voice <target> <text>
 *   adb shell dumpsys opencyvis help
 */
class TestShellService : Binder() {

    companion object {
        private const val TAG = "TestShellCmd"
        const val SERVICE_NAME = "opencyvis"
        private const val TIMEOUT_SECONDS = 5L

        fun register() {
            try {
                val sm = Class.forName("android.os.ServiceManager")
                val addService = sm.getMethod("addService", String::class.java, IBinder::class.java)
                addService.invoke(null, SERVICE_NAME, TestShellService())
                Log.i(TAG, "Registered shell service '$SERVICE_NAME'")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register shell service", e)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>?) {
        val cmd = args?.firstOrNull() ?: "state"

        when (cmd) {
            "state" -> handleState(pw)
            "start" -> handleStart(pw, args ?: emptyArray())
            "reset" -> runOnMain(pw) { it.stopAgent() }
            "inject" -> handleInject(pw, args ?: emptyArray())
            "debug" -> handleDebug(pw, args ?: emptyArray())
            "simulate" -> handleSimulate(pw, args ?: emptyArray())
            "voice" -> handleVoice(pw, args ?: emptyArray())
            "help" -> handleHelp(pw)
            else -> {
                pw.println("Unknown command: $cmd")
                pw.println("Run 'dumpsys opencyvis help' for usage.")
            }
        }
    }

    private fun handleState(pw: PrintWriter) {
        val service = App.agentService
        if (service == null) {
            pw.println("""{"engine_state":"NOT_RUNNING","service_alive":false}""")
            return
        }

        val engine = service.engineFlow.value
        val state = engine?.state?.value

        val engineState: String
        val step: Int
        val thought: String?
        val extra: String?

        when (state) {
            is AgentState.Idle -> {
                engineState = "Idle"
                step = 0
                thought = state.resultMessage
                extra = null
            }
            is AgentState.Running -> {
                engineState = "Running"
                step = state.step
                thought = state.thought
                extra = null
            }
            is AgentState.Paused -> {
                engineState = "Paused"
                step = 0
                thought = null
                extra = null
            }
            is AgentState.Error -> {
                engineState = "Error"
                step = 0
                thought = null
                extra = state.message
            }
            is AgentState.WaitingForUser -> {
                engineState = "WaitingForUser"
                step = state.step
                thought = null
                extra = state.question
            }
            is AgentState.WaitingForHandoff -> {
                engineState = "WaitingForHandoff"
                step = state.step
                thought = null
                extra = state.reason
            }
            null -> {
                engineState = "NoEngine"
                step = 0
                thought = null
                extra = null
            }
        }

        val displayState = service.displayState.name

        val json = buildString {
            append("""{"engine_state":"$engineState"""")
            append(""","step":$step""")
            append(""","display_state":"$displayState"""")
            append(""","service_alive":true""")
            if (thought != null) append(""","thought":"${escapeJson(thought)}"""")
            if (extra != null) append(""","extra":"${escapeJson(extra)}"""")
            append("}")
        }
        pw.println(json)
    }

    private fun handleStart(pw: PrintWriter, args: Array<out String>) {
        val instruction = args.drop(1).joinToString(" ")
        if (instruction.isBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis start <instruction>")
            return
        }
        runOnMain(pw) { service ->
            Log.i(TAG, "start: $instruction")
            service.startAgent(instruction)
        }
    }

    private fun handleInject(pw: PrintWriter, args: Array<out String>) {
        val subCmd = args.getOrNull(1)
        val payload = args.drop(2).joinToString(" ")

        val service = App.agentService
        if (service == null) {
            pw.println("ERROR: AgentService not running")
            return
        }

        when (subCmd) {
            "ask_user_response" -> {
                if (payload.isBlank()) {
                    pw.println("ERROR: usage: dumpsys opencyvis inject ask_user_response <text>")
                    return
                }
                runOnMain(pw) { it.submitUserResponse(payload) }
            }
            "supplement" -> {
                if (payload.isBlank()) {
                    pw.println("ERROR: usage: dumpsys opencyvis inject supplement <text>")
                    return
                }
                runOnMain(pw) { it.submitUserSupplement(payload) }
            }
            else -> {
                pw.println("ERROR: unknown inject command: $subCmd")
                pw.println("Available: ask_user_response, supplement")
            }
        }
    }

    private fun handleDebug(pw: PrintWriter, args: Array<out String>) {
        val subCmd = args.getOrNull(1)
        if (subCmd.isNullOrBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis debug <command>")
            pw.println("Commands: running, view, takeover, return_control, stop, complete_handoff,")
            pw.println("          repeat_type_text_block, repeat_tap_block, repeat_tap_allow")
            return
        }

        runOnMain(pw) { service ->
            Log.i(TAG, "debug: $subCmd")
            when (subCmd) {
                "running" -> service.debugStartRunningAgent()
                "view" -> service.enterViewMode()
                "takeover" -> service.enterTakeoverMode()
                "return_control" -> service.exitTakeoverMode()
                "stop" -> service.stopAgent()
                "complete_handoff" -> service.completeUserHandoff("dumpsys_test")
                "repeat_type_text_block" -> service.debugRepeatGuardTypeText()
                "repeat_tap_block" -> service.debugRepeatGuardTapBlocked()
                "repeat_tap_allow" -> service.debugRepeatGuardTapAllowed()
                else -> throw IllegalArgumentException("Unknown debug command: $subCmd")
            }
        }
    }

    private fun handleSimulate(pw: PrintWriter, args: Array<out String>) {
        val subCmd = args.getOrNull(1)
        val payload = args.drop(2).joinToString(" ")

        if (subCmd.isNullOrBlank() || payload.isBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis simulate <ask_user|handoff> <text>")
            return
        }

        val service = App.agentService
        if (service == null) {
            pw.println("ERROR: AgentService not running")
            return
        }

        val engine = service.engineFlow.value
        if (engine == null) {
            pw.println("ERROR: no engine running")
            return
        }

        runOnMain(pw) {
            Log.i(TAG, "simulate: $subCmd $payload")
            when (subCmd) {
                "ask_user" -> engine.debugSimulateAskUser(payload)
                "handoff" -> engine.debugSimulateHandoff(payload)
                else -> throw IllegalArgumentException("Unknown simulate command: $subCmd")
            }
        }
    }

    private fun handleVoice(pw: PrintWriter, args: Array<out String>) {
        val target = args.getOrNull(1)
        val text = args.drop(2).joinToString(" ")

        if (target.isNullOrBlank() || text.isBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis voice <target> <text>")
            pw.println("Targets: command, control_answer, view_answer")
            return
        }

        val service = App.agentService
        if (service == null) {
            pw.println("ERROR: AgentService not running")
            return
        }

        runOnMain(pw) {
            Log.i(TAG, "voice: target=$target text=$text")
            service.sendBroadcast(Intent(VoiceInputTestBridge.ACTION).apply {
                setPackage(service.packageName)
                putExtra(VoiceInputTestBridge.EXTRA_TARGET, target)
                putExtra(VoiceInputTestBridge.EXTRA_RESULT, text)
            })
        }
    }

    /**
     * Dispatch an action to the main thread and block until complete.
     * Returns "OK" on success, "ERROR: ..." on failure or timeout.
     */
    private fun runOnMain(pw: PrintWriter, action: (AgentService) -> Unit) {
        val service = App.agentService
        if (service == null) {
            pw.println("ERROR: AgentService not running")
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                action(service)
                pw.println("OK")
            } catch (e: Exception) {
                pw.println("ERROR: ${e.message}")
            }
            return
        }

        val latch = CountDownLatch(1)
        var error: String? = null

        mainHandler.post {
            try {
                action(service)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            pw.println("ERROR: timeout (${TIMEOUT_SECONDS}s)")
            return
        }

        if (error != null) {
            pw.println("ERROR: $error")
        } else {
            pw.println("OK")
        }
    }

    private fun handleHelp(pw: PrintWriter) {
        pw.println("Usage: dumpsys opencyvis <command>")
        pw.println()
        pw.println("Commands:")
        pw.println("  state                          Print engine state as JSON (default)")
        pw.println("  start <instruction>            Start agent with instruction")
        pw.println("  reset                          Stop the agent engine")
        pw.println("  inject <subcommand> <text>     Inject events into the agent")
        pw.println("    inject ask_user_response <text>")
        pw.println("    inject supplement <text>")
        pw.println("  debug <command>                Execute debug commands")
        pw.println("    debug running                Start debug running agent")
        pw.println("    debug view                   Enter VIEW mode")
        pw.println("    debug takeover               Enter TAKEOVER mode")
        pw.println("    debug return_control         Exit TAKEOVER → VIEW")
        pw.println("    debug stop                   Stop agent")
        pw.println("    debug complete_handoff       Complete user handoff")
        pw.println("    debug repeat_type_text_block Test repeat guard (type_text)")
        pw.println("    debug repeat_tap_block       Test repeat guard (tap blocked)")
        pw.println("    debug repeat_tap_allow       Test repeat guard (tap allowed)")
        pw.println("  simulate <type> <text>         Simulate agent states")
        pw.println("    simulate ask_user <question>")
        pw.println("    simulate handoff <reason>")
        pw.println("  voice <target> <text>          Inject voice input result")
        pw.println("    Targets: command, control_answer, view_answer")
        pw.println("  help                           Show this help")
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
