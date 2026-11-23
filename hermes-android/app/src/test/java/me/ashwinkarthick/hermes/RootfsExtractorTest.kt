package me.ashwinkarthick.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * Extracts the real Ubuntu base tarball with RootfsExtractor and with GNU tar, then compares the trees.
 * Run with: ./gradlew :app:testDebugUnitTest -Drootfs.tarball=/path/to/ubuntu-base-arm64.tar.gz
 */
class RootfsExtractorTest {

    @Test
    fun rejectsPathsOutsideRoot() {
        val root = Paths.get("/tmp/root")
        assertNull(RootfsExtractor.inside(root, "../etc/passwd"))
        assertNull(RootfsExtractor.inside(root, "a/../../x"))
        assertNull(RootfsExtractor.inside(root, "/etc/passwd"))
        assertEquals(Paths.get("/tmp/root/etc/passwd"), RootfsExtractor.inside(root, "etc/passwd"))
    }

    @Test
    fun matchesGnuTar() {
        val tarball = System.getProperty("rootfs.tarball")?.let(::File)
        assumeTrue("set -Drootfs.tarball to run", tarball != null && tarball.isFile)
        val work = Files.createTempDirectory("rootfs-test").toFile()
        val ours = File(work, "ours")
        val gnu = File(work, "gnu").apply { mkdirs() }
        try {
            val result = RootfsExtractor(chmod = ::posixChmod).extract(tarball!!, ours)
            val p = ProcessBuilder("tar", "-xzf", tarball.path, "-C", gnu.path, "--no-same-owner").inheritIO().start()
            assertEquals(0, p.waitFor())

            val gnuEntries = walk(gnu.toPath())
            val ourEntries = walk(ours.toPath())
            assertEquals("same set of paths", gnuEntries.keys, ourEntries.keys)

            var compared = 0
            for ((rel, g) in gnuEntries) {
                val o = ourEntries.getValue(rel)
                assertEquals("type of $rel", g.type, o.type)
                when (g.type) {
                    "link" -> assertEquals("target of $rel", g.linkTarget, o.linkTarget)
                    "file" -> {
                        assertEquals("size of $rel", g.size, o.size)
                        assertEquals("mode of $rel", RootfsExtractor.fileMode(g.mode), o.mode)
                    }
                    "dir" -> assertEquals("mode of $rel", RootfsExtractor.dirMode(g.mode), o.mode)
                }
                compared++
            }
            println("Compared $compared entries: $result")
            assertTrue(result.files > 1000)
        } finally {
            work.deleteRecursively()
        }
    }

    private data class Entry(val type: String, val mode: Int, val size: Long, val linkTarget: String?)

    private fun walk(root: Path): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        Files.walk(root).use { stream ->
            stream.filter { it != root }.forEach { p ->
                val rel = root.relativize(p).toString()
                out[rel] = when {
                    Files.isSymbolicLink(p) -> Entry("link", 0, 0, Files.readSymbolicLink(p).toString())
                    Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> Entry("dir", mode(p), 0, null)
                    else -> Entry("file", mode(p), Files.size(p), null)
                }
            }
        }
        return out
    }

    private fun mode(p: Path): Int {
        val perms = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS)
        var m = 0
        PosixFilePermission.values().forEachIndexed { i, perm -> if (perm in perms) m = m or (1 shl (8 - i)) }
        // The sticky bit is invisible to PosixFilePermission; read it from the unix view.
        val unixMode = (Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int)
        return m or (unixMode and 0x200)
    }

    companion object {
        fun posixChmod(p: Path, mode: Int) {
            val perms = HashSet<PosixFilePermission>()
            PosixFilePermission.values().forEachIndexed { i, perm -> if (mode and (1 shl (8 - i)) != 0) perms += perm }
            Files.setPosixFilePermissions(p, perms)
            if (mode and 0x200 != 0) { // sticky bit: not settable through PosixFilePermission
                ProcessBuilder("chmod", Integer.toOctalString(mode), p.toString()).start().waitFor()
            }
        }
    }
}
