package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * The numbers the test JVMs of one task claim, so that each drives a TestKit home no other running
 * JVM uses: taken lowest first, and free again the moment their holder releases them or dies.
 */
class TestKitSlotTest {

    @TempDir lateinit var dir: File

    private val claims = mutableListOf<TestKitSlot>()
    private val others = mutableListOf<Process>()

    @AfterEach
    fun release() {
        claims.forEach { it.release() }
        others.forEach { it.destroyForcibly().waitFor() }
    }

    @Test
    fun `two holders get 1 and 2, and a released number is the next one claimed`() {
        val first = claim()
        val second = claim()
        assertEquals(listOf(1, 2), listOf(first.number, second.number))

        first.release()

        assertEquals(1, claim().number)
        assertEquals(3, claim().number)
    }

    @Test
    fun `a lock file nobody holds does not keep its number taken`() {
        File(dir, ".gradle-test-kit-slot-1.lock").writeText("")

        assertEquals(1, claim().number)
    }

    @Test
    fun `a number another JVM holds is passed over, and is free again once that JVM is killed`() {
        val other = claimInAnotherJvm()
        assertEquals("1", other.inputStream.bufferedReader().readLine())

        assertEquals(2, claim().number)

        other.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
        // Windows frees a dead process's locks a moment after the process is gone.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var next = claim()
        while (next.number != 1 && System.nanoTime() < deadline) {
            next.release()
            Thread.sleep(100)
            next = claim()
        }
        assertEquals(1, next.number)
    }

    private fun claim() = TestKitSlot.claim(dir).also { claims += it }

    /** A JVM that claims a number in [dir], prints it, and holds it until it is killed. */
    private fun claimInAnotherJvm(): Process {
        val classpath = listOf(TestKitSlot::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }
        return ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath.joinToString(File.pathSeparator),
            "io.github.zeuspizza.yoriwake.gradle.TestKitSlotTestKt",
            dir.path,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start().also { others += it }
    }
}

/** Claims a number in the directory named, prints it, and holds it until stdin closes. */
fun main(args: Array<String>) {
    println(TestKitSlot.claim(File(args.single())).number)
    System.`in`.read()
}
