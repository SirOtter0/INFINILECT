// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.progress

import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider

/** Host fixture for the documented Android libcore restriction, not an Android emulator.
 * All other operations use real host files. Reintroducing getFileStore makes saves fail.
 */
internal class AndroidLikeFileSystem(private val delegate: FileSystem) : FileSystem() {
    var fileStoreQueries = 0
        private set
    private val wrappedProvider = object : FileSystemProvider() {
        private val real = delegate.provider()
        private fun unwrap(path: Path) = (path as WrappedPath).real
        override fun getScheme() = real.scheme
        override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = error("Not needed")
        override fun getFileSystem(uri: URI): FileSystem = this@AndroidLikeFileSystem
        override fun getPath(uri: URI): Path = wrap(real.getPath(uri))
        override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel =
            real.newByteChannel(unwrap(path), options, *attrs)
        override fun newFileChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): FileChannel =
            real.newFileChannel(unwrap(path), options, *attrs)
        override fun newDirectoryStream(path: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
            val stream = real.newDirectoryStream(unwrap(path)) { filter.accept(wrap(it)) }
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> {
                    val entries = stream.iterator()
                    return object : MutableIterator<Path> {
                        override fun hasNext() = entries.hasNext()
                        override fun next() = wrap(entries.next())
                        override fun remove() = entries.remove()
                    }
                }
                override fun close() = stream.close()
            }
        }
        override fun createDirectory(path: Path, vararg attrs: FileAttribute<*>) = real.createDirectory(unwrap(path), *attrs)
        override fun delete(path: Path) = real.delete(unwrap(path))
        override fun copy(source: Path, target: Path, vararg options: CopyOption) = real.copy(unwrap(source), unwrap(target), *options)
        override fun move(source: Path, target: Path, vararg options: CopyOption) = real.move(unwrap(source), unwrap(target), *options)
        override fun isSameFile(a: Path, b: Path) = real.isSameFile(unwrap(a), unwrap(b))
        override fun isHidden(path: Path) = real.isHidden(unwrap(path))
        override fun getFileStore(path: Path): FileStore {
            fileStoreQueries++
            throw SecurityException("getFileStore")
        }
        override fun checkAccess(path: Path, vararg modes: AccessMode) = real.checkAccess(unwrap(path), *modes)
        override fun <V : FileAttributeView?> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? =
            real.getFileAttributeView(unwrap(path), type, *options)
        override fun <A : BasicFileAttributes?> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A =
            real.readAttributes(unwrap(path), type, *options)
        override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> =
            real.readAttributes(unwrap(path), attributes, *options)
        override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption) =
            real.setAttribute(unwrap(path), attribute, value, *options)
    }
    fun wrap(path: Path): Path = WrappedPath(path)
    private inner class WrappedPath(val real: Path) : Path by real {
        override fun getFileSystem(): FileSystem = this@AndroidLikeFileSystem
        override fun resolve(other: String): Path = wrap(real.resolve(other))
        override fun resolve(other: Path): Path = wrap(real.resolve((other as WrappedPath).real))
        override fun getParent(): Path? = real.parent?.let(::wrap)
        override fun getFileName(): Path? = real.fileName?.let(::wrap)
        override fun getRoot(): Path? = real.root?.let(::wrap)
        override fun normalize(): Path = wrap(real.normalize())
        override fun toAbsolutePath(): Path = wrap(real.toAbsolutePath())
        override fun toString() = real.toString()
    }
    override fun provider(): FileSystemProvider = wrappedProvider
    override fun close() {} // This fixture does not own the default filesystem.
    override fun isOpen() = delegate.isOpen
    override fun isReadOnly() = delegate.isReadOnly
    override fun getSeparator() = delegate.separator
    override fun getRootDirectories(): MutableIterable<Path> = delegate.rootDirectories.map(::wrap).toMutableList()
    override fun getFileStores(): MutableIterable<FileStore> = mutableListOf()
    override fun supportedFileAttributeViews(): MutableSet<String> = delegate.supportedFileAttributeViews()
    override fun getPath(first: String, vararg more: String): Path = wrap(delegate.getPath(first, *more))
    override fun getPathMatcher(pattern: String): PathMatcher = delegate.getPathMatcher(pattern)
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = delegate.userPrincipalLookupService
    override fun newWatchService(): WatchService = delegate.newWatchService()
}
