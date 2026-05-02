package ai.opencyvis.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for AgentEngine logic (pure logic portions).
 *
 * Tests cover:
 * - stripImagesFromHistory: removing images from all but last message
 * - Message trimming to stay within 15 messages
 * - System prompt content validation
 * - State transitions
 */
class AgentEngineTest {

    // --- State transition tests ---

    @Test
    fun `initial state is Idle`() {
        val state: AgentState = AgentState.Idle()
        assertTrue(state is AgentState.Idle)
    }

    @Test
    fun `transition from Idle to Running`() {
        var state: AgentState = AgentState.Idle()
        state = AgentState.Running(step = 1, thought = "starting")
        assertTrue(state is AgentState.Running)
        assertEquals(1, (state as AgentState.Running).step)
        assertEquals("starting", state.thought)
    }

    @Test
    fun `transition from Running to Idle on completion`() {
        var state: AgentState = AgentState.Running(step = 3, thought = "done")
        state = AgentState.Idle()
        assertTrue(state is AgentState.Idle)
    }

    @Test
    fun `transition from Running to Error`() {
        var state: AgentState = AgentState.Running(step = 2, thought = "working")
        state = AgentState.Error(message = "API timeout")
        assertTrue(state is AgentState.Error)
        assertEquals("API timeout", (state as AgentState.Error).message)
    }

    @Test
    fun `transition from Idle to Running to Error to Idle`() {
        var state: AgentState = AgentState.Idle()
        state = AgentState.Running(step = 1, thought = "go")
        state = AgentState.Error(message = "failed")
        state = AgentState.Idle()
        assertTrue(state is AgentState.Idle)
    }

    @Test
    fun `Running state tracks step progression`() {
        val state1 = AgentState.Running(step = 1, thought = "first")
        val state2 = AgentState.Running(step = 2, thought = "second")
        val state3 = AgentState.Running(step = 3, thought = "third")
        assertEquals(1, state1.step)
        assertEquals(2, state2.step)
        assertEquals(3, state3.step)
    }

    @Test
    fun `Paused state exists`() {
        val state: AgentState = AgentState.Paused
        assertTrue(state is AgentState.Paused)
    }

    @Test
    fun `WaitingForUser state holds question and step`() {
        val state = AgentState.WaitingForUser(question = "Which contact?", step = 3)
        assertTrue(state is AgentState.WaitingForUser)
        assertEquals("Which contact?", state.question)
        assertEquals(3, state.step)
    }

    @Test
    fun `transition from Running to WaitingForUser`() {
        var state: AgentState = AgentState.Running(step = 2, thought = "need info")
        state = AgentState.WaitingForUser(question = "What do you want?", step = 2)
        assertTrue(state is AgentState.WaitingForUser)
        assertEquals("What do you want?", (state as AgentState.WaitingForUser).question)
    }

    @Test
    fun `transition from WaitingForUser back to Running after answer`() {
        var state: AgentState = AgentState.WaitingForUser(question = "Confirm?", step = 1)
        state = AgentState.Running(step = 1, thought = "user answered, proceeding")
        assertTrue(state is AgentState.Running)
    }

    @Test
    fun `transition from WaitingForUser to Idle when stopped`() {
        var state: AgentState = AgentState.WaitingForUser(question = "Please clarify", step = 2)
        state = AgentState.Idle()
        assertTrue(state is AgentState.Idle)
    }

    @Test
    fun `WaitingForUser step matches Running step (ask_user does not increment step)`() {
        val runningStep = 3
        val waitingState = AgentState.WaitingForUser(question = "?", step = runningStep)
        // Step should be the same as it was before ask_user (not incremented)
        assertEquals(runningStep, waitingState.step)
    }

    // --- Completion verification tests ---

    @Test
    fun `side-effect actions should not allow immediate completion`() {
        val sideEffectActions = AgentEngine.SIDE_EFFECT_ACTIONS
        assertTrue("tap should be side-effect", "tap" in sideEffectActions)
        assertTrue("swipe should be side-effect", "swipe" in sideEffectActions)
        assertTrue("type_text should be side-effect", "type_text" in sideEffectActions)
        assertTrue("key_event should be side-effect", "key_event" in sideEffectActions)
        assertTrue("open_app should be side-effect", "open_app" in sideEffectActions)
        assertTrue("long_press should be side-effect", "long_press" in sideEffectActions)
    }

    @Test
    fun `non-side-effect actions allow immediate completion`() {
        val sideEffectActions = AgentEngine.SIDE_EFFECT_ACTIONS
        assertFalse("wait should not be side-effect", "wait" in sideEffectActions)
        assertFalse("note should not be side-effect", "note" in sideEffectActions)
        assertFalse("finish should not be side-effect", "finish" in sideEffectActions)
        assertFalse("fail should not be side-effect", "fail" in sideEffectActions)
        assertFalse("ask_user should not be side-effect", "ask_user" in sideEffectActions)
        assertFalse("handoff_user should not be side-effect", "handoff_user" in sideEffectActions)
        assertFalse("remember should not be side-effect", "remember" in sideEffectActions)
    }

