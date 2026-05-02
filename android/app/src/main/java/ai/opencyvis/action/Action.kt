package ai.opencyvis.action

/**
 * Sealed class representing all possible phone actions.
 * Mirrors the Python ActionType enum from actions.py.
 */
sealed class Action(val typeName: String, open val thought: String) {

    data class Tap(
        val x: Int,
        val y: Int,
        override val thought: String = ""
    ) : Action("tap", thought)

    data class LongPress(
        val x: Int,
        val y: Int,
        override val thought: String = ""
    ) : Action("long_press", thought)

    data class OpenApp(
        val appName: String,
        override val thought: String = ""
    ) : Action("open_app", thought)

    data class Swipe(
        val direction: String,
        override val thought: String = ""
    ) : Action("swipe", thought)

    data class KeyEvent(
        val key: String,
        override val thought: String = ""
    ) : Action("key_event", thought)

    data class TypeText(
        val text: String,
        override val thought: String = ""
    ) : Action("type_text", thought)

    data class Wait(
        override val thought: String = ""
    ) : Action("wait", thought)

    data class Finish(
        override val thought: String = ""
    ) : Action("finish", thought)

    data class Fail(
        val reason: String,
        override val thought: String = ""
    ) : Action("fail", thought)

    data class AskUser(
        val question: String,
        override val thought: String = ""
    ) : Action("ask_user", thought)

    data class HandoffUser(
        val reason: String,
        override val thought: String = ""
    ) : Action("handoff_user", thought)

    data class Note(
        val note: String,
        override val thought: String = ""
    ) : Action("note", thought)

    data class Remember(
        val key: String,
        val value: String,
        val category: String = "",
        override val thought: String = ""
    ) : Action("remember", thought)

    companion object {
        /**
         * Parse an Action from the LLM tool call result map.
         */
        fun fromMap(map: Map<String, Any?>): Action {
            val thought = (map["thought"] as? String) ?: ""
            val actionType = ((map["action_type"] as? String) ?: "fail").trim()

            return when (actionType) {
                "tap" -> Tap(
                    x = (map["x"] as? Number)?.toInt()
                        ?: throw IllegalArgumentException("tap action missing required field 'x'"),
                    y = (map["y"] as? Number)?.toInt()
                        ?: throw IllegalArgumentException("tap action missing required field 'y'"),
                    thought = thought
                )
                "long_press" -> LongPress(
                    x = (map["x"] as? Number)?.toInt()
                        ?: throw IllegalArgumentException("long_press action missing required field 'x'"),
                    y = (map["y"] as? Number)?.toInt()
                        ?: throw IllegalArgumentException("long_press action missing required field 'y'"),
                    thought = thought
                )
                "open_app" -> OpenApp(
                    appName = (map["app_name"] as? String) ?: "",
                    thought = thought
                )
                "swipe" -> Swipe(
                    direction = (map["direction"] as? String) ?: "up",
                    thought = thought
                )
                "key_event" -> KeyEvent(
                    key = (map["key"] as? String) ?: "back",
                    thought = thought
                )
                "type_text" -> TypeText(
                    text = (map["text"] as? String) ?: "",
                    thought = thought
                )
                "wait" -> Wait(thought = thought)
                "finish" -> Finish(thought = thought)
                "fail" -> Fail(
                    reason = (map["reason"] as? String) ?: "unknown reason",
                    thought = thought
                )
                "ask_user" -> AskUser(
                    question = (map["question"] as? String) ?: thought,
                    thought = thought
                )
                "handoff_user" -> HandoffUser(
                    reason = (map["handoff_reason"] as? String) ?: thought,
                    thought = thought
                )
                "note" -> Note(
                    note = (map["note"] as? String) ?: thought,
                    thought = thought
                )
                "remember" -> Remember(
                    key = (map["memory_key"] as? String) ?: "",
                    value = (map["memory_value"] as? String) ?: "",
                    category = (map["memory_category"] as? String) ?: "",
                    thought = thought
                )
                else -> Fail(reason = "Unknown action type: $actionType", thought = thought)
            }
        }
    }
}
