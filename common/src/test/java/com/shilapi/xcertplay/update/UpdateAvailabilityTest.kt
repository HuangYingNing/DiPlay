package com.shilapi.xcertplay.update

import android.content.Context
import org.junit.After
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
@Config(sdk = [25, 29, 35])
class UpdateAvailabilityTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(context)

    @Test fun persistsANewerBuildRelease() {
        val release = release("v0.2.16-b261108-002")
        UpdateAvailability.save(context, release)
        assertEquals(release, UpdateAvailability.available(context, "v0.2.16-b261108-001"))
    }

    @Test fun removesAReleaseAfterTheAppCatchesUp() {
        UpdateAvailability.save(context, release("v0.2.16-b261108-002"))
        assertNull(UpdateAvailability.available(context, "v0.2.16-b261108-002"))
        assertNull(UpdateAvailability.available(context, "v0.2.16-b261108-001"))
    }

    @Test fun backgroundChecksDefaultOnAndPersistTheChoice() {
        assertTrue(UpdateAvailability.backgroundChecksEnabled(context))
        UpdateAvailability.saveBackgroundChecksEnabled(context, false)
        assertFalse(UpdateAvailability.backgroundChecksEnabled(context))
    }

    @Test fun checksAtMostOncePerDayButRecoversFromAClockReset() {
        val now = 10L * UpdateAvailability.CHECK_INTERVAL_MILLIS
        assertTrue(UpdateAvailability.shouldCheck(context, now))
        UpdateAvailability.recordAttempt(context, now)
        assertFalse(UpdateAvailability.shouldCheck(context, now + UpdateAvailability.CHECK_INTERVAL_MILLIS - 1))
        assertTrue(UpdateAvailability.shouldCheck(context, now + UpdateAvailability.CHECK_INTERVAL_MILLIS))
        assertTrue(UpdateAvailability.shouldCheck(context, now - 1))
    }

    private fun release(tag: String) = UpdateRelease(
        tagName = tag,
        apkName = "DiPlay-$tag.apk",
        apkUrl = "https://github.com/HuangYingNing/DiPlay/releases/download/$tag/DiPlay-$tag.apk",
        checksumsUrl = "https://github.com/HuangYingNing/DiPlay/releases/download/$tag/SHA256SUMS.txt",
    )
}
