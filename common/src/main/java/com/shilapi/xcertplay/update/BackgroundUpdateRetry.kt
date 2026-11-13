package com.shilapi.xcertplay.update

/** 自动检查由 WorkManager 启动，网络失败后每隔 20 秒重试。 */
internal object BackgroundUpdateRetry {
    const val RETRY_DELAY_MILLIS = 20_000L
    const val MAX_ATTEMPTS = 5

    fun <T> run(
        check: () -> T,
        canContinue: () -> Boolean,
        pause: (Long) -> Unit = Thread::sleep,
        onFailure: (Int, Throwable) -> Unit = { _, _ -> },
    ): Result<T> {
        var outcome: Result<T> = Result.failure(InterruptedException("自动检查已停止"))
        for (attempt in 1..MAX_ATTEMPTS) {
            if (!canContinue()) break
            outcome = runCatching(check)
            if (outcome.isSuccess) return outcome
            onFailure(attempt, outcome.exceptionOrNull()!!)
            if (attempt < MAX_ATTEMPTS && canContinue()) {
                try {
                    pause(RETRY_DELAY_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Result.failure(interrupted)
                }
            }
        }
        return outcome
    }
}
