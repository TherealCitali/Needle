package dev.citali.needle.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * A tool exposed to the on-device model.
 *
 * The schema shape mirrors the one `cactus-needle` builds from Python type
 * hints: `{name, description, parameters:{type:"object", properties:{...},
 * required:[...]}}`. Needle's decode grammar compiles the schema into a byte
 * level constraint, so a returned call always parses and its arguments always
 * match the declared types.
 */
class NeedleTool(
    val name: String,
    val description: String,
    val params: List<NeedleParam> = emptyList(),
    val handler: suspend (JSONObject) -> String,
) {
    fun toSchema(): JSONObject {
        val properties = JSONObject()
        params.forEach { param -> properties.put(param.name, param.toJson()) }
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        val required = params.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) parameters.put("required", JSONArray(required))
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)
    }

    companion object {
        fun schemaArray(tools: List<NeedleTool>): String {
            val array = JSONArray()
            tools.forEach { array.put(it.toSchema()) }
            return array.toString()
        }
    }
}

data class NeedleParam(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = true,
    val enumValues: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        if (description.isNotBlank()) put("description", description)
        if (enumValues.isNotEmpty()) put("enum", JSONArray(enumValues))
    }
}
