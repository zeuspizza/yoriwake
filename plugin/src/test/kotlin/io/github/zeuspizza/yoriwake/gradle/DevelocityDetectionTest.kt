package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.wiring.DevelocityDetection
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Develocity's test-task extensions, read by name and shape, never by class.
class DevelocityDetectionTest {

    private val objects = ProjectBuilder.builder().build().objects

    private fun flag(value: Boolean?): Property<Boolean> =
        objects.property(Boolean::class.java).also { property -> value?.let(property::set) }

    // Shaped like the real plugin's: an `enabled` property, and PTS's system-property twin.
    class Feature(val enabled: Property<Boolean>)

    class SelectionFeature(val enabled: Property<Boolean>, val enabledFromSystemProperty: Property<Boolean>)

    class Modern(private val distribution: Any, private val selection: Any) {
        fun getTestDistribution() = distribution
        fun getPredictiveTestSelection() = selection
    }

    class Broken {
        fun getTestDistribution(): Any = throw IllegalStateException("not on this version")
        fun getPredictiveTestSelection(): Any = Any()
    }

    private fun modern(td: Boolean? = false, pts: Boolean? = false, ptsSystem: Boolean? = null) =
        Modern(Feature(flag(td)), SelectionFeature(flag(pts), flag(ptsSystem)))

    private fun detect(vararg extensions: Pair<String, Any>): Pair<RefusalKind, String>? {
        val byName = extensions.toMap()
        return DevelocityDetection.detect { byName[it] }.refusal
    }

    @Test
    fun `no Develocity extension, or every feature off, declines nothing`() {
        assertNull(detect())
        assertNull(detect("develocity" to modern()))
        assertNull(detect("develocity" to modern(td = null, pts = null)))
    }

    @Test
    fun `Test Distribution enabled declines under its own token`() {
        assertEquals(RefusalKind.DEVELOCITY_TEST_DISTRIBUTION, detect("develocity" to modern(td = true))?.first)
    }

    @Test
    fun `Predictive Test Selection enabled, in the DSL or by its system property, declines`() {
        assertEquals(RefusalKind.DEVELOCITY_TEST_SELECTION, detect("develocity" to modern(pts = true))?.first)
        // -Dpts.enabled=true sets only the system-property twin.
        assertEquals(RefusalKind.DEVELOCITY_TEST_SELECTION, detect("develocity" to modern(ptsSystem = true))?.first)
    }

    @Test
    fun `both features on decline as Test Distribution, naming both`() {
        val (kind, reason) = assertNotNullPair(detect("develocity" to modern(td = true, pts = true)))
        assertEquals(RefusalKind.DEVELOCITY_TEST_DISTRIBUTION, kind)
        assertContains(reason, "Test Distribution")
        assertContains(reason, "Predictive Test Selection")
    }

    @Test
    fun `the legacy extensions are read, beside the new one too`() {
        assertEquals(RefusalKind.DEVELOCITY_TEST_DISTRIBUTION, detect("distribution" to Feature(flag(true)))?.first)
        assertEquals(RefusalKind.DEVELOCITY_TEST_SELECTION, detect("predictiveSelection" to Feature(flag(true)))?.first)
        // A 3.17-3.19 plugin carries both shapes; the legacy name alone may hold the switch.
        assertEquals(
            RefusalKind.DEVELOCITY_TEST_SELECTION,
            detect("develocity" to modern(), "predictiveSelection" to Feature(flag(true)))?.first,
        )
    }

    @Test
    fun `an extension whose getter throws is undetermined`() {
        val (kind, reason) = assertNotNullPair(detect("develocity" to Broken()))
        assertEquals(RefusalKind.DEVELOCITY_UNDETERMINED, kind)
        assertContains(reason, "not on this version")
    }

    @Test
    fun `a feature read as on names its own remedy though another cannot be read`() {
        class HalfBroken {
            fun getTestDistribution(): Any = Feature(ProjectBuilder.builder().build().objects.property(Boolean::class.java).value(true))
            fun getPredictiveTestSelection(): Any = throw IllegalStateException("unreadable")
        }
        assertEquals(RefusalKind.DEVELOCITY_TEST_DISTRIBUTION, detect("develocity" to HalfBroken())?.first)
    }

    @Test
    fun `an enabled value that is not a boolean is undetermined`() {
        class Odd { fun getEnabled() = "yes" }
        assertEquals(RefusalKind.DEVELOCITY_UNDETERMINED, detect("distribution" to Odd())?.first)
        class Missing
        assertEquals(RefusalKind.DEVELOCITY_UNDETERMINED, detect("distribution" to Missing())?.first)
    }

    @Test
    fun `a decision crosses the configuration cache as text and back`() {
        listOf(
            DevelocityDetection.detect { null },
            DevelocityDetection.detect { if (it == "develocity") modern(td = true, pts = true) else null },
            DevelocityDetection.detect { if (it == "develocity") Broken() else null },
        ).forEach { detected ->
            assertEquals(detected.refusal, DevelocityDetection.decode(detected.encode()))
        }
        assertNull(DevelocityDetection.decode(null))
    }

    private fun assertNotNullPair(pair: Pair<RefusalKind, String>?) = kotlin.test.assertNotNull(pair)
}
