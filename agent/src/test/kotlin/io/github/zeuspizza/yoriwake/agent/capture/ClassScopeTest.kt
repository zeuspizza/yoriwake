package io.github.zeuspizza.yoriwake.agent.capture

import kotlin.test.assertEquals

class ClassScopeTest {

    @org.junit.jupiter.api.Test
    fun `extracts the class prefix a test's siblings share`() {
        val id = "[engine:junit-jupiter]/[class:dev.detekt.AlphaSpec]/[method:does a thing()]"

        assertEquals(
            "[engine:junit-jupiter]/[class:dev.detekt.AlphaSpec]",
            CaptureSession.classScopeOf(id),
        )
    }

    @org.junit.jupiter.api.Test
    fun `uses the outermost class for a nested test`() {
        // The outer class's @BeforeAll runs once before the first nested class's first test;
        // scoping it to the innermost class would silently skip every sibling.
        val id = "[engine:junit-jupiter]/[class:Outer]/[nested-class:Inner]/[method:t()]"

        assertEquals("[engine:junit-jupiter]/[class:Outer]", CaptureSession.classScopeOf(id))
    }

    @org.junit.jupiter.api.Test
    fun `sibling nested classes share one scope so setup selects them all`() {
        val a = "[engine:junit-jupiter]/[class:Outer]/[nested-class:A]/[method:t()]"
        val b = "[engine:junit-jupiter]/[class:Outer]/[nested-class:B]/[method:t()]"

        assertEquals(CaptureSession.classScopeOf(a), CaptureSession.classScopeOf(b))
    }

    @org.junit.jupiter.api.Test
    fun `covers every invocation of a parameterized test`() {
        val id = "[engine:junit-jupiter]/[class:S]/[test-template:t()]/[test-template-invocation:#2]"

        // The scope is the class, so all invocations share it and setup selects all of them.
        assertEquals("[engine:junit-jupiter]/[class:S]", CaptureSession.classScopeOf(id))
    }

    @org.junit.jupiter.api.Test
    fun `an id with no class segment yields null so the window stays global`() {
        // Conservative: an id shape this listener does not understand must not be narrowed.
        assertEquals(null, CaptureSession.classScopeOf("[engine:custom]/[thing:x]"))
    }
}

class ClassScopeEdgeCaseTest {

    @org.junit.jupiter.api.Test
    fun `a class name containing a bracket keeps its terminator`() {
        // JUnit does not escape ']' inside a segment value, so the first ']' is not the terminator,
        // and a truncated prefix matches nothing or a longer class name.
        val id = "[engine:junit-jupiter]/[class:Weird[Name]Spec]/[method:t()]"

        assertEquals(
            "[engine:junit-jupiter]/[class:Weird[Name]Spec]",
            CaptureSession.classScopeOf(id),
        )
    }

    @org.junit.jupiter.api.Test
    fun `a vintage engine id has no class segment`() {
        // JUnit vintage emits [runner:...], not [class:...]. Returning null is what routes the
        // window to the global bucket instead of blaming a neighbouring class for its coverage.
        val id = "[engine:junit-vintage]/[runner:com.example.OldTest]/[test:t(com.example.OldTest)]"

        assertEquals(null, CaptureSession.classScopeOf(id))
    }
}
