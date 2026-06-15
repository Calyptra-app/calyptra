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
        return try {
            val updater = BlocklistUpdater()
            // In real app, url might come from settings
            val newDomains = updater.fetch()
            
            if (newDomains.isNotEmpty()) {
                val app = applicationContext as CalyptraApp
                app.blocklistManager.saveUpdate(newDomains)
                
                // Update timestamp in prefs
                app.preferencesRepository.updateBlocklistMetadata(
                    System.currentTimeMillis(),
                    "remote-update"
                )
            }
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
