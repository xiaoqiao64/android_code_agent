package com.xiaoqiao.codeagent.runtime

import android.content.Context
import java.io.File

/**
 * Copies native payloads from nativeLibraryDir into filesDir/bootstrap with real names.
 * Android only unpacks lib*.so from the APK; libtalloc.so.2 must be renamed after copy.
 */
object Bootstrap {
    private const val MARKER = "bootstrap.ok"

    fun bootDir(ctx: Context): File = File(ctx.filesDir, "bootstrap")
    fun debianDir(ctx: Context): File = File(ctx.filesDir, "debian")
    fun workspaceHostDir(ctx: Context): File = File(debianDir(ctx), "root/workspace")
    fun cacheDir(ctx: Context): File = File(ctx.cacheDir, "proot")
    fun shmDir(ctx: Context): File = File(cacheDir(ctx), "shm")

    fun isReady(ctx: Context): Boolean {
        val boot = bootDir(ctx)
        val debian = debianDir(ctx)
        return File(boot, MARKER).exists() &&
            File(boot, "proot").canExecute() &&
            File(debian, "etc").isDirectory &&
            (File(debian, "usr/bin/env").exists() || File(debian, "bin/bash").exists())
    }

    fun ensureBootstrap(ctx: Context) {
        val boot = bootDir(ctx)
        boot.mkdirs()
        shmDir(ctx).mkdirs()
        cacheDir(ctx).mkdirs()

        val native = File(ctx.applicationInfo.nativeLibraryDir)
        val copies = listOf(
            "libproot.so" to "proot",
            "libprootloader.so" to "loader",
            "libtalloc.so" to "libtalloc.so.2",
            "libandroid-shmem.so" to "libandroid-shmem.so",
        )
        for ((srcName, destName) in copies) {
            val src = File(native, srcName)
            val dest = File(boot, destName)
            if (!src.exists()) {
                // Also try jniLibs path during debug if not packaged yet
                throw IllegalStateException("Missing native library: $srcName in $native")
            }
            if (!dest.exists() || dest.length() != src.length() || dest.lastModified() < src.lastModified()) {
                src.copyTo(dest, overwrite = true)
            }
            dest.setExecutable(true, false)
            dest.setReadable(true, false)
        }
        File(boot, MARKER).writeText(System.currentTimeMillis().toString())
    }

    fun agentsInstalledMarker(ctx: Context): File = File(debianDir(ctx), "root/.codeagent_setup_ok")
}
