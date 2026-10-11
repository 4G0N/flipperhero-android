package com.flipperhero.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** Mirrors Swift `JSONValue`: a decoded JSON tree with convenience accessors. Backed by Gson. */
sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Arr(val value: List<JsonValue>) : JsonValue()
    data class Obj(val value: Map<String, JsonValue>) : JsonValue()

    operator fun get(key: String): JsonValue? = (this as? Obj)?.value?.get(key)
    val stringValue: String? get() = (this as? Str)?.value
    val boolValue: Boolean? get() = (this as? Bool)?.value
    val numberValue: Double? get() = (this as? Num)?.value

    fun toElement(): JsonElement = when (this) {
        is Null -> JsonNull.INSTANCE
        is Bool -> JsonPrimitive(value)
        is Num -> JsonPrimitive(value)
        is Str -> JsonPrimitive(value)
        is Arr -> JsonArray().apply { value.forEach { add(it.toElement()) } }
        is Obj -> JsonObject().apply { value.forEach { (k, v) -> add(k, v.toElement()) } }
    }

    companion object {
        /** Throws [com.google.gson.JsonParseException] on invalid JSON. */
        fun parse(text: String): JsonValue = from(JsonParser.parseString(text))

        fun from(element: JsonElement): JsonValue = when {
            element.isJsonNull -> Null
            element.isJsonObject -> Obj(element.asJsonObject.entrySet().associate { it.key to from(it.value) })
            element.isJsonArray -> Arr(element.asJsonArray.map { from(it) })
            element.isJsonPrimitive -> {
                val p = element.asJsonPrimitive
                when {
                    p.isBoolean -> Bool(p.asBoolean)
                    p.isNumber -> Num(p.asDouble)
                    else -> Str(p.asString)
                }
            }
            else -> Null
        }

        // Builders for assembling tool-parameter schemas.
        fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = Obj(linkedMapOf(*pairs))
        fun arr(items: List<JsonValue>): JsonValue = Arr(items)
        fun str(value: String): JsonValue = Str(value)
        fun bool(value: Boolean): JsonValue = Bool(value)
    }
}
