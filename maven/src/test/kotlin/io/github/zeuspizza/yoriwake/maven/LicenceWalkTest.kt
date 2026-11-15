package io.github.zeuspizza.yoriwake.maven

import kotlin.test.Test
import kotlin.test.assertEquals

class LicenceWalkTest {

    private val mycila = "com.mycila:license-maven-plugin"

    private fun named(vararg sets: LicenceFileSet) = licencePluginsListingState(mapOf(mycila to sets.toList()))

    @Test
    fun `a licence plugin that walks the tree with no includes or excludes is named`() {
        assertEquals(listOf(mycila), named(LicenceFileSet(emptyList(), emptyList())))
    }

    @Test
    fun `excludes that name the state directory are not named`() {
        assertEquals(emptyList(), named(LicenceFileSet(emptyList(), listOf("**/*.md", ".yoriwake/**"))))
    }

    @Test
    fun `excludes of something else are still named`() {
        assertEquals(listOf(mycila), named(LicenceFileSet(emptyList(), listOf("**/*.md"))))
    }

    @Test
    fun `includes that reach no state file are not named, and those that reach one are`() {
        for (includes in listOf(listOf("src/**/*.java"), listOf("**/*.java", "pom.xml"), listOf("src/"))) {
            assertEquals(emptyList(), named(LicenceFileSet(includes, emptyList())), "$includes")
        }
        for (includes in listOf(listOf("**/*.txt"), listOf("**/*.properties"), listOf("**/*"), listOf(".yoriwake/**"), listOf("**"))) {
            assertEquals(listOf(mycila), named(LicenceFileSet(includes, emptyList())), "$includes")
        }
    }

    @Test
    fun `a set that excludes the state directory does not silence another that lists it`() {
        val excluding = LicenceFileSet(emptyList(), listOf(".yoriwake/**"))
        assertEquals(listOf(mycila), named(excluding, LicenceFileSet(listOf("**/*.txt"), emptyList())))
        assertEquals(emptyList(), named(excluding, LicenceFileSet(listOf("**/*.java"), emptyList())))
    }

    @Test
    fun `a plugin not known to walk the tree is not named`() {
        val all = listOf(LicenceFileSet(emptyList(), emptyList()))
        assertEquals(emptyList(), licencePluginsListingState(mapOf("org.apache.rat:apache-rat-plugin" to all, "org.apache.maven.plugins:maven-jar-plugin" to all)))
    }
}