    // --- StepResult tests ---

    @Test
    fun `StepResult holds all properties`() {
        val result = StepResult(
            step = 1,
            actionType = "tap",
            thought = "tapping button",
            success = true,
            detail = "tapped at (500, 300)",
            durationMs = 150L,
            completed = false
        )
        assertEquals(1, result.step)
        assertEquals("tap", result.actionType)
        assertEquals("tapping button", result.thought)
        assertTrue(result.success)
        assertEquals("tapped at (500, 300)", result.detail)
        assertEquals(150L, result.durationMs)
        assertFalse(result.completed)
    }

    @Test
    fun `StepResult completed flag for finish action`() {
        val result = StepResult(
            step = 5,
            actionType = "finish",
            thought = "task complete",
            success = true,
            detail = "completed",
            durationMs = 50L,
            completed = true
        )
        assertTrue(result.completed)
        assertEquals("finish", result.actionType)
    }

    // --- stripImagesFromHistory tests ---

    @Test
    fun `stripImagesFromHistory removes images from all but last message`() {
        val messages = buildConversationHistory(3)
        val stripped = stripImagesFromHistory(messages)

        // First two messages should have images removed
        for (i in 0 until stripped.length() - 1) {
            val msg = stripped.getJSONObject(i)
            if (msg.getString("role") == "user") {
                val content = msg.get("content")
                if (content is JSONArray) {
                    for (j in 0 until (content as JSONArray).length()) {
                        val part = (content as JSONArray).getJSONObject(j)
                        assertNotEquals("image_url", part.optString("type"))
                    }
                }
            }
        }

        // Last message should retain images
        val lastMsg = stripped.getJSONObject(stripped.length() - 1)
        if (lastMsg.getString("role") == "user") {
            val content = lastMsg.get("content")
            if (content is JSONArray) {
                var hasImage = false
                for (j in 0 until (content as JSONArray).length()) {
                    val part = (content as JSONArray).getJSONObject(j)
                    if (part.optString("type") == "image_url") {
                        hasImage = true
                    }
                }
                assertTrue("Last message should retain images", hasImage)
            }
        }
    }

    @Test
    fun `stripImagesFromHistory with single message keeps image`() {
        val messages = buildConversationHistory(1)
        val stripped = stripImagesFromHistory(messages)
        assertEquals(1, stripped.length())

        // Single message should keep its image
        val msg = stripped.getJSONObject(0)
        val content = msg.get("content")
        if (content is JSONArray) {
            var hasImage = false
            for (j in 0 until (content as JSONArray).length()) {
                if ((content as JSONArray).getJSONObject(j).optString("type") == "image_url") {
                    hasImage = true
                }
            }
            assertTrue("Single message should retain image", hasImage)
        }
    }

    @Test
    fun `stripImagesFromHistory with empty history returns empty`() {
        val messages = JSONArray()
        val stripped = stripImagesFromHistory(messages)
        assertEquals(0, stripped.length())
    }

    // --- Message trimming tests ---

    @Test
    fun `trimMessages keeps within 15 messages`() {
        val messages = JSONArray()
        for (i in 0 until 20) {
            messages.put(JSONObject().apply {
                put("role", if (i % 2 == 0) "user" else "assistant")
                put("content", "message $i")
            })
        }

        val trimmed = trimMessages(messages, maxMessages = 15)
        assertTrue("Should be at most 15 messages", trimmed.length() <= 15)
    }

