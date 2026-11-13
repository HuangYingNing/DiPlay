package com.shilapi.xcertplay

import java.io.File
import java.util.ArrayDeque

/** 只读列举应用能遍历的系统分区文件；不跟随符号链接，也不读取文件内容。 */
internal object SystemFileInventory {
    private const val MAX_FILES = 100_000
    private val roots = listOf("/system", "/system_ext", "/product", "/vendor", "/odm", "/apex")

    data class Result(val paths: List<String>, val unreadableDirectories: Int,
        val truncated: Boolean)

    fun scan(directories: List<File> = roots.map(::File)): Result {
        val paths = ArrayList<String>()
        var unreadable = 0
        var truncated = false
        val pending = ArrayDeque<File>()
        directories.filter(File::isDirectory).forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val directory = pending.removeLast()
            val children = directory.listFiles()
            if (children == null) {
                unreadable++
                continue
            }
            val resolvedDirectory = runCatching { directory.canonicalPath }.getOrNull()
            for (child in children.sortedBy(File::getName)) {
                if (resolvedDirectory == null ||
                    runCatching { child.canonicalPath != File(resolvedDirectory, child.name).path }.getOrDefault(true)) continue
                if (child.isDirectory) pending.addLast(child)
                else if (child.isFile) {
                    if (paths.size == MAX_FILES) {
                        truncated = true
                        pending.clear()
                        break
                    }
                    paths.add(child.absolutePath)
                }
            }
        }
        paths.sort()
        return Result(paths, unreadable, truncated)
    }
}
