package dev.fitnessapp.algo.challenge.cds

import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.registry.ModuleRef
import dev.fitnessapp.algo.core.registry.ModuleRegistry
import dev.fitnessapp.algo.core.registry.SemVer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Result of loading a definition: compiled, or the errors of the first failing stage (warnings included). */
sealed class LoadResult {
    data class Ok(val definition: CompiledDefinition, val warnings: List<ValidationError>) : LoadResult()
    data class Err(val errors: List<ValidationError>) : LoadResult()

    fun orThrow(): CompiledDefinition = when (this) {
        is Ok -> definition
        is Err -> throw IllegalArgumentException("definition invalid:\n" + errors.joinToString("\n"))
    }
}

/**
 * `challenge.cds@1.0.0` (CHALLENGE_ENGINE 2): load, validate (stage 1 structural, stage 2 semantic) and
 * compile a definition. Stage 3 (golden simulation) runs in `:sim`.
 */
object Cds {
    val ref: ModuleRef = ModuleRegistry.ref("challenge.cds")

    const val SCHEMA: String = "fitapp.challenge/1"

    /** V16 size limits. */
    const val MAX_BYTES: Int = 512 * 1024
    const val MAX_RULES: Int = 300
    const val MAX_TEMPLATES: Int = 100

    private val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    private val SLUG = Regex("^[a-z][a-z0-9_]{1,31}$")

    fun load(text: String, ctx: ValidationContext): LoadResult {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return LoadResult.Err(listOf(ValidationError("S1", "", "not JSON: ${e.message}")))
        }
        val errors = ArrayList<ValidationError>()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) errors += ValidationError("V16", "", "definition larger than $MAX_BYTES bytes")
        return load(element, ctx, errors)
    }

    fun load(element: JsonElement, ctx: ValidationContext, initial: List<ValidationError> = emptyList()): LoadResult {
        val errors = ArrayList(initial)
        val doc = try {
            strict.decodeFromJsonElement(DefinitionDoc.serializer(), element)
        } catch (e: SerializationException) {
            errors += ValidationError("S1", pointerOf(e.message), e.message ?: "structural error")
            return LoadResult.Err(errors)
        } catch (e: IllegalArgumentException) {
            errors += ValidationError("S1", pointerOf(e.message), e.message ?: "structural error")
            return LoadResult.Err(errors)
        }
        errors += header(doc, ctx)
        if (errors.any { !it.warning && it.id == "S1" }) return LoadResult.Err(errors)
        val sha = Jcs.sha256Hex(element)
        val compiler = DefinitionCompiler(doc, ctx, sha)
        val compiled = compiler.compile()
        errors += compiler.errors()
        if (compiled != null) errors += SemanticValidator(doc, compiled, ctx).check()
        val hard = errors.filter { !it.warning }
        return if (compiled == null || hard.isNotEmpty()) LoadResult.Err(errors) else LoadResult.Ok(compiled, errors.filter { it.warning })
    }

    private fun header(doc: DefinitionDoc, ctx: ValidationContext): List<ValidationError> {
        val out = ArrayList<ValidationError>()
        if (doc.schema != SCHEMA) out += ValidationError("S1", "/schema", "expected $SCHEMA")
        if (!SLUG.matches(doc.id)) out += ValidationError("S1", "/id", "slug must match ${SLUG.pattern}")
        if (runCatching { SemVer.parse(doc.version) }.isFailure) out += ValidationError("S1", "/version", "not a semantic version")
        val range = runCatching { SemverRange.parse(doc.engine) }.getOrNull()
        if (range == null) {
            out += ValidationError("S1", "/engine", "not a semver range")
        } else if (!range.contains(SemVer.parse(ctx.engineVersion))) {
            out += ValidationError("S1", "/engine", "engine ${ctx.engineVersion} outside ${doc.engine}")
        }
        for (f in listOf("title" to doc.title, "summary" to doc.summary)) {
            if (f.second["en"].isNullOrBlank()) out += ValidationError("S1", "/${f.first}/en", "English text is required (D12)")
        }
        if (doc.rules.size > MAX_RULES) out += ValidationError("V16", "/rules", "more than $MAX_RULES rules")
        if (doc.templates.size > MAX_TEMPLATES) out += ValidationError("V16", "/templates", "more than $MAX_TEMPLATES templates")
        return out
    }

    /** kotlinx messages end with "at path: $.a.b[2]"; turn that into a JSON pointer. */
    private fun pointerOf(message: String?): String {
        val m = Regex("""path:? \$([^\s]*)""").find(message ?: return "") ?: return ""
        return m.groupValues[1].replace(Regex("""\[(\d+)]"""), ".$1").replace('.', '/').let { if (it.isEmpty() || it.startsWith("/")) it else "/$it" }
    }
}

/** A conjunction of comparators such as `>=1.0.0 <2.0.0` (2.2). */
class SemverRange private constructor(private val parts: List<Pair<String, SemVer>>) {
    fun contains(v: SemVer): Boolean = parts.all { (op, x) ->
        val c = v.compareTo(x)
        when (op) {
            ">=" -> c >= 0
            ">" -> c > 0
            "<=" -> c <= 0
            "<" -> c < 0
            else -> c == 0
        }
    }

    companion object {
        private val PART = Regex("^(>=|<=|>|<|=)?(\\d+\\.\\d+\\.\\d+)$")

        fun parse(text: String): SemverRange {
            val parts = text.trim().split(Regex("\\s+")).map {
                val m = requireNotNull(PART.matchEntire(it)) { "bad comparator $it" }
                (m.groupValues[1].ifEmpty { "=" }) to SemVer.parse(m.groupValues[2])
            }
            require(parts.isNotEmpty()) { "empty range" }
            return SemverRange(parts)
        }
    }
}
