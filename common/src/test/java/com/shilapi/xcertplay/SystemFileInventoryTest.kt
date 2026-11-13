package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SystemFileInventoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun listsReadableFilesInStableOrderWithoutReadingContents() {
        val root = temporary.newFolder("system")
        File(root, "z.apk").writeText("secret")
        val nested = File(root, "bin").also { it.mkdirs() }
        File(nested, "a").writeText("command")
        val result = SystemFileInventory.scan(listOf(root))
        assertEquals(listOf(File(nested, "a").absolutePath, File(root, "z.apk").absolutePath), result.paths)
        assertEquals(0, result.unreadableDirectories)
        assertFalse(result.truncated)
        assertTrue(result.paths.none { it.contains("secret") || it.contains("command") })
    }
}
