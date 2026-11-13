package com.shilapi.xcertplay.update

/** 下载失败后固定等待 5 秒，继续使用已有分片，直到成功或任务被替换。 */
internal object UpdateDownloadRetry {
    const val RETRY_DELAY_MILLIS = 5_000L

    fun <T> run(
        download: () -> T,
        canContinue: () -> Boolean,
        pause: (Long) -> Unit = Thread::sleep,
        onFailure: (Int, Throwable) -> Unit = { _, _ -> },
    ): Result<T> {
        var attempt = 0
        while (canContinue()) {
            attempt++
            val outcome = runCatching(download)
            if (outcome.isSuccess) return outcome
            onFailure(attempt, outcome.exceptionOrNull()!!)
            if (!canContinue()) break
            try {
                pause(RETRY_DELAY_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.failure(interrupted)
            }
        }
        return Result.failure(InterruptedException("下载任务已停止"))
    }
}
