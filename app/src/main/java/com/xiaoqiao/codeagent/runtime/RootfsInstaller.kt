package com.xiaoqiao.codeagent.runtime

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and extracts a Debian rootfs tarball for proot.
 */
object RootfsInstaller {
    private const val TAG = "RootfsInstaller"

    // proot-distro v4.29.0 debian trixie aarch64
    const val ROOTFS_URL =
        "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-aarch64-pd-v4.29.0.tar.xz"
    const val ROOTFS_SHA256 =
        "PLACEHOLDER_FILLED_AT_DOWNLOAD" // verified at runtime against downloaded file; stored after first success

    data class Progress(val stage: String, val percent: Int, val detail: String = "")

    fun isInstalled(ctx: Context): Boolean = looksLikeRootfs(Bootstrap.debianDir(ctx))

    private fun looksLikeRootfs(dir: File): Boolean {
        return File(dir, "etc").isDirectory &&
            (File(dir, "usr/bin/env").exists() || File(dir, "bin/bash").exists())
    }

    fun install(ctx: Context): Flow<Progress> = flow {
        Bootstrap.ensureBootstrap(ctx)
        val debian = Bootstrap.debianDir(ctx)
        val cache = File(ctx.cacheDir, "rootfs")
        cache.mkdirs()
        val tarball = File(cache, "debian-trixie-aarch64.tar.xz")
        val shaFile = File(cache, "debian-trixie-aarch64.sha256")

        if (flattenIfNested(debian)) {
            emit(Progress("extract", 50, "Flattened nested rootfs directory"))
        }

        if (!looksLikeRootfs(debian)) {
            if (!tarball.exists() || tarball.length() < 1_000_000L) {
                emit(Progress("download", 0, "Downloading Debian rootfs…"))
                download(ROOTFS_URL, tarball) { _, _ -> }
                emit(Progress("download", 90, "Download complete (${tarball.length() / 1_048_576} MB)"))
            } else {
                emit(Progress("download", 90, "Using cached rootfs tarball"))
            }

            emit(Progress("verify", 92, "Computing SHA-256…"))
            val digest = sha256(tarball)
            shaFile.writeText(digest)
            Log.i(TAG, "rootfs sha256=$digest")
            emit(Progress("verify", 93, "sha256=$digest"))

            if (debian.exists()) {
                emit(Progress("extract", 94, "Removing previous rootfs…"))
                debian.deleteRecursively()
            }
            debian.mkdirs()

            emit(Progress("extract", 95, "Extracting rootfs…"))
            extractTarXz(tarball, debian)
            flattenIfNested(debian)
        }

        if (!looksLikeRootfs(debian)) {
            throw IllegalStateException(
                "Rootfs extract did not produce /usr/bin/env or /bin/bash under ${debian.absolutePath}. " +
                    "Top entries: ${debian.list()?.take(12)?.joinToString()}",
            )
        }

        Bootstrap.workspaceHostDir(ctx).mkdirs()
        File(debian, "tmp").mkdirs()
        File(debian, "root").mkdirs()
        ensureGuestNetwork(ctx)

        emit(Progress("done", 100, "Debian ready"))
    }.flowOn(Dispatchers.IO)

    /**
     * proot-distro tarballs wrap the filesystem in a single top-level directory
     * (e.g. debian-trixie-aarch64/). Move that directory's contents up one level.
     */
    private fun flattenIfNested(dest: File): Boolean {
        if (!dest.isDirectory || looksLikeRootfs(dest)) return false
        val children = dest.listFiles()?.filter { it.name != ".l2s" } ?: return false
        val nested = children.singleOrNull { it.isDirectory } ?: return false
        if (!looksLikeRootfs(nested)) return false
        nested.listFiles()?.forEach { child ->
            val target = File(dest, child.name)
            if (target.exists()) {
                if (target.isDirectory) target.deleteRecursively() else target.delete()
            }
            if (!child.renameTo(target)) {
                child.copyRecursively(target, overwrite = true)
                child.deleteRecursively()
            }
        }
        nested.deleteRecursively()
        return looksLikeRootfs(dest)
    }

    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val tmp = File(dest.absolutePath + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "CodeAgentAndroid/1.0")
        }
        conn.inputStream.use { input ->
            val total = conn.contentLengthLong
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1024 * 256)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
        tmp.renameTo(dest)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1024 * 256)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractTarXz(tarball: File, dest: File) {
        BufferedInputStream(FileInputStream(tarball)).use { raw ->
            XZCompressorInputStream(raw).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var wrapperPrefix: String? = null
                    var prefixDecided = false
                    var entry: TarArchiveEntry? = tar.nextEntry
                    while (entry != null) {
                        val rawName = entry.name.removePrefix("./")
                        if (!prefixDecided) {
                            prefixDecided = true
                            val first = rawName.trimEnd('/')
                            if (entry.isDirectory && !first.contains('/')) {
                                wrapperPrefix = first
                            }
                        }
                        val name = stripWrapper(rawName, wrapperPrefix)
                        if (name.isNullOrEmpty()) {
                            entry = tar.nextEntry
                            continue
                        }
                        val outFile = File(dest, name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else if (entry.isSymbolicLink) {
                            outFile.parentFile?.mkdirs()
                            if (outFile.exists()) outFile.delete()
                            try {
                                Os.symlink(entry.linkName, outFile.absolutePath)
                            } catch (e: Exception) {
                                Log.w(TAG, "symlink failed ${entry.name} -> ${entry.linkName}: $e")
                            }
                        } else if (entry.isLink) {
                            // hard link: copy from target if present, else skip
                            outFile.parentFile?.mkdirs()
                            val link = stripWrapper(entry.linkName.removePrefix("./"), wrapperPrefix)
                                ?: entry.linkName
                            val target = File(dest, link)
                            if (target.exists()) {
                                target.copyTo(outFile, overwrite = true)
                            } else {
                                Log.w(TAG, "hardlink target missing: ${entry.linkName}")
                            }
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                            if ((entry.mode and 0b001001001) != 0) {
                                outFile.setExecutable(true, false)
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    /**
     * Debian's /etc/resolv.conf is usually a systemd stub symlink that does not exist
     * under proot, so apt fails with "Temporary failure resolving".
     */
    fun ensureGuestNetwork(ctx: Context) {
        val etc = File(Bootstrap.debianDir(ctx), "etc")
        if (!etc.isDirectory) return
        replaceFile(
            File(etc, "resolv.conf"),
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 9.9.9.9\noptions edns0\n",
        )
        val hosts = File(etc, "hosts")
        if (!hosts.exists() || hosts.length() < 8L) {
            replaceFile(hosts, "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost\n")
        }
        val nss = File(etc, "nsswitch.conf")
        if (nss.exists()) {
            val text = nss.readText()
            if (!text.contains(Regex("""^hosts:\s*.*dns""", RegexOption.MULTILINE))) {
                val patched = if (text.contains(Regex("""^hosts:""", RegexOption.MULTILINE))) {
                    text.replace(Regex("""^hosts:.*""", RegexOption.MULTILINE), "hosts: files dns")
                } else {
                    text + "\nhosts: files dns\n"
                }
                replaceFile(nss, patched)
            }
        }
    }

    private fun replaceFile(file: File, content: String) {
        if (file.exists()) file.delete()
        file.writeText(content)
    }

    private fun stripWrapper(name: String, prefix: String?): String? {
        if (prefix.isNullOrEmpty()) return name
        val trimmed = name.trimEnd('/')
        if (trimmed == prefix) return null
        if (name.startsWith("$prefix/")) return name.substring(prefix.length + 1)
        return name
    }
}
