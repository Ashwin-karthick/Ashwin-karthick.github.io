package me.ashwinkarthick.hermes

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

/**
 * Unpacks a .tar.gz root filesystem into a directory.
 *
 * Plain JVM code (no Android APIs) so it is unit-tested against GNU tar on a desktop JVM.
 * - Hard links are written as copies: Android does not let apps create hard links.
 * - Device nodes and FIFOs are skipped (proot binds the real /dev).
 * - Owner read/write (and search, for directories) is always kept so the app can manage the tree.
 * - Directory modes are applied last, deepest first, so a read-only directory can't block its children.
 */
class RootfsExtractor(
    private val chmod: (Path, Int) -> Unit,
    private val onProgress: (compressedBytesRead: Long) -> Unit = {},
) {
    data class Result(
        val files: Int,
        val dirs: Int,
        val symlinks: Int,
        val hardlinks: Int,
        val skipped: List<String>,
    )

    fun extract(archive: File, dest: File): Result {
        val root = dest.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        val dirModes = ArrayList<Pair<Path, Int>>()
        val skipped = ArrayList<String>()
        var files = 0; var dirs = 0; var symlinks = 0; var hardlinks = 0

        val counting = CountingStream(BufferedInputStream(FileInputStream(archive), 1 shl 16), onProgress)
        TarArchiveInputStream(GZIPInputStream(counting, 1 shl 16)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = cleanName(entry.name) ?: continue
                val target = inside(root, name)
                if (target == null) { skipped += entry.name; continue }
                when {
                    entry.isDirectory -> {
                        Files.createDirectories(target)
                        dirModes += target to entry.mode
                        dirs++
                    }
                    entry.isSymbolicLink -> {
                        Files.createDirectories(target.parent)
                        Files.deleteIfExists(target)
                        Files.createSymbolicLink(target, Paths.get(entry.linkName))
                        symlinks++
                    }
                    entry.isLink -> {
                        val source = cleanName(entry.linkName)?.let { inside(root, it) }
                        if (source == null || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                            skipped += entry.name; continue
                        }
                        Files.createDirectories(target.parent)
                        Files.deleteIfExists(target)
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                        if (!Files.isSymbolicLink(target)) chmod(target, fileMode(entry.mode))
                        hardlinks++
                    }
                    entry.isFile -> {
                        Files.createDirectories(target.parent)
                        Files.deleteIfExists(target)
                        Files.newOutputStream(target).use { out -> tar.copyTo(out, 1 shl 16) }
                        chmod(target, fileMode(entry.mode))
                        files++
                    }
                    else -> skipped += entry.name // character/block devices, FIFOs
                }
            }
        }
        for ((dir, mode) in dirModes.sortedByDescending { it.first.nameCount }) chmod(dir, dirMode(mode))
        return Result(files, dirs, symlinks, hardlinks, skipped)
    }

    companion object {
        /** rwx bits plus the sticky bit; setuid/setgid dropped. Owner can always read and write. */
        fun fileMode(mode: Int) = (mode and 0x1ff) or 0x180            // | 0600
        fun dirMode(mode: Int) = (mode and 0x3ff) or 0x1c0             // keep 01000, | 0700

        private fun cleanName(raw: String): String? {
            val n = raw.removePrefix("./").trimEnd('/')
            return n.ifEmpty { null }
        }

        /** Resolves [name] under [root]; null when it would escape the root. */
        fun inside(root: Path, name: String): Path? {
            if (name.startsWith("/")) return null
            val p = root.resolve(name).normalize()
            return if (p.startsWith(root) && p != root) p else null
        }
    }

    private class CountingStream(input: InputStream, private val report: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int = super.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        private fun bump(n: Long) {
            count += n
            if (count - lastReport >= 512 * 1024) { lastReport = count; report(count) }
        }
    }
}
