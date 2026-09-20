package com.xiaoqiao.codeagent.runtime

import android.content.Context
import java.io.File

/**
 * Single source of truth for constructing a proot command that runs inside the Debian rootfs.
 */
object Proot {
    data class Command(
        val argv: List<String>,
        val env: Map<String, String>,
        val workingDir: File,
    )

    fun command(
        ctx: Context,
        cmd: List<String>,
        cwd: String = "/root",
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): Command {
        val boot = Bootstrap.bootDir(ctx)
        val debian = Bootstrap.debianDir(ctx)
        val cache = Bootstrap.cacheDir(ctx)
        val shm = Bootstrap.shmDir(ctx)
        boot.mkdirs()
        cache.mkdirs()
        shm.mkdirs()
        RootfsInstaller.ensureGuestNetwork(ctx)

        val proot = File(boot, "proot").absolutePath
        val loader = File(boot, "loader").absolutePath

        val argv = mutableListOf(
            proot,
            "--kill-on-exit",
            "--link2symlink",
            "--root-id",
            "--rootfs=${debian.absolutePath}",
            "--cwd=$cwd",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=${shm.absolutePath}:/dev/shm",
        )
        for ((host, guest) in extraBinds) {
            argv += "--bind=$host:$guest"
        }
        argv += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "PREFIX=/usr",
        )
        argv += cmd

        val env = mapOf(
            "PROOT_LOADER" to loader,
            "PROOT_TMP_DIR" to cache.absolutePath,
            "LD_LIBRARY_PATH" to boot.absolutePath,
        )
        return Command(argv = argv, env = env, workingDir = boot)
    }

    fun processBuilder(ctx: Context, cmd: List<String>, cwd: String = "/root"): ProcessBuilder {
        val c = command(ctx, cmd, cwd)
        return ProcessBuilder(c.argv)
            .directory(c.workingDir)
            .apply {
                environment().clear()
                environment().putAll(c.env)
                // Keep a minimal host PATH for the proot binary itself (dynamic linker resolves via LD_LIBRARY_PATH)
                environment()["PATH"] = "/system/bin:/system/xbin"
            }
            .redirectErrorStream(true)
    }
}
