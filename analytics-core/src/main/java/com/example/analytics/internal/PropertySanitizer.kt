package com.example.analytics.internal

import com.example.analytics.AnalyticsLogger
import com.example.analytics.PropertyLimits
import com.example.analytics.ReservedKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts arbitrary key/value maps into JSON the backend can trust.
 *
 * Supported values: String/CharSequence, Number, Boolean, null, Char, Enum, arrays, collections and
 * nested maps. Anything else is recorded via `toString()` with a warning. NaN/Infinity (not valid
 * JSON) and values nested deeper than [PropertyLimits.maxDepth] are dropped. Top-level keys that
 * collide with [ReservedKeys] are dropped.
 */
internal class PropertySanitizer(
    private val limits: PropertyLimits,
    private val logger: AnalyticsLogger,
) {

    /** @return the trimmed name, or null if it is unusable. */
    fun sanitizeName(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> null.also { logger.warn("Dropping event with blank name") }
            trimmed.length > limits.maxNameLength ->
                null.also { logger.warn("Dropping event: name longer than ${limits.maxNameLength} chars") }
            else -> trimmed
        }
    }

    fun sanitize(properties: Map<String, Any?>): JSONObject {
        val result = JSONObject()
        for ((rawKey, value) in properties) {
            if (result.length() >= limits.maxProperties) {
                logger.warn("More than ${limits.maxProperties} properties; extra keys dropped")
                break
            }
            val key = sanitizeKey(rawKey) ?: continue
            if (ReservedKeys.isReserved(key)) {
                logger.warn("Property '$key' is reserved by the SDK and was dropped")
                continue
            }
            putIfValid(result, key, value, depth = 1)
        }
        return result
    }

    private fun sanitizeKey(raw: Any?): String? {
        val key = raw?.toString()?.trim().orEmpty()
        return when {
            key.isEmpty() -> null.also { logger.warn("Dropping property with blank key") }
            key.length > limits.maxKeyLength ->
                null.also { logger.warn("Dropping property '${key.take(32)}…': key too long") }
            else -> key
        }
    }

    private fun putIfValid(target: JSONObject, key: String, value: Any?, depth: Int) {
        val converted = convert(value, depth, key)
        if (converted !== Invalid) target.put(key, converted)
    }

    private fun convert(value: Any?, depth: Int, path: String): Any? {
        if (depth > limits.maxDepth) {
            logger.warn("Property '$path' nested deeper than ${limits.maxDepth} levels; dropped")
            return Invalid
        }
        return when (value) {
            null -> JSONObject.NULL
            is Boolean -> value
            is Double -> if (value.isFinite()) value else invalidNumber(path)
            is Float -> if (value.isFinite()) value.toDouble() else invalidNumber(path)
            is Int, is Long, is Short, is Byte -> value
            is Number -> value.toDouble().let { if (it.isFinite()) it else invalidNumber(path) }
            is CharSequence -> truncate(value.toString(), path)
            is Char -> value.toString()
            is Enum<*> -> value.name
            is Map<*, *> -> convertMap(value, depth, path)
            is Collection<*> -> convertIterable(value, depth, path)
            is Array<*> -> convertIterable(value.asList(), depth, path)
            is IntArray -> convertIterable(value.asList(), depth, path)
            is LongArray -> convertIterable(value.asList(), depth, path)
            is DoubleArray -> convertIterable(value.asList(), depth, path)
            is FloatArray -> convertIterable(value.asList(), depth, path)
            is BooleanArray -> convertIterable(value.asList(), depth, path)
            is JSONObject, is JSONArray -> value
            else -> {
                logger.warn("Property '$path' has unsupported type ${value.javaClass.name}; using toString()")
                truncate(value.toString(), path)
            }
        }
    }

    private fun convertMap(map: Map<*, *>, depth: Int, path: String): JSONObject {
        val result = JSONObject()
        for ((rawKey, nested) in map) {
            val key = sanitizeKey(rawKey) ?: continue
            putIfValid(result, key, nested, depth + 1)
        }
        return result
    }

    private fun convertIterable(items: Collection<*>, depth: Int, path: String): JSONArray {
        if (items.size > limits.maxCollectionSize) {
            logger.warn("Property '$path' has ${items.size} items; truncated to ${limits.maxCollectionSize}")
        }
        val result = JSONArray()
        items.asSequence().take(limits.maxCollectionSize).forEach { item ->
            val converted = convert(item, depth + 1, "$path[]")
            if (converted !== Invalid) result.put(converted)
        }
        return result
    }

    private fun truncate(value: String, path: String): String {
        if (value.length <= limits.maxStringLength) return value
        logger.warn("Property '$path' truncated to ${limits.maxStringLength} chars")
        return value.take(limits.maxStringLength)
    }

    private fun invalidNumber(path: String): Any {
        logger.warn("Property '$path' is NaN/Infinity (not valid JSON); dropped")
        return Invalid
    }

    private object Invalid
}
