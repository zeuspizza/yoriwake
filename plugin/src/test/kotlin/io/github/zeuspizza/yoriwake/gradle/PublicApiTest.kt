package io.github.zeuspizza.yoriwake.gradle

import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plugin's public Kotlin surface is exactly what a host build or the init script needs.
 *
 * Read from the compiled classes' Kotlin metadata rather than from bytecode access flags, because
 * `internal` compiles to a public JVM class and only the metadata says otherwise.
 */
class PublicApiTest {

    private val intended = mapOf(
        "YoriwakePlugin" to "the plugin class Gradle instantiates from the plugin id",
        "YoriwakeExtension" to "the `yoriwake { }` block a build script configures",
        "YoriwakePlugin.Companion" to "what scripts/yoriwake.init.gradle.kts reads, compiled apart",
    )

    @Test
    fun `only the plugin, its extension and what the init script reads are public`() {
        val root = File(YoriwakePlugin::class.java.protectionDomain.codeSource.location.toURI())
        val pkg = File(root, "io/github/zeuspizza/yoriwake/gradle")
        assertTrue(pkg.isDirectory, "expected the main classes as a directory at $root")

        val classes = mutableMapOf<String, Visibility>()
        val topLevel = mutableListOf<String>()
        pkg.walk().filter { it.isFile && it.extension == "class" }.forEach { file ->
            val name = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val metadata = Class.forName(name, false, javaClass.classLoader)
                .getAnnotation(Metadata::class.java) ?: return@forEach
            when (val read = KotlinClassMetadata.readStrict(metadata)) {
                is KotlinClassMetadata.Class -> classes[read.kmClass.name] = read.kmClass.visibility
                is KotlinClassMetadata.FileFacade -> {
                    val pkgMembers = read.kmPackage
                    topLevel += pkgMembers.functions.filter { it.visibility.exposed() }.map { it.name }
                    topLevel += pkgMembers.properties.filter { it.visibility.exposed() }.map { it.name }
                    topLevel += pkgMembers.typeAliases.filter { it.visibility.exposed() }.map { it.name }
                }
                else -> Unit
            }
        }

        // A nested class is only as visible as the class around it.
        fun effectivelyPublic(name: String): Boolean {
            if (classes[name]?.exposed() != true) return false
            val outer = name.substringBeforeLast('.', "")
            return outer.isEmpty() || effectivelyPublic(outer)
        }

        val prefix = "io/github/zeuspizza/yoriwake/gradle/"
        val public = classes.keys.filter(::effectivelyPublic).map { it.removePrefix(prefix) }.toSet()
        assertEquals(intended.keys, public)
        assertEquals(emptyList(), topLevel, "top-level declarations that are public")
    }

    private fun Visibility.exposed() = this == Visibility.PUBLIC || this == Visibility.PROTECTED
}
