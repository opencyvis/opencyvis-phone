package ai.opencyvis.action

import android.content.Context
import android.graphics.Point
import ai.opencyvis.engine.StepResult
import ai.opencyvis.input.InputInjector
import kotlinx.coroutines.delay

/**
 * Dispatches Action objects to InputInjector / AppLauncher and returns StepResults.
 * Ported from Python ActionExecutor.
 *
 * @param displayId Target display (0 for physical, >0 for virtual)
 * @param displaySize Fixed display size (for virtual displays with known resolution)
 */
class ActionExecutor(
    context: Context,
    displayId: Int = 0,
    displaySize: Point? = null,
    private val onOpenAppSuccess: ((packageName: String) -> Unit)? = null
) {

    private val inputInjector = InputInjector(context, displayId, displaySize)
    private val appLauncher = AppLauncher(context, displayId)

    /**
     * Swipe direction -> (startNx, startNy, endNx, endNy).
     * Ported from Python SWIPE_DIRECTIONS.
     */
    private val swipeDirections = mapOf(
        "up" to intArrayOf(500, 700, 500, 300),
        "down" to intArrayOf(500, 300, 500, 700),
        "left" to intArrayOf(700, 500, 300, 500),
        "right" to intArrayOf(300, 500, 700, 500)
    )

    /**
     * Execute an action and return a StepResult.
     */
    suspend fun execute(action: Action, step: Int): StepResult {
        val startTime = System.currentTimeMillis()
        val completed = false  // will be overridden by caller based on LLM response

        val (success, detail) = try {
            when (action) {
                is Action.Tap -> {
                    val ok = inputInjector.tap(action.x, action.y)
                    ok to "Tapped at (${action.x}, ${action.y})"
                }

                is Action.LongPress -> {
                    val ok = inputInjector.longPress(action.x, action.y)
                    ok to "Long pressed at (${action.x}, ${action.y})"
                }

                is Action.OpenApp -> {
                    val result = appLauncher.launch(action.appName)
                    val ok = result.packageName != null
                    if (ok) {
                        onOpenAppSuccess?.invoke(result.packageName!!)
                    }
                    ok to result.description
                }

                is Action.Swipe -> {
                    val coords = swipeDirections[action.direction.lowercase()]
                    if (coords != null) {
                        val ok = inputInjector.swipe(
                            coords[0], coords[1], coords[2], coords[3]
                        )
                        ok to "Swiped ${action.direction}"
                    } else {
                        false to "Unknown swipe direction: ${action.direction}"
                    }
                }

                is Action.KeyEvent -> {
                    val ok = inputInjector.keyEvent(action.key)
                    ok to "Key event: ${action.key}"
                }

                is Action.TypeText -> {
                    val ok = inputInjector.typeText(action.text)
                    ok to "Typed: ${action.text}"
                }

                is Action.Wait -> {
                    delay(2000)
                    true to "Waited 2s"
                }

                is Action.Finish -> {
                    true to "Task finished"
                }

                is Action.Fail -> {
                    false to "Task failed: ${action.reason}"
                }

                is Action.AskUser -> {
                    // Intercepted by AgentEngine before reaching here
                    true to "Asking user: ${action.question}"
                }

                is Action.HandoffUser -> {
                    // Intercepted by AgentEngine before reaching here
                    true to "Handing off to user: ${action.reason}"
                }

                is Action.Note -> {
                    // Intercepted by AgentEngine before reaching here
                    true to "Note: ${action.note}"
                }

                is Action.Remember -> {
                    // Intercepted by AgentEngine before reaching here
                    true to "Remembered: ${action.key}"
                }
            }
        } catch (e: Exception) {
            false to "Error: ${e.message}"
        }

        val duration = System.currentTimeMillis() - startTime

        return StepResult(
            step = step,
            actionType = action.typeName,
            thought = action.thought,
            success = success,
            detail = detail,
            durationMs = duration,
            completed = completed
        )
    }
}
