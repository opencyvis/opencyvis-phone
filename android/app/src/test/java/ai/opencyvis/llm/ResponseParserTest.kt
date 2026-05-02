package ai.opencyvis.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for parsing LLM API responses.
 *
 * The ResponseParser (or equivalent logic) is expected to:
 * 1. Extract function_call from the output array
 * 2. Fall back to parsing JSON from text output
 * 3. Handle malformed/empty responses gracefully
 */
class ResponseParserTest {

    /**
     * Helper: build a function_call response JSON string.
     */
    private fun buildFunctionCallResponse(
        name: String = "phone_action",
        arguments: String
    ): String {
        return JSONObject().apply {
            put("id", "resp_test")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "function_call")
                    put("name", name)
                    put("arguments", arguments)
                })
            })
        }.toString()
    }

    /**
     * Helper: build a text message response.
     */
    private fun buildTextResponse(text: String): String {
        return JSONObject().apply {
            put("id", "resp_text")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "message")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", text)
                        })
                    })
                })
            })
        }.toString()
    }

    // --- function_call parsing tests ---

    @Test
    fun `parse function_call extracts arguments correctly`() {
        val args = """{"thought":"I see the home screen","action_type":"tap","x":500,"y":300,"completed":false}"""
        val response = buildFunctionCallResponse(arguments = args)
        val json = JSONObject(response)
        val output = json.getJSONArray("output")
        val item = output.getJSONObject(0)

        assertEquals("function_call", item.getString("type"))
        assertEquals("phone_action", item.getString("name"))

        val parsed = JSONObject(item.getString("arguments"))
        assertEquals("tap", parsed.getString("action_type"))
        assertEquals(500, parsed.getInt("x"))
        assertEquals(300, parsed.getInt("y"))
        assertEquals("I see the home screen", parsed.getString("thought"))
        assertFalse(parsed.getBoolean("completed"))
    }

    @Test
    fun `parse function_call with finish action`() {
        val args = """{"thought":"Task completed successfully","action_type":"finish","completed":true}"""
        val response = buildFunctionCallResponse(arguments = args)
        val json = JSONObject(response)
        val output = json.getJSONArray("output")
        val item = output.getJSONObject(0)
        val parsed = JSONObject(item.getString("arguments"))

        assertEquals("finish", parsed.getString("action_type"))
        assertTrue(parsed.getBoolean("completed"))
    }

    @Test
    fun `parse function_call with all action types`() {
        val actionTypes = listOf(
            "tap", "long_press", "open_app", "swipe",
            "key_event", "type_text", "wait", "finish", "fail"
        )
        for (actionType in actionTypes) {
            val args = """{"thought":"test","action_type":"$actionType","completed":false}"""
            val response = buildFunctionCallResponse(arguments = args)
            val json = JSONObject(response)
            val output = json.getJSONArray("output")
            val item = output.getJSONObject(0)
            val parsed = JSONObject(item.getString("arguments"))
            assertEquals(actionType, parsed.getString("action_type"))
        }
    }

    // --- Text fallback parsing tests ---

    @Test
    fun `parse text fallback extracts JSON from plain text`() {
        val jsonStr = """{"thought":"fallback","action_type":"wait","completed":false}"""
        val response = buildTextResponse(jsonStr)
        val json = JSONObject(response)
        val output = json.getJSONArray("output")
        val message = output.getJSONObject(0)
        val content = message.getJSONArray("content")
        val text = content.getJSONObject(0).getString("text")

        val parsed = JSONObject(text)
        assertEquals("wait", parsed.getString("action_type"))
    }

    @Test
    fun `parse text fallback extracts JSON from markdown code block`() {
        val text = """Here is the action:
```json
{"thought":"found it","action_type":"tap","x":100,"y":200,"completed":false}
```"""
        // Extract JSON from code block
        val jsonPattern = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = jsonPattern.find(text)
        assertNotNull("Should find JSON in code block", match)
        val parsed = JSONObject(match!!.groupValues[1])
        assertEquals("tap", parsed.getString("action_type"))
        assertEquals(100, parsed.getInt("x"))
    }

    @Test
    fun `parse text fallback extracts JSON from mixed text`() {
        val text = """I'll tap the settings icon. {"thought":"opening settings","action_type":"tap","x":500,"y":300,"completed":false}"""
        // Extract first JSON object from text
        val startIdx = text.indexOf('{')
        assertTrue("Should find opening brace", startIdx >= 0)

        // Simple bracket-matching extraction
        var depth = 0
        var endIdx = -1
        for (i in startIdx until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        endIdx = i
                        break
                    }
                }
            }
        }
        assertTrue("Should find closing brace", endIdx > startIdx)

        val jsonStr = text.substring(startIdx, endIdx + 1)
        val parsed = JSONObject(jsonStr)
        assertEquals("tap", parsed.getString("action_type"))
    }

    // --- Malformed JSON handling ---

    @Test
    fun `handle malformed JSON gracefully`() {
        val malformedArgs = """{"thought":"broken","action_type":"tap","x":}"""
        try {
            JSONObject(malformedArgs)
            fail("Should throw on malformed JSON")
        } catch (e: org.json.JSONException) {
            // Expected - malformed JSON should be caught
            assertNotNull(e.message)
        }
    }

    @Test
    fun `handle truncated JSON`() {
        val truncated = """{"thought":"cut off","action_typ"""
        try {
            JSONObject(truncated)
            fail("Should throw on truncated JSON")
        } catch (e: org.json.JSONException) {
            assertNotNull(e.message)
        }
    }

    // --- Empty response handling ---

    @Test
    fun `handle empty output array`() {
        val response = JSONObject().apply {
            put("id", "resp_empty")
            put("output", JSONArray())
        }
        val output = response.getJSONArray("output")
        assertEquals(0, output.length())
    }

    @Test
    fun `handle response with no output field`() {
        val response = JSONObject().apply {
            put("id", "resp_no_output")
        }
        assertFalse(response.has("output"))
    }

    @Test
    fun `handle null arguments in function call`() {
        val response = JSONObject().apply {
            put("id", "resp_null")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "function_call")
                    put("name", "phone_action")
                    put("arguments", JSONObject.NULL)
                })
            })
        }
        val item = response.getJSONArray("output").getJSONObject(0)
        assertTrue(item.isNull("arguments"))
    }

    // --- incomplete_details handling ---

    @Test
    fun `handle incomplete_details in response`() {
        val response = JSONObject().apply {
            put("id", "resp_incomplete")
            put("status", "incomplete")
            put("incomplete_details", JSONObject().apply {
                put("reason", "max_output_tokens")
            })
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "message")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", """{"thought":"partial","action_type":"wait"}""")
                        })
                    })
                })
            })
        }

        assertEquals("incomplete", response.getString("status"))
        assertTrue(response.has("incomplete_details"))
        assertEquals("max_output_tokens",
            response.getJSONObject("incomplete_details").getString("reason"))

        // Should still be able to parse whatever output was provided
        val output = response.getJSONArray("output")
        assertTrue(output.length() > 0)
    }

    @Test
    fun `handle multiple output items picks first function_call`() {
        val response = JSONObject().apply {
            put("id", "resp_multi")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "message")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", "thinking...")
                        })
                    })
                })
                put(JSONObject().apply {
                    put("type", "function_call")
                    put("name", "phone_action")
                    put("arguments", """{"thought":"act","action_type":"tap","x":1,"y":2,"completed":false}""")
                })
            })
        }

        val output = response.getJSONArray("output")
        var functionCall: JSONObject? = null
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.getString("type") == "function_call") {
                functionCall = item
                break
            }
        }

        assertNotNull("Should find function_call in output", functionCall)
        assertEquals("phone_action", functionCall!!.getString("name"))
    }
}
