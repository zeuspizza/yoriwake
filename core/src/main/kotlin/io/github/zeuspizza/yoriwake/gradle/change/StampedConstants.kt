package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import io.github.zeuspizza.yoriwake.gradle.bytecode.TaskArtifacts

/**
 * Compile-time constants whose value moved with no source change behind them: a build that stamps
 * the time or the commit into a constant (Android's `BuildConfig`) changes every class that inlined
 * it on every build. Each selecting run names such constants; with the build's `stampedConstants`
 * declaring them unread by any test, a class whose only change is their values is no change.
 */
internal object StampedConstants {

    /** What the map recorded and the build declares, for one selecting run. */
    class Rule(
        /** `constant-fields`; null for a map recorded before it. */
        val recordedFields: Map<String, String>?,
        /** `class-digests-masked`; null when the capture was not under a declaration. */
        val recordedMasked: Map<String, String>?,
        /** The build's `stampedConstants`, `<class>#<field>` each. */
        val declared: List<String>,
        /** Whether a class named [className], compiled from [sourceFile], has a tracked source file. */
        val tracked: (className: String, sourceFile: String) -> Boolean,
    )

    /** What [apply] found. */
    class Found(
        /** [DigestWidening.added] less the classes whose only change is declared constants' values. */
        val added: Set<String>,
        /** Each class whose source did not change and whose constants moved, with those fields; empty fields when the map cannot name them. */
        val moved: Map<String, List<String>>,
        /** How many of the other changed classes load one of the moved constants' new values. */
        val consumers: Int,
        /** Each declared constant that moved, `<class>#<field>`, with its value's digest at capture and now. */
        val declaredMoved: Map<String, Pair<String, String>>,
        /** The classes left out for it. */
        val masked: Set<String>,
        /** Why nothing was masked although the build declares constants; null when something was, or nothing is declared. */
        val unmasked: String?,
        /** Entries naming no constant field of the build's own classes, which count as undeclared. */
        val invalid: List<String>,
    )

    /**
     * Over [added], the classes whose bytes differ from the map's: those not under [changedPrefixes]
     * (their source did not change) whose constant fields moved are named; and when every constant
     * that moved, under a changed prefix or not, is one [Rule.declared] names, a class not under them
     * whose masked digest is the recorded one is left out. Only a declared constant's holder, or a
     * class compiled from a tracked source file, is left out this way: a generated class may hold a
     * literal that changed for another reason.
     */
    fun apply(rule: Rule, added: Set<String>, changedPrefixes: Collection<String>, artifacts: TaskArtifacts): Found? {
        val unchangedSource = io.github.zeuspizza.yoriwake.agent.select.Selector.bytesOnly(added, changedPrefixes)
        // What each changed class holds now; a class this build no longer has holds nothing.
        val bytes = mutableMapOf<String, ByteArray>()
        for (name in added) {
            artifacts.classesFor(name.substringBefore('$'))?.firstOrNull { it.first == name }?.let { bytes[name] = it.second }
        }
        val fieldsNow = bytes.mapValues { (_, b) -> Recordability.constantFields(b) ?: return null }
        val recorded = rule.recordedFields
        val movedFields = mutableMapOf<String, List<String>>()
        val declaredMoved = linkedMapOf<String, Pair<String, String>>()
        for (name in added) {
            val now = fieldsNow[name].orEmpty().associate { "${it.name}:${it.descriptor}" to it.valueDigest }
            if (recorded == null) {
                if (name in unchangedSource && now.isNotEmpty()) movedFields[name] = emptyList()
                continue
            }
            val then = recorded.filterKeys { it.substringBefore('#') == name }.mapKeys { it.key.substringAfter('#') }
            val moved = (then.keys + now.keys).filter { then[it] != now[it] }.sorted()
            if (moved.isEmpty()) continue
            if (name in unchangedSource) movedFields[name] = moved.map { it.substringBefore(':') }
            for (field in moved) declaredMoved["$name#${field.substringBefore(':')}"] = (then[field] ?: "none") to (now[field] ?: "none")
        }
        if (movedFields.isEmpty() && declaredMoved.isEmpty()) return null

        val all = artifacts.constantFields()
        val mask = all?.let(Recordability::ConstantMask)
        val newValues = movedFields.flatMap { (holder, fields) ->
            fieldsNow[holder].orEmpty().filter { it.name in fields }.map { it.value }
        }.toSet()
        val consumers = (unchangedSource - movedFields.keys).count { name ->
            bytes[name]?.let { b -> newValues.any { mask?.fieldOf(it) != null && loads(b, it) } } == true
        }

        val effective = rule.declared.filter { entry ->
            val owner = entry.substringBefore('#')
            all?.get(owner)?.any { it.name == entry.substringAfter('#', "") } == true
        }
        val invalid = rule.declared - effective.toSet()
        fun found(masked: Set<String>, unmasked: String?) =
            Found(added - masked, movedFields, consumers, declaredMoved.filterKeys { it in effective }, masked, unmasked, invalid)
        if (rule.declared.isEmpty()) return found(emptySet(), null)
        val undeclared = declaredMoved.keys.filter { it !in effective }
        val why = when {
            recorded == null -> "the map records no constant fields, so which constants moved is not known; a capture records them"
            rule.recordedMasked == null -> "the map was not captured under stampedConstants; a capture records what it needs"
            mask == null -> "the build's constants could not be read"
            undeclared.isNotEmpty() -> "${undeclared.take(NAMED).joinToString()} moved too and stampedConstants does not declare " +
                (if (undeclared.size > NAMED) "them (and ${undeclared.size - NAMED} more)" else "them")
            else -> null
        }
        if (why != null) return found(emptySet(), why)
        val holders = effective.map { it.substringBefore('#') }.toSet()
        val masked = unchangedSource.filterTo(sortedSetOf()) { name ->
            val b = bytes[name] ?: return@filterTo false
            val eligible = name in holders || Recordability.sourceFile(b)?.let { rule.tracked(name, it) } == true
            eligible && Recordability.maskedDigest(b, mask!!).value?.let { it == rule.recordedMasked!![name] } == true
        }
        return found(masked, if (masked.isEmpty()) "no changed class differs only in their values" else null)
    }

