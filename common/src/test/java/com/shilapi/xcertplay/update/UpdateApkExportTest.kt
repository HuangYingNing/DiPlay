package com.shilapi.xcertplay.update

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateApkExportTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun copiesTheApkAndKeepsOnlyTheNewestOne() {
        val directory = File(context.getExternalFilesDir(null), "update").apply { mkdirs() }
        File(directory, "DiPlay-old.apk").writeText("old")
        val apk = File(context.cacheDir, "DiPlay-new.apk").apply { writeText("new") }
        val saved = UpdateApkExport.copy(context, apk)!!
        assertEquals(File(directory, "DiPlay-new.apk").absolutePath, saved.path)
        assertEquals("new", File(saved.path).readText())
        assertNull(saved.uri)
        assertFalse(File(directory, "DiPlay-old.apk").exists())
        assertTrue(apk.exists())
    }
}
