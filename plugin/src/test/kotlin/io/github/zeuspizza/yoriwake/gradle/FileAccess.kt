package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView

// The functional tests carry the same two ACL functions; the suites share no test code.

/**
 * Makes [file] unreadable to this user, and says whether it is: by permission bits where the
 * file system has them, by a deny entry in its ACL where it has those instead (Windows, where
 * `setReadable(false)` does nothing). Checked by opening it: on Windows `canRead` reads attributes,
 * not the ACL. Root reads anything, so the answer can be no.
 */
internal fun makeUnreadable(file: File): Boolean {
    val acl = Files.getFileAttributeView(file.toPath(), AclFileAttributeView::class.java)
    if (acl != null) {
        acl.acl = listOf(denyRead(file)) + acl.acl
    } else {
        file.setReadable(false)
    }
    return runCatching { file.inputStream().use { it.read() } }.isFailure
}

/** Undoes [makeUnreadable], so the test's directory can be cleaned up. */
internal fun makeReadable(file: File) {
    val acl = Files.getFileAttributeView(file.toPath(), AclFileAttributeView::class.java)
    if (acl != null) {
        acl.acl = acl.acl - denyRead(file)
    } else {
        file.setReadable(true)
    }
}

private fun denyRead(file: File): AclEntry = AclEntry.newBuilder()
    .setType(AclEntryType.DENY)
    // The owner, not a name looked up: the test created the file, so it is this user (or, in an
    // elevated process, the administrators group it acts as), with no domain lookup to go astray.
    .setPrincipal(Files.getOwner(file.toPath()))
    .setPermissions(AclEntryPermission.READ_DATA)
    .build()

/**
 * Creates a symbolic link, or skips the test where this user may not: Windows grants it only in
 * Developer Mode or to an elevated process. Any other failure is the test's own and fails it. CI's
 * Windows runner is elevated, so there a refusal fails too rather than skip unseen.
 */
internal fun symbolicLink(link: File, target: File) {
    try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
    } catch (refused: FileSystemException) {
        val privilege = File.separatorChar == '\\' && refused !is NoSuchFileException &&
            refused !is FileAlreadyExistsException && System.getenv("CI") == null
        if (!privilege) throw refused
        assumeTrue(false, "this user may not create a symbolic link here: ${refused.reason}")
    }
}
