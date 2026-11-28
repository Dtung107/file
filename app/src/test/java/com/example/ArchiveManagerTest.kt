package com.example

import com.example.filemanager.archive.*
import com.example.filemanager.utils.FileUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fileUtilsDetectsMultiPartArchiveExtensions() {
        assertEquals("tar.gz", FileUtils.getExtension("backup.tar.gz"))
        assertEquals("tar.bz2", FileUtils.getExtension("archive.tar.bz2"))
        assertEquals("tar.xz", FileUtils.getExtension("data.tar.xz"))
        assertEquals("tar.lz4", FileUtils.getExtension("fast.tar.lz4"))
        assertEquals("tar.zstd", FileUtils.getExtension("modern.tar.zstd"))
        assertEquals("7z", FileUtils.getExtension("game.7z"))
        assertEquals("zip", FileUtils.getExtension("app.zip"))
    }

    @Test
    fun createAndExtractZipWithAes256Password() = runBlocking {
        val srcDir = tempFolder.newFolder("source_zip")
        val sampleFile = File(srcDir, "hello.txt").apply { writeText("Xin chào File Manager Pro!") }
        val outDir = tempFolder.newFolder("out_zip")
        val extractDir = tempFolder.newFolder("extract_zip")

        val config = ArchiveCompressionConfig(
            archiveFileName = "secret.zip",
            destinationDir = outDir,
            format = ArchiveFormat.ZIP,
            compressionLevel = ArchiveCompressionLevel.FAST,
            encryptionMethod = ArchiveEncryptionMethod.AES_256,
            password = "MyPassword123"
        )

        val createRes = ArchiveManager.createArchive(listOf(sampleFile), config)
        assertTrue(createRes.isSuccess)
        val zipFile = createRes.getOrThrow().single()
        assertTrue(zipFile.exists())

        val listRes = ArchiveManager.listArchiveEntries(zipFile, "MyPassword123")
        assertTrue(listRes.isSuccess)
        assertEquals(1, listRes.getOrThrow().size)

        // Wrong or missing password should fail with ArchivePasswordRequiredException
        val wrongRes = ArchiveManager.extractAll(zipFile, extractDir, null)
        assertTrue(wrongRes.isFailure)
        assertTrue(wrongRes.exceptionOrNull() is ArchivePasswordRequiredException)

        // Correct password extracts cleanly
        val extractRes = ArchiveManager.extractAll(zipFile, extractDir, "MyPassword123")
        assertTrue(extractRes.isSuccess)
        val extractedFile = File(extractDir, "hello.txt")
        assertTrue(extractedFile.exists())
        assertEquals("Xin chào File Manager Pro!", extractedFile.readText())
    }

    @Test
    fun createAndExtract7zAndTarFormats() = runBlocking {
        val srcFile = tempFolder.newFile("config.json").apply { writeText("""{"ok":true}""") }
        val outDir = tempFolder.newFolder("archives")

        for (fmt in listOf(ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_LZ4)) {
            val archiveName = ArchiveFormat.replaceExtension("bundle", fmt)
            val config = ArchiveCompressionConfig(
                archiveFileName = archiveName,
                destinationDir = outDir,
                format = fmt,
                compressionLevel = ArchiveCompressionLevel.FAST
            )
            val created = ArchiveManager.createArchive(listOf(srcFile), config)
            assertTrue("Create failed for $fmt", created.isSuccess)
            val archiveFile = created.getOrThrow().single()

            val entries = ArchiveManager.listArchiveEntries(archiveFile)
            assertTrue("List failed for $fmt", entries.isSuccess)
            assertTrue(entries.getOrThrow().any { it.name == "config.json" })

            val targetDir = tempFolder.newFolder("extract_${fmt.name}")
            val extracted = ArchiveManager.extractAll(archiveFile, targetDir)
            assertTrue("Extract failed for $fmt", extracted.isSuccess)
            assertEquals("""{"ok":true}""", File(targetDir, "config.json").readText())
        }
    }

    @Test
    fun separateArchivesAndDeleteSource() = runBlocking {
        val workDir = tempFolder.newFolder("sep_src")
        val f1 = File(workDir, "one.txt").apply { writeText("111") }
        val f2 = File(workDir, "two.txt").apply { writeText("222") }
        val destDir = tempFolder.newFolder("sep_dest")

        val config = ArchiveCompressionConfig(
            archiveFileName = "ignored.zip",
            destinationDir = destDir,
            format = ArchiveFormat.ZIP,
            compressionLevel = ArchiveCompressionLevel.NORMAL,
            deleteSourceAfterCompression = true,
            createSeparateArchives = true
        )

        val res = ArchiveManager.createArchive(listOf(f1, f2), config)
        assertTrue(res.isSuccess)
        assertEquals(2, res.getOrThrow().size)
        assertTrue(File(destDir, "one.zip").exists())
        assertTrue(File(destDir, "two.zip").exists())
        assertFalse(f1.exists())
        assertFalse(f2.exists())
    }

    @Test
    fun compressAcrossDirectoriesWithDuplicateNamesPreservesAllFiles() = runBlocking {
        val dirAlpha = tempFolder.newFolder("Alpha")
        val dirBeta = tempFolder.newFolder("Beta")
        val fAlpha = File(dirAlpha, "readme.txt").apply { writeText("content-alpha") }
        val fBeta = File(dirBeta, "readme.txt").apply { writeText("content-beta") }
        val fUnique = File(dirBeta, "notes.md").apply { writeText("unique-note") }
        val outDir = tempFolder.newFolder("out_cross_dir")
        val extractDir = tempFolder.newFolder("extract_cross_dir")

        val config = ArchiveCompressionConfig(
            archiveFileName = "cross_dir_batch.zip",
            destinationDir = outDir,
            format = ArchiveFormat.ZIP,
            compressionLevel = ArchiveCompressionLevel.FAST,
            encryptionMethod = ArchiveEncryptionMethod.NONE
        )

        val res = ArchiveManager.createArchive(listOf(fAlpha, fBeta, fUnique), config)
        assertTrue(res.isSuccess)
        val zipFile = res.getOrThrow().single()

        val extractRes = ArchiveManager.extractAll(zipFile, extractDir)
        assertTrue(extractRes.isSuccess)
        assertEquals("content-alpha", File(extractDir, "Alpha/readme.txt").readText())
        assertEquals("content-beta", File(extractDir, "Beta/readme.txt").readText())
        assertEquals("unique-note", File(extractDir, "notes.md").readText())
    }

    @Test
    fun deleteEntriesFromZipRemovesFilesAndDirectories() = runBlocking {
        val srcRoot = tempFolder.newFolder("zip_delete_src")
        val subDir = File(srcRoot, "replays").apply { mkdirs() }
        File(subDir, "match1.dat").writeText("r1")
        File(subDir, "match2.dat").writeText("r2")
        val keepFile = File(srcRoot, "keep.txt").apply { writeText("stay") }
        val removeSingle = File(srcRoot, "drop.txt").apply { writeText("gone") }
        val outDir = tempFolder.newFolder("zip_delete_out")

        val config = ArchiveCompressionConfig(
            archiveFileName = "editable.zip",
            destinationDir = outDir,
            format = ArchiveFormat.ZIP,
            compressionLevel = ArchiveCompressionLevel.FAST
        )

        val created = ArchiveManager.createArchive(listOf(subDir, keepFile, removeSingle), config)
        assertTrue(created.isSuccess)
        val zipFile = created.getOrThrow().single()

        val deleteRes = ArchiveManager.deleteEntriesFromZip(zipFile, listOf("replays/", "drop.txt"))
        assertTrue(deleteRes.isSuccess)
        assertTrue((deleteRes.getOrNull() ?: 0) >= 3)

        val remaining = ArchiveManager.listArchiveEntries(zipFile).getOrThrow().map { it.path }
        assertTrue(remaining.contains("keep.txt"))
        assertFalse(remaining.any { it.startsWith("replays") })
        assertFalse(remaining.contains("drop.txt"))
    }

    @Test
    fun fastExtractAllAndDirectoryWithManyNestedDatFiles() = runBlocking {
        val srcRoot = tempFolder.newFolder("many_dat_src")
        val docDir = File(srcRoot, "kho/doc").apply { mkdirs() }
        val otherDir = File(srcRoot, "kho/other").apply { mkdirs() }
        val expectedCount = 120
        for (i in 0 until expectedCount) {
            File(docDir, "item_$i.dat").writeText("payload-$i")
        }
        File(otherDir, "skip.txt").writeText("skip-me")

        val outDir = tempFolder.newFolder("many_dat_out")
        val config = ArchiveCompressionConfig(
            archiveFileName = "bundle.zip",
            destinationDir = outDir,
            format = ArchiveFormat.ZIP,
            compressionLevel = ArchiveCompressionLevel.FAST
        )
        val zipFile = ArchiveManager.createArchive(listOf(File(srcRoot, "kho")), config).getOrThrow().single()

        // 1. extractAll fast stream path
        val extractAllDir = tempFolder.newFolder("many_dat_all")
        val allRes = ArchiveManager.extractAll(zipFile, extractAllDir)
        assertTrue(allRes.isSuccess)
        assertEquals(expectedCount + 1, allRes.getOrThrow())
        assertEquals("payload-0", File(extractAllDir, "kho/doc/item_0.dat").readText())
        assertEquals("payload-119", File(extractAllDir, "kho/doc/item_119.dat").readText())

        // 2. extractDirectory fast stream path
        val extractSubDir = tempFolder.newFolder("many_dat_sub")
        val dirRes = ArchiveManager.extractDirectory(zipFile, "kho/doc/", extractSubDir)
        assertTrue(dirRes.isSuccess)
        assertEquals(expectedCount, dirRes.getOrThrow())
        assertEquals("payload-42", File(extractSubDir, "item_42.dat").readText())
        assertFalse(File(extractSubDir, "skip.txt").exists())

        // 3. extractEntries fast stream path (> 4 entries)
        val selectedPaths = (0 until 8).map { "kho/doc/item_$it.dat" }
        val extractEntriesDir = tempFolder.newFolder("many_dat_entries")
        val entriesRes = ArchiveManager.extractEntries(zipFile, selectedPaths, extractEntriesDir)
        assertTrue(entriesRes.isSuccess)
        assertEquals(8, entriesRes.getOrThrow())
        assertEquals("payload-7", File(extractEntriesDir, "kho/doc/item_7.dat").readText())
    }

    @Test
    fun extractDirectoryEncryptedZipAndTarGz() = runBlocking {
        val srcRoot = tempFolder.newFolder("enc_dir_src")
        val sub = File(srcRoot, "sub").apply { mkdirs() }
        File(sub, "a.txt").writeText("alpha")
        File(sub, "b.txt").writeText("beta")
        File(srcRoot, "root.txt").writeText("root")
        val outDir = tempFolder.newFolder("enc_dir_out")

        val encZip = ArchiveManager.createArchive(
            listOf(sub, File(srcRoot, "root.txt")),
            ArchiveCompressionConfig(
                archiveFileName = "enc.zip",
                destinationDir = outDir,
                format = ArchiveFormat.ZIP,
                encryptionMethod = ArchiveEncryptionMethod.AES_256,
                password = "pass"
            )
        ).getOrThrow().single()

        val failDir = tempFolder.newFolder("enc_dir_fail")
        val noPass = ArchiveManager.extractDirectory(encZip, "sub/", failDir, password = null)
        assertTrue(noPass.isFailure)
        assertTrue(noPass.exceptionOrNull() is ArchivePasswordRequiredException)

        val okDir = tempFolder.newFolder("enc_dir_ok")
        val withPass = ArchiveManager.extractDirectory(encZip, "sub/", okDir, password = "pass")
        assertTrue(withPass.isSuccess)
        assertEquals(2, withPass.getOrThrow())
        assertEquals("alpha", File(okDir, "a.txt").readText())
        assertEquals("beta", File(okDir, "b.txt").readText())
        assertFalse(File(okDir, "root.txt").exists())

        val tarGz = ArchiveManager.createArchive(
            listOf(sub, File(srcRoot, "root.txt")),
            ArchiveCompressionConfig(
                archiveFileName = "data.tar.gz",
                destinationDir = outDir,
                format = ArchiveFormat.TAR_GZ
            )
        ).getOrThrow().single()
        val tarDir = tempFolder.newFolder("tar_dir_ok")
        val tarRes = ArchiveManager.extractDirectory(tarGz, "sub/", tarDir)
        assertTrue(tarRes.isSuccess)
        assertEquals(2, tarRes.getOrThrow())
        assertEquals("alpha", File(tarDir, "a.txt").readText())
    }

    @Test
    fun extractAllSkipsZipSlipTraversalEntries() = runBlocking {
        val zipFile = tempFolder.newFile("zipslip.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../outside.txt"))
            zos.write("malicious".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("safe/inside.txt"))
            zos.write("safe-data".toByteArray())
            zos.closeEntry()
        }

        val parentSandbox = tempFolder.newFolder("sandbox")
        val targetDir = File(parentSandbox, "target").apply { mkdirs() }
        val res = ArchiveManager.extractAll(zipFile, targetDir)
        assertTrue(res.isSuccess)
        assertEquals(1, res.getOrThrow())
        assertFalse(File(parentSandbox, "outside.txt").exists())
        assertEquals("safe-data", File(targetDir, "safe/inside.txt").readText())
    }

    @Test
    fun fastExtensionDetectionMatchesExpectedFileTypes() {
        assertEquals(com.example.filemanager.model.FileType.FOLDER, FileUtils.detectFileTypeFast("anything", isDirectory = true))
        assertEquals(com.example.filemanager.model.FileType.GAME_DATA, FileUtils.detectFileTypeFast("character.2.dat", isDirectory = false))
        assertEquals(com.example.filemanager.model.FileType.TEXT_CODE, FileUtils.detectFileTypeFast("index.ts", isDirectory = false))
        assertEquals(com.example.filemanager.model.FileType.APK, FileUtils.detectFileTypeFast("app.apk", isDirectory = false))
        assertEquals(com.example.filemanager.model.FileType.ARCHIVE, FileUtils.detectFileTypeFast("backup.tar.gz", isDirectory = false))
    }
}
