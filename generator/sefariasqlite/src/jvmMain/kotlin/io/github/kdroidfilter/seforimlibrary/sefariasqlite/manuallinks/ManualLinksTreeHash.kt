package io.github.kdroidfilter.seforimlibrary.sefariasqlite.manuallinks

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo

internal data class RootFile(val source: Path, val repositoryPath: String, val packagedPath: String)

internal data class RootScan(val files: List<RootFile>, val sourceTreeSha256: String, val packagedTreeSha256: String)

internal object ManualLinksTreeHash {
    private val magic = "manual-links-tree-v1\u0000".toByteArray(Charsets.UTF_8)

    fun scan(base: Path, config: ManualLinksConfig): RootScan {
        val rootStates = ArrayList<Pair<String, ExpectedState>>()
        val files = ArrayList<RootFile>()
        for (root in config.linksRoots) {
            val rootPath = base.resolve(root.path)
            val exists = Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)
            when (root.expectedState) {
                ExpectedState.PRESENT -> require(exists && rootPath.isDirectory()) { "Required links root missing: ${root.path}" }
                ExpectedState.ABSENT -> require(!exists) { "Links root expected absent: ${root.path}" }
            }
            rootStates += root.path to root.expectedState
            if (!exists) continue
            Files.walk(rootPath).use { stream ->
                stream.sorted().forEach { entry ->
                    if (entry == rootPath) return@forEach
                    require(!Files.isSymbolicLink(entry)) { "Symlink forbidden: $entry" }
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        require(entry.parent == rootPath) { "Nested directory forbidden in ${root.path}: $entry" }
                        require(Files.list(entry).use { !it.findAny().isPresent }) { "Nested content forbidden in ${root.path}: $entry" }
                        return@forEach
                    }
                    require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) { "Non-regular entry forbidden: $entry" }
                    require(entry.parent == rootPath) { "Nested file forbidden in ${root.path}: $entry" }
                    val repositoryPath = entry.relativeTo(base).toString().replace('\\', '/')
                    files += RootFile(entry, repositoryPath, "links/${entry.name}")
                }
            }
        }
        val collisions = files.groupBy { it.packagedPath }.filterValues { it.size > 1 }
        require(collisions.isEmpty()) {
            "Packaged-path collision(s): " + collisions.entries.joinToString { (path, values) ->
                "$path <- ${values.joinToString { it.repositoryPath }}"
            }
        }
        return RootScan(
            files = files,
            sourceTreeSha256 = sourceHash(rootStates, files),
            packagedTreeSha256 = packagedHash(files),
        )
    }

    fun copyConfiguredRoots(input: Path, output: Path, config: ManualLinksConfig) {
        require(!output.exists() || (output.isDirectory() && Files.list(output).use { !it.findAny().isPresent })) {
            "manualLinksOutput must be absent or empty: $output"
        }
        Files.createDirectories(output)
        config.linksRoots.filter { it.expectedState == ExpectedState.ABSENT }.forEach { root ->
            require(!Files.exists(input.resolve(root.path), LinkOption.NOFOLLOW_LINKS)) {
                "Links root expected absent: ${root.path}"
            }
        }
        config.linksRoots.filter { it.expectedState == ExpectedState.PRESENT }.forEach { root ->
            val sourceRoot = input.resolve(root.path)
            require(Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(sourceRoot)) {
                "Required links root missing or unsafe: ${root.path}"
            }
            val targetRoot = output.resolve(root.path)
            Files.createDirectories(targetRoot)
            Files.list(sourceRoot).use { stream ->
                stream.sorted().forEach { source ->
                    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)) {
                        "Only direct regular files may be copied: $source"
                    }
                    Files.copy(source, targetRoot.resolve(source.fileName))
                }
            }
        }
    }

    private fun sourceHash(roots: List<Pair<String, ExpectedState>>, files: List<RootFile>): String = digest { out ->
        out.write(magic)
        roots.sortedWith(compareByUtf8 { it.first }).forEach { (path, state) ->
            out.writeUtf8Path(path)
            out.writeByte(if (state == ExpectedState.ABSENT) 0 else 1)
        }
        files.sortedWith(compareByUtf8 { it.repositoryPath }).forEach { file -> out.writeFileRecord(file.repositoryPath, file.source) }
    }

    private fun packagedHash(files: List<RootFile>): String = digest { out ->
        out.write(magic)
        files.sortedWith(compareByUtf8 { it.packagedPath }).forEach { file -> out.writeFileRecord(file.packagedPath, file.source) }
    }

    private fun digest(write: (DataOutputStream) -> Unit): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        return ManualLinksJson.sha256(bytes.toByteArray())
    }

    private fun DataOutputStream.writeUtf8Path(path: String) {
        val bytes = path.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeFileRecord(path: String, file: Path) {
        writeUtf8Path(path)
        writeLong(Files.size(file))
        write(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
    }

    private fun <T> compareByUtf8(path: (T) -> String): Comparator<T> = Comparator { left, right ->
        compareUnsigned(path(left).toByteArray(Charsets.UTF_8), path(right).toByteArray(Charsets.UTF_8))
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val compared = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }
}
