package com.shilapi.xcertplay.update

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadRetryTest {
    @Test fun failuresRetryEveryFiveSecondsUntilSuccess() {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val result = UpdateDownloadRetry.run(
            download = {
                if (++attempts < 3) throw IOException("网络中断")
                "verified.apk"
            },
            canContinue = { true },
            pause = { delays.add(it) },
        )
        assertEquals("verified.apk", result.getOrThrow())
        assertEquals(3, attempts)
        assertEquals(listOf(5_000L, 5_000L), delays)
    }

    @Test fun supersededTaskStopsWithoutFurtherRequests() {
        var active = true
        var attempts = 0
        val result = UpdateDownloadRetry.run<Unit>(
            download = { attempts++; throw IOException("网络中断") },
            canContinue = { active },
            pause = { active = false },
        )
        assertTrue(result.isFailure)
        assertEquals(1, attempts)
    }
}
