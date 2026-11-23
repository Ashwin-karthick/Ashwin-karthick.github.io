package me.ashwinkarthick.hermes

import android.content.Context
import android.system.Os
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Where the bundled Linux lives on the phone, and how to start programs inside it.
 *
 *   files/linux/rootfs      Ubuntu root filesystem
 *   files/linux/sysdata     stand-ins for /proc files Android hides from apps
 *   files/linux/lib         libtalloc.so.2 -> the APK's libtalloc.so (proot asks for that name)
 *   files/linux/proot-tmp   proot's scratch directory
 *   files/linux/hm-linux.sh launcher (copied from assets on every start)
 */
class LinuxEnv(private val context: Context) {
    val base = File(context.filesDir, "linux")
    val rootfs = File(base, "rootfs")
    val sysdata = File(base, "sysdata")
    private val libDir = File(base, "lib")
    val prootTmp = File(base, "proot-tmp")
    val launcher = File(base, "hm-linux.sh")
    val download = File(base, "ubuntu-base.tar.gz")
    private val l2sDir = File(rootfs, ".l2s")
    private val rootfsMarker = File(base, ".rootfs-complete")

    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val proot = File(nativeDir, "libproot.so")
    private val loader = File(nativeDir, "libproot-loader.so")

    val rootfsReady: Boolean get() = rootfsMarker.isFile && File(rootfs, "usr/bin/bash").exists()

    fun markRootfsComplete() = rootfsMarker.writeText(System.currentTimeMillis().toString())

    fun clearRootfs() {
        rootfsMarker.delete()
        rootfs.deleteRecursively()
    }

    /** Files outside Ubuntu that every session needs. Safe to call repeatedly. */
    fun prepareHost() {
        listOf(base, sysdata, libDir, prootTmp).forEach { it.mkdirs() }
        // The native library directory moves when the app is updated, so always re-point the link.
        val talloc = File(libDir, "libtalloc.so.2")
        talloc.delete()
        Os.symlink(File(nativeDir, "libtalloc.so").path, talloc.path)

        context.assets.open("hm-linux.sh").use { input -> launcher.outputStream().use { input.copyTo(it) } }

        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        File(sysdata, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
        File(sysdata, "uptime").writeText("124.08 932.80\n")
        File(sysdata, "version").writeText(
            "Linux version 6.17.0-PRoot-Distro (proot@termux) (gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) " +
                "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\n"
        )
        File(sysdata, "stat").writeText(buildString {
            append("cpu  ${1957 * cpus} 0 ${2877 * cpus} ${93280 * cpus} 262 342 254 87 0 0\n")
            for (i in 0 until cpus) append("cpu$i 1957 0 2877 93280 262 342 254 87 0 0\n")
            append("intr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
        })
        File(sysdata, "vmstat").writeText(
            "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n" +
                "nr_zone_inactive_file 22858\nnr_zone_active_file 51328\nnr_zone_unevictable 642\n" +
                "nr_zone_write_pending 0\nnr_mlock 0\npgpgin 198292\npgpgout 0\npswpin 0\npswpout 0\n"
        )
        File(sysdata, "sys_empty").mkdirs()
        if (rootfs.isDirectory) l2sDir.mkdirs()
    }

    /** Files inside Ubuntu the app manages: DNS, hosts, and the step scripts. */
    fun prepareGuest() {
        File(rootfs, "etc").mkdirs()
        // Android has no resolv.conf to bind, so Ubuntu gets public resolvers.
        File(rootfs, "etc/resolv.conf").apply { delete() }.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        l2sDir.mkdirs()
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        Os.chmod(tmp.path, "1777".toInt(8))
        val dir = File(rootfs, "opt/hermes-mobile").apply { mkdirs() }
        for (name in context.assets.list("guest").orEmpty()) {
            val out = File(dir, name)
            context.assets.open("guest/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
            Os.chmod(out.path, "755".toInt(8))
        }
    }

    /**
     * Earlier attempts (before PROOT_L2S_DIR was set) left proot's hard-link placeholders inside
     * Hermes' package folders; uv then fails on them, and proot hides them from Ubuntu so they
     * can't be removed from inside. Remove those rebuildable folders from the Android side.
     * Hermes' settings and data in ~/.hermes are kept. Returns true when something was cleared.
     */
    fun clearStaleHermesPackages(): Boolean {
        val hermes = File(rootfs, "root/.hermes").toPath()
        if (!Files.isDirectory(hermes, LinkOption.NOFOLLOW_LINKS)) return false
        var stale = false
        Files.walkFileTree(hermes, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val n = file.fileName.toString()
                if (n.startsWith(".l2s.") || n.startsWith(".proot.l2s.")) { stale = true; return FileVisitResult.TERMINATE }
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        if (stale) for (dir in listOf("cache/uv", "installs")) deleteTree(hermes.resolve(dir))
        return stale
    }

    /** Deletes a tree without following symbolic links (a link is removed, never its target). */
    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
            }
        })
    }

    /** Environment for a terminal session (Android side; the launcher builds a clean one for Ubuntu). */
    fun environment(extra: Map<String, String> = emptyMap()): Array<String> {
        val env = LinkedHashMap(System.getenv())
        env["HOME"] = base.path
        env["TMPDIR"] = prootTmp.path
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env["HM_PROOT"] = proot.path
        env["HM_ROOTFS"] = rootfs.path
        env["HM_SYSDATA"] = sysdata.path
        env["HM_TERMUX_PROOT"] = "1"
        env["PROOT_LOADER"] = loader.path
        env["PROOT_TMP_DIR"] = prootTmp.path
        env["LD_LIBRARY_PATH"] = "${libDir.path}:${nativeDir.path}"
        // Keep proot's hard-link placeholders in one folder instead of next to each file, so tools
        // that walk folders (uv) never see them. Same setting proot-distro uses.
        env["PROOT_L2S_DIR"] = l2sDir.path
        env.putAll(extra)
        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    /** argv for /system/bin/sh: run [command] inside Ubuntu, or a login shell when null. */
    fun ubuntuArgs(command: String?): Array<String> =
        if (command == null) arrayOf("sh", launcher.path) else arrayOf("sh", launcher.path, command)

    /** argv for a command that runs on the Android side (before Ubuntu exists). */
    fun androidArgs(script: String): Array<String> = arrayOf("sh", "-c", script)

    companion object {
        const val SHELL = "/system/bin/sh"
    }
}
