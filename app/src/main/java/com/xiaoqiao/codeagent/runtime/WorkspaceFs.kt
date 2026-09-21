package com.xiaoqiao.codeagent.runtime

import java.io.File
import java.io.OutputStream

data class FsEntry(
    val name: String,
    val isDirectory: Boolean,
    val relativePath: String,
)

object WorkspaceFs {
    fun hostRoot(debianDir: File, guestWorkspace: String): File =
        File(debianDir, guestWorkspace.removePrefix("/"))

    fun resolve(root: File, relative: String): File {
        val target = if (relative.isBlank() || relative == ".") root else File(root, relative)
        val canonicalRoot = root.canonicalFile
        val canonical = target.canonicalFile
        if (!isInside(canonicalRoot, canonical)) {
            throw IllegalArgumentException("path escapes workspace")
        }
        return canonical
    }

    fun isInside(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalFile.path
        val path = candidate.canonicalFile.path
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    fun list(root: File, relative: String = "."): List<FsEntry> {
        val canonicalRoot = root.canonicalFile
        val dir = resolve(root, relative)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { f ->
                FsEntry(
                    name = f.name,
                    isDirectory = f.isDirectory,
                    relativePath = f.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath,
                )
            }
    }

    fun parentRelative(relative: String): String? {
        if (relative.isBlank() || relative == ".") return null
        val parent = File(relative).parent
        return if (parent.isNullOrBlank()) "." else parent
    }

    fun validateName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "empty name" }
        require('/' !in trimmed && '\\' !in trimmed) { "invalid name" }
        require(trimmed != "." && trimmed != "..") { "invalid name" }
        return trimmed
    }

    fun rename(root: File, relative: String, newName: String): File {
        val src = resolve(root, relative)
        if (src.canonicalFile == root.canonicalFile) {
            throw IllegalArgumentException("cannot rename workspace root")
        }
        val destName = validateName(newName)
        val dest = File(src.parentFile, destName)
        if (!isInside(root, dest)) {
            throw IllegalArgumentException("path escapes workspace")
        }
        if (dest.exists()) throw IllegalStateException("already exists")
        if (!src.renameTo(dest)) throw IllegalStateException("rename failed")
        return dest
    }

    fun delete(root: File, relative: String) {
        val target = resolve(root, relative)
        if (target.canonicalFile == root.canonicalFile) {
            throw IllegalArgumentException("cannot delete workspace root")
        }
        if (!target.exists()) return
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!ok) throw IllegalStateException("delete failed")
    }

    fun copyToStream(src: File, output: OutputStream) {
        src.inputStream().use { it.copyTo(output) }
    }
}
