package com.shilapi.xcertplay.update

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shilapi.xcertplay.CarPlayBackgroundSession
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object BackgroundUpdatePolicy {
    fun connectionBusy(hasSession: Boolean, active: Boolean): Boolean = hasSession && !active
    fun shouldCheck(openTask: Boolean, intervalDue: Boolean): Boolean = openTask || intervalDue
}

internal class BackgroundUpdateWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (!UpdateAvailability.backgroundChecksEnabled(applicationContext)) return Result.success()
        if (BackgroundUpdatePolicy.connectionBusy(CarPlayBackgroundSession.hasSession(), CarPlayBackgroundSession.active)) {
            return retryOrGiveUp("session busy")
        }
        val now = System.currentTimeMillis()
        if (!BackgroundUpdatePolicy.shouldCheck(inputData.getBoolean(INPUT_OPEN_CHECK, false),
                UpdateAvailability.shouldCheck(applicationContext, now))) return Result.success()
        val installedVersion = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, 0).versionName
        if (installedVersion == null || UpdateVersion.parse(installedVersion) == null) return Result.success()
        val outcome = BackgroundUpdateRetry.run(
            check = {
                val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
                    ?: throw IOException("连接管理服务不可用")
                val network = InternetNetworkSelector.select(connectivity)
                    ?: throw IOException("没有已验证的互联网连接")
                UpdateReleaseLookup.latest(installedVersion, "DiPlay/$installedVersion", network::openConnection)
            },
            canContinue = { !isStopped && UpdateAvailability.backgroundChecksEnabled(applicationContext) },
            onFailure = { attempt, failure ->
                Log.i(TAG, "background update check failed attempt=$attempt/${BackgroundUpdateRetry.MAX_ATTEMPTS}", failure)
                UpdateAvailability.recordResult(applicationContext, System.currentTimeMillis(),
                    "fetch failed: ${failure.javaClass.simpleName} ($attempt/${BackgroundUpdateRetry.MAX_ATTEMPTS})")
            },
        )
        return outcome.fold({ release ->
            if (release == null) UpdateAvailability.clear(applicationContext) else UpdateAvailability.save(applicationContext, release)
            val completedAt = System.currentTimeMillis()
            UpdateAvailability.recordAttempt(applicationContext, completedAt)
            UpdateAvailability.recordResult(applicationContext, completedAt,
                if (release == null) "up to date" else "found ${release.tagName}")
            Result.success()
        }, { failure -> retryOrGiveUp("fetch failed: ${failure.javaClass.simpleName}") })
    }

    private fun retryOrGiveUp(reason: String): Result {
        UpdateAvailability.recordResult(applicationContext, System.currentTimeMillis(), "$reason (attempt ${runAttemptCount + 1})")
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "DiPlay-Update"
        const val MAX_ATTEMPTS = 5
        const val INPUT_OPEN_CHECK = "open_check"
    }
}

internal object BackgroundUpdateScheduler {
    private const val PERIODIC_WORK = "diplay-periodic-update-check"
    private const val OPEN_WORK = "diplay-open-update-check"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager = WorkManager.getInstance(context.applicationContext)
        val periodic = PeriodicWorkRequestBuilder<BackgroundUpdateWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(constraints).setInitialDelay(10, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)
        val afterOpen = OneTimeWorkRequestBuilder<BackgroundUpdateWorker>().setConstraints(constraints)
            .setInputData(workDataOf("open_check" to true))
            .setInitialDelay(2, TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES).build()
        manager.enqueueUniqueWork(OPEN_WORK, ExistingWorkPolicy.REPLACE, afterOpen)
    }
}
