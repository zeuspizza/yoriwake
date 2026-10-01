package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.bytecode.EffectiveScope
import io.github.zeuspizza.yoriwake.gradle.change.AbsenceEvidence
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Each condition under which absence of coverage proves nothing, violated on purpose.
class AbsenceEvidenceTest {

    private fun bytesOf(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class"))
            .use { it.readBytes() }

    private val logic = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasLogic"
    private val invisible = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasInvisibleAnnotation"
    private val visible = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasVisibleAnnotation"
    private val constant = "io.github.zeuspizza.yoriwake.gradle.fixtures.HasConstant"

    private val compiled = AbsenceEvidence.CompiledClasses { prefix ->
        listOf(prefix to bytesOf(prefix))
    }

    private val namedNowhere = AbsenceEvidence.NamedInResources { emptySet() }

    private val nothingInTestOutput = AbsenceEvidence.TestOutput { false }

    private fun assess(
        prefix: String,
        scopes: List<EffectiveScope> = listOf(EffectiveScope(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.*"), emptyList())),
        compiled: AbsenceEvidence.CompiledClasses = this.compiled,
        named: AbsenceEvidence.NamedInResources = namedNowhere,
    ) = AbsenceEvidence.assess(listOf(prefix), scopes, compiled, named).getValue(prefix)

    @Test
    fun `a recordable class inside the scope, named nowhere, is provable`() {
        assertTrue(assess(logic).provable)
    }

    @Test
    fun `a scope that could not be established proves nothing`() {
        // Records describe the scope in force when they were captured, not current configuration.
        val verdict = assess(logic, scopes = emptyList())

        assertFalse(verdict.provable)
        assertTrue(verdict.reason!!.contains("could not be established"))
    }

    @Test
    fun `a class the host excluded from instrumentation proves nothing`() {
        // A class the host excludes looks exactly like an untested one.
        val verdict = assess(
            logic,
            scopes = listOf(EffectiveScope(listOf("io.github.zeuspizza.yoriwake.gradle.*"), listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.*"))),
        )

        assertFalse(verdict.provable)
        assertTrue(verdict.reason!!.contains("excluded from instrumentation"))
    }

    @Test
    fun `a class outside the includes proves nothing`() {
        val verdict = assess(logic, scopes = listOf(EffectiveScope(listOf("com.elsewhere.*"), emptyList())))

        assertFalse(verdict.provable)
        assertTrue(verdict.reason!!.contains("outside the instrumentation scope"))
    }

    @Test
    fun `a constant holder proves nothing, whatever else it declares`() {
        // No probe exists to fire, and the value is inlined into consumers whose sources did not
        // change.
        assertFalse(assess(constant).provable)
    }

    @Test
    fun `a sibling class in the same file disqualifies the prefix`() {
        // A Kotlin file's constants live in `Foo$Companion` and its functions in `FooKt`; judging
        // `Foo` alone misses them.
        val bothClasses = AbsenceEvidence.CompiledClasses {
            listOf(logic to bytesOf(logic), constant to bytesOf(constant))
        }

        assertFalse(assess(logic, compiled = bothClasses).provable)
    }

    @Test
    fun `a class named as a string in a resource proves nothing`() {
        // A class named in test-resource YAML and resolved by a registry has no bytecode edge and
        // no coverage record.
        val verdict = assess(logic, named = { names -> names.filter { it == logic }.toSet() })

        assertFalse(verdict.provable)
        assertTrue(verdict.reason!!.contains("named as a string in a resource"))
    }

    @Test
    fun `a prefix with no compiled class proves nothing`() {
        assertFalse(assess(logic, compiled = { null }).provable)
        assertFalse(assess(logic, compiled = { emptyList() }).provable)
    }

    @Test
    fun `an empty include list means JaCoCo was watching everything, not nothing`() {
        // Empty includes is JaCoCo's default and means the whole classpath.
        assertTrue(assess(logic, scopes = listOf(EffectiveScope(emptyList(), emptyList()))).provable)
    }

    @Test
    fun `every prefix gets a verdict`() {
        val verdicts = AbsenceEvidence.assess(
            listOf(logic, constant), listOf(EffectiveScope(emptyList(), emptyList())), compiled, namedNowhere,
        )

        assertEquals(setOf(logic, constant), verdicts.keys)
    }

    @Test
    fun `a class one of the map's scopes was not watching proves nothing`() {
        // A map can hold records captured under more than one scope, e.g. after packages moved;
        // absence from records nobody was watching proves nothing.
        val verdict = assess(
            logic,
            scopes = listOf(
                EffectiveScope(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.*"), emptyList()),
                EffectiveScope(listOf("com.elsewhere.*"), emptyList()),
            ),
        )

        assertFalse(verdict.provable)
    }

    @Test
    fun `a class every recorded scope was watching is still provable`() {
        assertTrue(
            assess(
                logic,
                scopes = listOf(
                    EffectiveScope(listOf("io.github.zeuspizza.yoriwake.gradle.fixtures.*"), emptyList()),
                    EffectiveScope(listOf("io.github.zeuspizza.yoriwake.*"), emptyList()),
                ),
            ).provable
        )
    }

    @Test
    fun `a constants-only fixture in the test output is not exempt from forcing`() {
        // A changed test class need not force only if JUnit discovers it. A constants-only fixture
        // in src/test/java is never discovered, loaded or covered, but is inlined into its readers.
        val verdict = assess(constant)

        assertFalse(verdict.provable)
        assertTrue(verdict.reason!!.contains("compile-time constant"))
    }

    // A framework acts on a class's annotation without loading it: a Dagger module whose `@Binds`
    // are abstract, or a bean a scan registers from the class file alone.
    @Test
    fun `a class carrying a class-level annotation is not provable by absence`() {
        val verdicts = AbsenceEvidence.unannotated(
            listOf(visible, invisible, logic).associateWith { AbsenceEvidence.Verdict(true, null) },
            compiled,
            nothingInTestOutput,
        )

        assertFalse(verdicts.getValue(visible).provable)
        assertTrue(verdicts.getValue(visible).reason!!.contains("class-level annotation @java.lang.Deprecated"))
        // Class retention: invisible to reflection, not to an annotation processor or a bytecode scan.
        assertFalse(verdicts.getValue(invisible).provable)
        assertTrue(verdicts.getValue(logic).provable)
    }

    @Test
    fun `one annotated class in the file refuses the prefix`() {
        val both = AbsenceEvidence.CompiledClasses {
            listOf(logic to bytesOf(logic), "$logic\$Module" to bytesOf(visible))
        }

        val verdict = AbsenceEvidence.unannotated(mapOf(logic to AbsenceEvidence.Verdict(true, null)), both, nothingInTestOutput)

        assertFalse(verdict.getValue(logic).provable)
    }

    @Test
    fun `classes that cannot be read or found refuse rather than count as unannotated`() {
        val provable = mapOf(logic to AbsenceEvidence.Verdict(true, null))

        assertFalse(AbsenceEvidence.unannotated(provable, { listOf(logic to byteArrayOf(1, 2, 3)) }, nothingInTestOutput).getValue(logic).provable)
        assertFalse(AbsenceEvidence.unannotated(provable, { null }, nothingInTestOutput).getValue(logic).provable)
    }

    @Test
    fun `a refusal already given is kept as it was`() {
        val refused = AbsenceEvidence.Verdict(false, "no compiled class could be found")

        assertEquals(refused, AbsenceEvidence.unannotated(mapOf(visible to refused), compiled, nothingInTestOutput).getValue(visible))
    }

    // Discovery runs a test class whatever the map holds, so its annotations cost nothing; a
    // configuration nested in it runs no test of its own and still refuses.
    @Test
    fun `an annotated test class discovery runs is exempt, and a helper beside it is not`() {
        val testClass = "io.github.zeuspizza.yoriwake.gradle.fixtures.AnnotatedTestClass"
        val provable = mapOf(testClass to AbsenceEvidence.Verdict(true, null))
        val everyPrefix = AbsenceEvidence.TestOutput { true }

        assertTrue(AbsenceEvidence.unannotated(provable, compiled, everyPrefix).getValue(testClass).provable)
        assertFalse(AbsenceEvidence.unannotated(provable, compiled, nothingInTestOutput).getValue(testClass).provable)

        val withHelper = AbsenceEvidence.CompiledClasses {
            listOf(testClass to bytesOf(testClass), "$testClass\$Config" to bytesOf(visible))
        }
        assertFalse(AbsenceEvidence.unannotated(provable, withHelper, everyPrefix).getValue(testClass).provable)
    }
}