    /** Whether [bytes] load [value] by `ldc`. */
    private fun loads(bytes: ByteArray, value: Any): Boolean = runCatching {
        var found = false
        org.objectweb.asm.ClassReader(bytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
                ) = object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    override fun visitLdcInsn(loaded: Any?) {
                        if (loaded == value) found = true
                    }
                }
            },
            org.objectweb.asm.ClassReader.SKIP_FRAMES,
        )
        found
    }.getOrDefault(false)

    /**
     * The run's lines: the constants that moved with no source change (whatever the run then
     * decides), and what `stampedConstants` left out, or why it left nothing out.
     */
    fun lines(found: Found, readers: (Set<String>) -> Int?): List<String> {
        val lines = mutableListOf<String>()
        if (found.moved.isNotEmpty()) {
            val named = found.moved.entries.take(NAMED).joinToString("; ") { (holder, fields) ->
                if (fields.isEmpty()) "$holder (the map does not record which of its constants)" else "$holder ${fields.joinToString()}"
            } + if (found.moved.size > NAMED) "; and ${found.moved.size - NAMED} more" else ""
            lines += "compile-time constants changed with no source change behind them: $named; ${found.consumers} other " +
                "changed class(es) load one of their new values. A constant the build stamps with the time or the commit " +
                "changes every class that inlined it: make its value fixed for test builds, or list it in stampedConstants " +
                "if no test depends on it."
        }
        if (found.invalid.isNotEmpty()) {
            lines += "stampedConstants entries that name no compile-time constant of this build's classes, so declare " +
                "nothing: ${found.invalid.joinToString()}"
        }
        if (found.masked.isNotEmpty()) {
            val fields = found.declaredMoved.entries.take(NAMED).joinToString { (field, digests) ->
                "$field (${digests.first.take(DIGEST_SHOWN)} -> ${digests.second.take(DIGEST_SHOWN)})"
            }
            val count = readers(found.masked)
            val read = if (count == null) "the map cannot say which tests executed them"
            else "the $count test(s) that executed them when the map was captured do not run for them"
            lines += "stampedConstants left out ${found.masked.size} changed class(es) whose only change is the value of " +
                "declared constants $fields, which no test depends on: ${found.masked.take(NAMED).joinToString()}" +
                (if (found.masked.size > NAMED) " and ${found.masked.size - NAMED} more" else "") + "; $read"
        } else if (found.unmasked != null && found.declaredMoved.isNotEmpty()) {
            lines += "stampedConstants left nothing out: ${found.unmasked}"
        }
        return lines
    }

    /** How many tests of the map at [mapDir] executed one of [classes]; null when the map cannot be read. */
    fun testsExecuting(mapDir: java.io.File, classes: Set<String>): Int? = runCatching {
        val map = io.github.zeuspizza.yoriwake.agent.select.MapReader.read(mapDir).takeIf { it.isUsable } ?: return null
        map.entries().count { entry ->
            entry.testId() != io.github.zeuspizza.yoriwake.agent.contract.AgentContract.UNATTRIBUTED_RECORD_ID &&
                !entry.testId().startsWith(io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CLASS_SCOPED_RECORD_PREFIX) &&
                entry.coveredClasses().any { it in classes }
        }
    }.getOrNull()

    private const val NAMED = 5
    private const val DIGEST_SHOWN = 12
}
