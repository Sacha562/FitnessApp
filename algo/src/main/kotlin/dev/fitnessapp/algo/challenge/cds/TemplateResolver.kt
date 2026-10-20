package dev.fitnessapp.algo.challenge.cds

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Template inheritance of CHALLENGE_ENGINE 6.2 on the raw JSON: from the root ancestor down, each child
 * applies `replace`, `add`, `prepend`, `override` in that order. Block references are expanded where they
 * appear (one level), so "after the leading warm-up items" and `after` keys see the expanded list. Header
 * fields (`slot`, `name`, `bookedMin`, ...) are inherited unless the child sets them. Problems are V05.
 */
class TemplateResolver(private val doc: DefinitionDoc) {
    class Resolved(val id: String, val header: Map<String, JsonElement>, val items: List<JsonObject>)

    private val errors = ArrayList<ValidationError>()
    private val done = LinkedHashMap<String, Resolved>()

    fun errors(): List<ValidationError> = errors.toList()

    fun resolveAll(): Map<String, Resolved> {
        for (id in doc.templates.keys.sorted()) resolve(id, emptyList())
        return done
    }

    private fun err(pointer: String, msg: String) {
        errors += ValidationError("V05", pointer, msg)
    }

    private fun resolve(id: String, stack: List<String>): Resolved? {
        done[id]?.let { return it }
        val raw = doc.templates[id] ?: return null
        if (id in stack) {
            err("/templates/$id/extends", "inheritance cycle ${(stack + id).joinToString(" -> ")}")
            return null
        }
        val ptr = "/templates/$id"
        val parentId = (raw["extends"] as? JsonPrimitive)?.contentOrNull
        val header = LinkedHashMap<String, JsonElement>()
        val items: MutableList<JsonObject>
        if (parentId == null) {
            val own = raw["items"] as? JsonArray
            if (own == null) {
                err(ptr, "a template without 'extends' needs 'items'")
                return null
            }
            items = expand(own.map { it as JsonObject }, "$ptr/items").toMutableList()
        } else {
            val parent = resolve(parentId, stack + id)
            if (parent == null) {
                if (doc.templates[parentId] == null) err("$ptr/extends", "unknown parent template $parentId")
                return null
            }
            header.putAll(parent.header)
            items = parent.items.toMutableList()
            if (raw["items"] != null) err("$ptr/items", "a child template may not redefine 'items'")
            applyReplace(items, raw["replace"] as? JsonObject, "$ptr/replace", allowExercise = true)
            applyAdd(items, raw["add"] as? JsonArray, "$ptr/add")
            applyPrepend(items, raw["prepend"] as? JsonArray, "$ptr/prepend")
            applyReplace(items, raw["override"] as? JsonObject, "$ptr/override", allowExercise = false)
        }
        for ((k, v) in raw) if (k !in OPERATORS) header[k] = v
        val keys = HashCheck()
        items.forEachIndexed { i, item ->
            val key = (item["key"] as? JsonPrimitive)?.contentOrNull
            if (key == null) err("$ptr/items/$i", "item without key") else if (!keys.add(key)) err("$ptr/items/$i", "duplicate item key $key")
        }
        val r = Resolved(id, header, items)
        done[id] = r
        return r
    }

    /** Ordered set helper (no hash iteration in outputs). */
    private class HashCheck {
        private val seen = sortedSetOf<String>()
        fun add(k: String): Boolean = seen.add(k)
    }

    private fun expand(items: List<JsonObject>, ptr: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        items.forEachIndexed { i, item ->
            val blockName = (item["block"] as? JsonPrimitive)?.contentOrNull
            if (blockName == null) {
                out += item
            } else {
                if (item.size != 1) err("$ptr/$i", "a block reference has no other fields")
                val block = doc.blocks[blockName]
                if (block == null) err("$ptr/$i/block", "unknown block $blockName")
                block?.forEach { b ->
                    if (b["block"] != null) err("/blocks/$blockName", "blocks may not reference blocks")
                    out += b
                }
            }
        }
        return out
    }

    private fun keyOf(o: JsonObject): String? = (o["key"] as? JsonPrimitive)?.contentOrNull

    private fun applyReplace(items: MutableList<JsonObject>, ops: JsonObject?, ptr: String, allowExercise: Boolean) {
        ops ?: return
        for ((key, fieldsEl) in ops) {
            val fields = fieldsEl as? JsonObject
            if (fields == null) {
                err("$ptr/$key", "expected an object of fields")
                continue
            }
            val idx = items.indexOfFirst { keyOf(it) == key }
            if (idx < 0) {
                err("$ptr/$key", "no item with key $key in the parent")
                continue
            }
            if (!allowExercise && fields.containsKey("exercise")) err("$ptr/$key/exercise", "override may not change the exercise")
            if (fields.containsKey("key")) err("$ptr/$key/key", "the item key cannot change")
            items[idx] = merge(items[idx], fields)
        }
    }

    private fun applyAdd(items: MutableList<JsonObject>, ops: JsonArray?, ptr: String) {
        ops ?: return
        val added = expand(ops.map { it as JsonObject }, ptr)
        added.forEachIndexed { i, item ->
            val after = (item["after"] as? JsonPrimitive)?.contentOrNull
            if (after != null) {
                val idx = items.indexOfFirst { keyOf(it) == after }
                if (idx < 0) {
                    err("$ptr/$i/after", "no item with key $after")
                    items += item
                } else {
                    items.add(idx + 1, item)
                }
            } else {
                // End of the main items: before the trailing prehab items.
                var idx = items.size
                while (idx > 0 && roleOf(items[idx - 1]) == "prehab") idx--
                items.add(idx, item)
            }
        }
    }

    private fun applyPrepend(items: MutableList<JsonObject>, ops: JsonArray?, ptr: String) {
        ops ?: return
        val toInsert = ArrayList<JsonObject>()
        ops.forEachIndexed { i, e ->
            when (e) {
                is JsonPrimitive -> {
                    val block = doc.blocks[e.content]
                    if (block == null) err("$ptr/$i", "unknown block ${e.content}") else toInsert += block
                }
                is JsonObject -> toInsert += expand(listOf(e), ptr)
                else -> err("$ptr/$i", "expected a block name or an item")
            }
        }
        var idx = 0
        while (idx < items.size && roleOf(items[idx]) == "warmup") idx++
        items.addAll(idx, toInsert)
    }

    private fun roleOf(o: JsonObject): String? = (o["role"] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val OPERATORS = setOf("extends", "items", "replace", "add", "prepend", "override")

        /** Shallow merge: given fields replace fields of the same name; a field set to null is removed. */
        fun merge(base: JsonObject, fields: JsonObject): JsonObject {
            val out = LinkedHashMap<String, JsonElement>(base)
            for ((k, v) in fields) {
                if (v is JsonNull) out.remove(k) else out[k] = v
            }
            return JsonObject(out)
        }

        fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        fun int(e: JsonElement?): Int? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

        fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    }
}
