package studio.zavi.uniremote.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Small null-safe helpers over kotlinx.serialization's JsonElement tree. */
object JsonX {
    fun parseObject(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { p ->
    p.booleanOrNull ?: p.contentOrNull?.toBooleanStrictOrNull()
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

val JsonElement.asObject: JsonObject? get() = this as? JsonObject
