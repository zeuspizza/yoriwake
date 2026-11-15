package io.github.zeuspizza.yoriwake.agent.capture

import io.github.zeuspizza.yoriwake.agent.contract.TestClassIds
import kotlin.test.assertEquals

class SpecificationScopeTest {

    @org.junit.jupiter.api.Test
    fun `a Spock feature and its iterations are scoped to their specification`() {
        val spec = "[engine:spock]/[spec:a.B]"

        assertEquals(spec, CaptureSession.classScopeOf("$spec/[feature:x]"))
        assertEquals(spec, CaptureSession.classScopeOf("$spec/[feature:x]/[iteration:0]"))
    }

    @org.junit.jupiter.api.Test
    fun `a Spock specification a suite runs is scoped up to the specification`() {
        val spec = "[engine:junit-platform-suite]/[suite:S]/[engine:spock]/[spec:a.B]"

        assertEquals(spec, CaptureSession.classScopeOf("$spec/[feature:x]"))
    }

    @org.junit.jupiter.api.Test
    fun `a spec segment under another engine is not a class`() {
        // Kotest names its specs the same way, but what runs between them is not known to be theirs.
        assertEquals(null, CaptureSession.classScopeOf("[engine:kotest]/[spec:a.B]/[test:x]"))
        assertEquals(
            null,
            CaptureSession.classScopeOf("[engine:junit-platform-suite]/[suite:S]/[engine:kotest]/[spec:a.B]/[test:x]"),
        )
    }

    @org.junit.jupiter.api.Test
    fun `a scope names the class or specification it ends with`() {
        assertEquals("a.B", TestClassIds.nameOf("[engine:spock]/[spec:a.B]"))
        assertEquals("Outer", TestClassIds.nameOf("[engine:junit-jupiter]/[class:Outer]"))
        assertEquals(null, TestClassIds.nameOf("[engine:kotest]/[spec:a.B]"))
        assertEquals(null, TestClassIds.nameOf(null))
    }
}
