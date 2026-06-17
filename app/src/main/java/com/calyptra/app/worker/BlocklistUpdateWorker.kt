package com.calyptra.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calyptra.app.CalyptraApp
import com.calyptra.app.blocklist.BlocklistUpdater

class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private companion object {
        const val TAG = "BlocklistUpdateWorker"
    }

    override suspend fun doWork(): Result {
        val updater = BlocklistUpdater()
        val app = applicationContext as CalyptraApp

        // Fetch the ad/tracker list and the always-on threat list independently so
        // one source failing (network/404/etc.) never starves the other. If either
        // fails we let WorkManager retry the whole run via the catch below.
        var anyFailed = false

        try {
            val newDomains = updater.fetch()
            if (app.blocklistManager.saveUpdate(newDomains)) {
                app.preferencesRepository.updateBlocklistMetadata(
                    System.currentTimeMillis(),
                    "remote-update"
                )
            } else {
                // Implausibly small/empty list: previous cache kept. Treat as a
                // failure so WorkManager retries — a truncated download may
                // succeed next attempt; a persistently bad feed eventually gives
                // up while protection stays intact.
                Log.w(TAG, "Ad/tracker update rejected (${newDomains.size} domains); kept previous cache")
                anyFailed = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            anyFailed = true
        }

        try {
            val newThreats = updater.fetchThreat()
            if (!app.blocklistManager.saveThreatUpdate(newThreats)) {
                Log.w(TAG, "Threat update rejected (${newThreats.size} domains); kept previous cache")
                anyFailed = true
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
