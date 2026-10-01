package io.github.zeuspizza.yoriwake.agent.engines

import org.junit.platform.engine.ConfigurationParameters
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.util.Optional

/**
 * Builds the [TestIdentifier]s the listener's callbacks expect.
 *
 * Driving the callbacks directly, rather than a nested launcher, lets tests assert the reset/dump
 * ordering that attribution depends on.
 */
object TestIdentifiers {

    private class Descriptor(id: UniqueId, private val leaf: Boolean) :
        AbstractTestDescriptor(id, id.lastSegment.value) {
        override fun getType() =
            if (leaf) TestDescriptor.Type.TEST else TestDescriptor.Type.CONTAINER
    }

    /** A leaf test inside [inClass], so its unique id carries the class segment the listener reads. */
    fun leaf(name: String, inClass: String = "DefaultSpec"): TestIdentifier =
        TestIdentifier.from(
            Descriptor(
                UniqueId.forEngine("junit-jupiter").append("class", inClass).append("method", name),
                true,
            )
        )

    /** A plan of one Jupiter class holding [methods], with that class's and its tests' identifiers. */
    fun plan(inClass: String, vararg methods: String): Triple<TestPlan, TestIdentifier, List<TestIdentifier>> {
        val engine = EngineDescriptor(UniqueId.forEngine("junit-jupiter"), "junit-jupiter")
        val container = Descriptor(engine.uniqueId.append("class", inClass), false)
        engine.addChild(container)
        methods.forEach { container.addChild(Descriptor(container.uniqueId.append("method", it), true)) }
        @Suppress("DEPRECATION")
        val plan = TestPlan.from(listOf<TestDescriptor>(engine), NoParameters)
        val classId = plan.getTestIdentifier(container.uniqueId.toString())
        return Triple(plan, classId, plan.getChildren(classId).sortedBy { it.uniqueId })
    }

    /**
     * A plan of one Jupiter class holding only test templates ([templates]), none of which has
     * registered an invocation yet, with that class's and its templates' identifiers.
     */
    fun templatePlan(inClass: String, vararg templates: String): Triple<TestPlan, TestIdentifier, List<TestIdentifier>> {
        val engine = EngineDescriptor(UniqueId.forEngine("junit-jupiter"), "junit-jupiter")
        val container = Descriptor(engine.uniqueId.append("class", inClass), false)
        engine.addChild(container)
        templates.forEach { container.addChild(Descriptor(container.uniqueId.append("test-template", it), false)) }
        @Suppress("DEPRECATION")
        val plan = TestPlan.from(listOf<TestDescriptor>(engine), NoParameters)
        val classId = plan.getTestIdentifier(container.uniqueId.toString())
        return Triple(plan, classId, plan.getChildren(classId).sortedBy { it.uniqueId })
    }

    private object NoParameters : ConfigurationParameters {
        override fun get(key: String): Optional<String> = Optional.empty()
        override fun getBoolean(key: String): Optional<Boolean> = Optional.empty()
        @Deprecated("required by the interface")
        override fun size() = 0
        override fun keySet(): Set<String> = emptySet()
    }

    fun container(name: String): TestIdentifier = identifier(name, isTest = false)

    /** A JUnit vintage id, which carries [runner:...] rather than [class:...]. */
    fun vintage(runner: String, method: String): TestIdentifier =
        TestIdentifier.from(
            Descriptor(
                UniqueId.forEngine("junit-vintage").append("runner", runner).append("test", method),
                true,
            )
        )

    private fun identifier(name: String, isTest: Boolean): TestIdentifier =
        TestIdentifier.from(
            Descriptor(
                UniqueId.forEngine("junit-jupiter").append(if (isTest) "method" else "class", name),
                isTest,
            )
        )
}
