package com.example.lalem

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cn.bmob.v3.exception.BmobException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RecordSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val owner = inputData.getString(OWNER) ?: return@withContext Result.failure()
        val app = applicationContext as LalemApplication
        val repository = LalemRepository()
        try {
            app.sessionLock.withLock {
                val user = repository.currentUser()?.takeIf { it.objectId == owner }
                    ?: return@withLock Result.success()
                app.recordStore.merge(owner, repository.records(user))
                for (record in app.recordStore.pending(owner)) {
                    val remoteId = repository.uploadRecord(user, record)
                    app.recordStore.acknowledge(owner, record, remoteId)
                }
                val synced = app.recordStore.entriesReadyToShare(owner)
                if (synced != null) {
                    val profile = repository.fetchProfile()
                    check(profile.objectId == owner)
                    repository.syncSharedRecords(profile, synced, repository.links(profile))
                }
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: BmobException) {
            // The pending records stay local until the account signs in again.
            if (error.errorCode == 206) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val OWNER = "owner"
        private fun workName(owner: String) = "lalem-record-sync-$owner"

        internal fun enqueue(context: Context, owner: String, afterPending: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<RecordSyncWorker>()
                .setInputData(workDataOf(OWNER to owner))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            val policy = if (afterPending) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(workName(owner), policy, request)
        }

        internal fun cancel(context: Context, owner: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(owner))
        }
    }
}
