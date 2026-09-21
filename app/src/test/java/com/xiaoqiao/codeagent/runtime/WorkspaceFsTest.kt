package com.xiaoqiao.codeagent.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class WorkspaceFsTest {
    private lateinit var root: File

    @Before
    fun setup() {
        root = File.createTempFile("wsfs", "dir").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun hostRootStripsLeadingSlash() {
        val debian = File(root, "debian")
        val host = WorkspaceFs.hostRoot(debian, "/root/workspace/foo")
        assertEquals(File(debian, "root/workspace/foo"), host)
    }

    @Test
    fun listPutsDirectoriesFirstThenFilesByName() {
        File(root, "b.txt").writeText("x")
        File(root, "a").mkdirs()
        File(root, "c").mkdirs()
        File(root, ".git").mkdirs()
        assertEquals(listOf(".git", "a", "c", "b.txt"), WorkspaceFs.list(root).map { it.name })
    }

    @Test
    fun listUsesRelativePathFromRoot() {
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun")
        val entries = WorkspaceFs.list(root, "src")
        assertEquals(listOf("Main.kt"), entries.map { it.name })
        assertEquals(listOf("src/Main.kt"), entries.map { it.relativePath })
        assertFalse(entries[0].isDirectory)
    }

    @Test
    fun parentRelativeStopsAtRoot() {
        assertEquals(null, WorkspaceFs.parentRelative("."))
        assertEquals(".", WorkspaceFs.parentRelative("src"))
        assertEquals("src", WorkspaceFs.parentRelative("src/foo"))
    }

    @Test
    fun resolveRejectsEscape() {
        File(root.parentFile, "outside.txt").writeText("no")
        try {
            WorkspaceFs.resolve(root, "../outside.txt")
            fail("expected escape to fail")
        } catch (_: IllegalArgumentException) {
        }
        File(root, "src").mkdirs()
        assertEquals(root.canonicalFile, WorkspaceFs.resolve(root, "src/.."))
    }

    @Test
    fun renameAndRejectInvalidOrExisting() {
        File(root, "old.txt").writeText("hi")
        File(root, "taken.txt").writeText("x")
        WorkspaceFs.rename(root, "old.txt", "new.txt")
        assertTrue(File(root, "new.txt").exists())
        assertFalse(File(root, "old.txt").exists())
        try {
            WorkspaceFs.rename(root, "new.txt", "taken.txt")
            fail("expected already exists")
        } catch (_: IllegalStateException) {
        }
        try {
            WorkspaceFs.rename(root, "new.txt", "../x")
            fail("expected invalid name")
        } catch (_: IllegalArgumentException) {
        }
        try {
            WorkspaceFs.rename(root, "new.txt", ".")
            fail("expected invalid name")
        } catch (_: IllegalArgumentException) {
        }
        try {
            WorkspaceFs.delete(root, ".")
            fail("expected cannot delete root")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun deleteFileAndDirectory() {
        File(root, "a.txt").writeText("x")
        val dir = File(root, "d")
        dir.mkdirs()
        File(dir, "f").writeText("y")
        WorkspaceFs.delete(root, "a.txt")
        WorkspaceFs.delete(root, "d")
        assertFalse(File(root, "a.txt").exists())
        assertFalse(dir.exists())
    }

    @Test
    fun copyToStreamWritesBytes() {
        File(root, "a.txt").writeText("hello")
        val out = ByteArrayOutputStream()
        WorkspaceFs.copyToStream(File(root, "a.txt"), out)
        assertEquals("hello", out.toString("UTF-8"))
    }
}