    @Test
    fun `trimMessages preserves system message`() {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", "You are a phone assistant")
        })
        for (i in 0 until 20) {
            messages.put(JSONObject().apply {
                put("role", if (i % 2 == 0) "user" else "assistant")
                put("content", "message $i")
            })
        }

        val trimmed = trimMessages(messages, maxMessages = 15)
        // System message should be first
        assertEquals("system", trimmed.getJSONObject(0).getString("role"))
    }

    @Test
    fun `trimMessages keeps recent messages`() {
        val messages = JSONArray()
        for (i in 0 until 20) {
            messages.put(JSONObject().apply {
                put("role", if (i % 2 == 0) "user" else "assistant")
                put("content", "message $i")
            })
        }

        val trimmed = trimMessages(messages, maxMessages = 15)
        // Last message should be preserved
        val lastOriginal = messages.getJSONObject(messages.length() - 1).getString("content")
        val lastTrimmed = trimmed.getJSONObject(trimmed.length() - 1).getString("content")
        assertEquals(lastOriginal, lastTrimmed)
    }

    @Test
    fun `trimMessages with fewer than max returns all`() {
        val messages = JSONArray()
        for (i in 0 until 5) {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", "msg $i")
            })
        }
        val trimmed = trimMessages(messages, maxMessages = 15)
        assertEquals(5, trimmed.length())
    }

    // --- System prompt tests ---

    @Test
    fun `system prompt contains coordinate system description`() {
        val prompt = AgentEngine.SYSTEM_PROMPT
        assertTrue("Should mention 0-1000 range", prompt.contains("0") && prompt.contains("1000"))
        assertTrue("Should mention coordinates", prompt.contains("坐标") || prompt.contains("coordinate") || prompt.contains("Coordinate"))
    }

    @Test
    fun `system prompt contains all action types including ask_user`() {
        val prompt = AgentEngine.SYSTEM_PROMPT
        val actionTypes = listOf("tap", "swipe", "type_text", "key_event", "open_app", "wait", "finish", "fail", "ask_user")
        for (actionType in actionTypes) {
            assertTrue("System prompt should mention $actionType", prompt.contains(actionType))
        }
    }

    @Test
    fun `system prompt mentions ask_user with question parameter`() {
        val prompt = AgentEngine.SYSTEM_PROMPT
        assertTrue("System prompt should mention ask_user(question)", prompt.contains("ask_user(question)"))
    }

    @Test
    fun `system prompt has rule about asking user when uncertain`() {
        val prompt = AgentEngine.SYSTEM_PROMPT
        // Rule 6 should be present
        assertTrue("System prompt should have rule about asking user", prompt.contains("ask_user"))
        assertTrue("Rule about uncertainty should exist",
            prompt.contains("不确定") || prompt.contains("额外信息") ||
            prompt.contains("uncertain") || prompt.contains("additional information"))
    }

    @Test
    fun `system prompt mentions Android`() {
        val prompt = AgentEngine.SYSTEM_PROMPT
        assertTrue(prompt.contains("Android") || prompt.contains("android") || prompt.contains("手机"))
    }

    // --- Helper functions that mirror expected engine logic ---

    /**
     * Build a conversation history with user messages containing text + image.
     */
    private fun buildConversationHistory(numUserMessages: Int): JSONArray {
        val messages = JSONArray()
        for (i in 0 until numUserMessages) {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Step $i: observe the screen")
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,/9j/fake${i}")
                        })
                    })
                })
            })
            if (i < numUserMessages - 1) {
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", """{"thought":"step $i","action_type":"tap","x":500,"y":500}""")
                })
            }
        }
        return messages
    }

    /**
     * Strip images from all messages except the last one.
     * This is the pure logic we expect AgentEngine to implement.
     */
    private fun stripImagesFromHistory(messages: JSONArray): JSONArray {
        if (messages.length() == 0) return messages

        val result = JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            if (i < messages.length() - 1 && msg.getString("role") == "user") {
                val content = msg.opt("content")
                if (content is JSONArray) {
                    val filteredContent = JSONArray()
                    for (j in 0 until content.length()) {
                        val part = content.getJSONObject(j)
                        if (part.optString("type") != "image_url") {
                            filteredContent.put(part)
                        }
                    }
                    result.put(JSONObject().apply {
                        put("role", "user")
                        put("content", filteredContent)
                    })
                } else {
                    result.put(msg)
                }
            } else {
                result.put(msg)
            }
        }
        return result
    }

    /**
     * Trim messages to keep within maxMessages, preserving system message and recent messages.
     */
    private fun trimMessages(messages: JSONArray, maxMessages: Int): JSONArray {
        if (messages.length() <= maxMessages) return messages

        val result = JSONArray()
        var startIdx = 0

        // Check if first message is system
        if (messages.length() > 0 && messages.getJSONObject(0).optString("role") == "system") {
            result.put(messages.getJSONObject(0))
            startIdx = 1
        }

        // Keep the most recent messages
        val remaining = maxMessages - result.length()
        val skipCount = (messages.length() - startIdx) - remaining
        for (i in startIdx until messages.length()) {
            if (i - startIdx >= skipCount) {
                result.put(messages.getJSONObject(i))
            }
        }

        return result
    }

    /**
     * Build a system prompt matching the expected format.
     */
    private fun buildSystemPrompt(): String {
        return """You are an Android phone assistant. You control an Android device by observing the screen and performing actions.

## Your Capabilities

You can perform these actions:
- tap: Tap at coordinates (x, y) in 0-1000 normalized range
- long_press: Long press at coordinates (x, y)
- swipe: Swipe in a direction ("up", "down", "left", "right")
- type_text: Type text into the currently focused input field
- key_event: Press a key ("back", "home", "enter", "recent")
- open_app: Open an app by name
- wait: Wait for the screen to update
- finish: The task has been completed successfully
- fail: The task cannot be completed, explain why

## Coordinate System

All coordinates use a normalized 0-1000 range:
- (0, 0) = top-left corner
- (1000, 1000) = bottom-right corner
- (500, 500) = center of screen

## Output Format

Respond with a function call using the phone_action tool.

## Rules

- After typing text, you usually need to press "enter" or tap a send/search button
- If the screen hasn't changed after an action, try a different approach
- If you're stuck after 3 attempts, use "fail" with an explanation
- Always explain your reasoning in the "thought" field"""
    }
}
