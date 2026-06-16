package com.calyptra.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calyptra.app.CalyptraApp
import com.calyptra.app.blocklist.BlocklistUpdater

class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updater = BlocklistUpdater()
        val app = applicationContext as CalyptraApp

        // Fetch the ad/tracker list and the always-on threat list independently so
        // one source failing (network/404/etc.) never starves the other. If either
        // fails we let WorkManager retry the whole run via the catch below.
        var anyFailed = false

        try {
            val newDomains = updater.fetch()
            if (newDomains.isNotEmpty()) {
                app.blocklistManager.saveUpdate(newDomains)
                app.preferencesRepository.updateBlocklistMetadata(
                    System.currentTimeMillis(),
                    "remote-update"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            anyFailed = true
        }

        try {
            val newThreats = updater.fetchThreat()
            if (newThreats.isNotEmpty()) {
                app.blocklistManager.saveThreatUpdate(newThreats)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            anyFailed = true
        }

        return if (!anyFailed) {
            Result.success()
        } else if (runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.failure()
        }
    }
}
