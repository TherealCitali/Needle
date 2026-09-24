package dev.citali.needle.engine

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool catalogue is compiled into the engine's decode grammar, so the shape
 * of these schemas is part of the runtime contract with Needle and worth pinning.
 */
class NeedleToolSchemaTest {

    private fun tool(name: String) = NeedleTool(
        name = name,
        description = "Turn the torch on or off.",
        params = listOf(
            NeedleParam("state", "string", "on or off", required = true, enumValues = listOf("on", "off")),
            NeedleParam("brightness", "integer", "0-255", required = false),
        ),
        handler = { "ok" },
    )

    @Test
    fun schemaUsesTheObjectShapeNeedleExpects() {
        val schema = tool("set_torch").toSchema()
        assertEquals("set_torch", schema.getString("name"))
        assertEquals("Turn the torch on or off.", schema.getString("description"))

        val parameters = schema.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))

        val state = parameters.getJSONObject("properties").getJSONObject("state")
        assertEquals("string", state.getString("type"))
        assertEquals("on or off", state.getString("description"))
        assertEquals("on", state.getJSONArray("enum").getString(0))
        assertEquals("off", state.getJSONArray("enum").getString(1))
    }

    @Test
    fun onlyRequiredParametersAreListedAsRequired() {
        val required = tool("set_torch").toSchema()
            .getJSONObject("parameters")
            .getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("state", required.getString(0))
    }

    @Test
    fun aToolWithoutParametersStillCarriesAnEmptyObject() {
        val schema = NeedleTool("get_battery", "Reads the battery level.") { "80" }.toSchema()
        val parameters = schema.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
        assertFalse(parameters.has("required"))
    }

    @Test
    fun schemaArrayIsValidJsonInOrder() {
        val array = JSONArray(NeedleTool.schemaArray(listOf(tool("first"), tool("second"))))
        assertEquals(2, array.length())
        assertEquals("first", array.getJSONObject(0).getString("name"))
        assertEquals("second", array.getJSONObject(1).getString("name"))
        assertTrue(array.getJSONObject(1).has("parameters"))
    }
}
