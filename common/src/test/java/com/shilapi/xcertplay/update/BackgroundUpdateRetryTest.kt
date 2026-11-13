package com.shilapi.xcertplay.update

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundUpdateRetryTest {
    @Test fun failedCheckWaitsTwentySecondsBeforeRetry() {
        var checks = 0
        val delays = mutableListOf<Long>()
        val result = BackgroundUpdateRetry.run(
            check = {
                if (++checks == 1) throw IOException("暂时断网")
                "new release"
            },
            canContinue = { true },
            pause = { delays.add(it) },
        )
        assertEquals("new release", result.getOrThrow())
        assertEquals(2, checks)
        assertEquals(listOf(20_000L), delays)
    }

    @Test fun repeatedFailuresStopAfterBoundedAttempts() {
        var checks = 0
        val delays = mutableListOf<Long>()
        val result = BackgroundUpdateRetry.run<Unit>(
            check = { checks++; throw IOException("暂时断网") },
            canContinue = { true },
            pause = { delays.add(it) },
        )
        assertTrue(result.isFailure)
        assertEquals(BackgroundUpdateRetry.MAX_ATTEMPTS, checks)
        assertEquals(BackgroundUpdateRetry.MAX_ATTEMPTS - 1, delays.size)
        assertTrue(delays.all { it == 20_000L })
    }
}
