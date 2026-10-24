package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.YoriwakeExtension
import org.gradle.api.InvalidUserDataException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The build's `dependsOn` entries: read whole, or the build fails naming the entry. */
class DeclarationsTest {

    private val tasks = setOf("test", "integrationTest")

    @Test
    fun `an entry names its task and resolves its glob against its project, or the root with a slash`() {
        val parsed = Declarations.parse(listOf("integrationTest=/docker/**", "test=src/main/resources/db/**"), "app", ":app", tasks)

        assertEquals(
            listOf(
                Declarations.Declaration("integrationTest", "/docker/**", "docker/**"),
                Declarations.Declaration("test", "src/main/resources/db/**", "app/src/main/resources/db/**"),
            ),
            parsed,
        )
    }

    @Test
    fun `an entry that matches every path, names no test task, or is not task=glob fails naming itself`() {
        for ((entry, why) in listOf(
            "test=**" to "matches every path",
            "test=/**" to "matches every path",
            "test=" to "is empty",
            "smokeTest=/docker/**" to "names no `Test` task of this project (it has integrationTest, test)",
            "/docker/**" to "is not `task=glob`",
            "test=docker/{a,b}.yml" to "braces are not supported",
        )) {
            val failure = assertThrows<InvalidUserDataException> { Declarations.parse(listOf(entry), "", ":", tasks) }
            assertContains(failure.message.orEmpty(), "dependsOn holds '$entry'")
            assertContains(failure.message.orEmpty(), why)
        }
    }

    @Test
    fun `the function and an added entry declare the same`() {
        val project = ProjectBuilder.builder().build()
        val viaFunction = project.extensions.create("one", YoriwakeExtension::class.java)
        val viaAdd = project.extensions.create("two", YoriwakeExtension::class.java)

        viaFunction.dependsOn("integrationTest", "/docker/**")
        viaAdd.dependsOn.add("integrationTest=/docker/**")

        assertEquals(viaAdd.dependsOn.get(), viaFunction.dependsOn.get())
    }
}
