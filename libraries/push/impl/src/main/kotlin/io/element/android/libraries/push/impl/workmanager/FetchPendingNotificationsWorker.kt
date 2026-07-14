/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.workmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.binding
import io.element.android.features.networkmonitor.api.NetworkMonitor
import io.element.android.features.networkmonitor.api.NetworkStatus
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.auth.SessionRestorationException
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.exception.ClientException
import io.element.android.libraries.matrix.api.exception.isNetworkError
import io.element.android.libraries.push.api.push.FetchPushForegroundServiceManager
import io.element.android.libraries.push.impl.db.PushRequest
import io.element.android.libraries.push.impl.history.PushHistoryService
import io.element.android.libraries.push.impl.notifications.NotifiableEventResolver
import io.element.android.libraries.push.impl.notifications.NotificationResultProcessor
import io.element.android.libraries.push.impl.push.PushRequestStatus
import io.element.android.libraries.push.impl.push.SyncOnNotifiableEvent
import io.element.android.libraries.workmanager.api.di.MetroWorkerFactory
import io.element.android.libraries.workmanager.api.di.WorkerKey
import io.element.android.services.analytics.api.AnalyticsLongRunningTransaction
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analytics.api.finishLongRunningTransaction
import io.element.android.services.analytics.api.recordTransaction
import io.element.android.services.analyticsproviders.api.AnalyticsTransaction
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@AssistedInject
class FetchPendingNotificationsWorker(
    @Assisted private val params: WorkerParameters,
    @ApplicationContext private val context: Context,
    private val pushHistoryService: PushHistoryService,
    private val networkMonitor: NetworkMonitor,
    private val eventResolver: NotifiableEventResolver,
    private val syncOnNotifiableEvent: SyncOnNotifiableEvent,
    private val resultProcessor: NotificationResultProcessor,
    private val analyticsService: AnalyticsService,
    private val systemClock: SystemClock,
    private val fetchPushForegroundServiceManager: FetchPushForegroundServiceManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Timber.d("FetchNotificationsWorker started")
        // RunCatching for test in debug mode
        val sessionId = runCatchingExceptions {
            inputData.getString(SyncPendingNotificationsRequestBuilder.SESSION_ID)?.let(::SessionId)
        }.getOrNull() ?: return Result.failure()

        // We can stop the foreground service and unlock the wakelock, since the work is now running and the device should be kept awake
        // HaohaoChat v26.06.4: stop() 可能阻塞 0-5s 等待 foreground service，缩短到 1s
        withTimeoutOrNull(1.seconds) {
            fetchPushForegroundServiceManager.stop()
        }

        // Fetch pending requests in the last 24 hours
        val fetchSince = Instant.fromEpochMilliseconds(systemClock.epochMillis()).minus(1.days)

        // HaohaoChat v26.06.5: 批量处理循环
        // 使用 KEEP 策略后，运行中的 Worker 完成时可能有新的 PENDING 请求
        // 此循环确保同一 Worker 实例处理所有积压的推送请求，避免延迟
        var totalProcessed = 0
        var maxIterations = 10
        var lastHadNetworkError = false

        while (maxIterations-- > 0) {
            val requests = pushHistoryService.getPendingPushRequests(sessionId, fetchSince).getOrNull() ?: return Result.failure()

            pushHistoryService.removeOldPushRequests(sessionId).onFailure {
                Timber.e(it, "Could not remove outdated push requests")
            }

            if (requests.isEmpty()) {
                Timber.d("No pending notifications to fetch (iteration ${10 - maxIterations}), returning")
                break
            }

            Timber.d("Iteration ${10 - maxIterations}: Found ${requests.size} pending push requests (total processed: $totalProcessed)")

            checkNetworkConnection(requests)?.let { failure -> return failure }

            Timber.d("Fetching ${requests.size} push requests")

            val pendingAnalyticTransactions = requests.mapNotNull { request ->
                analyticsService.finishLongRunningTransaction(AnalyticsLongRunningTransaction.PushToWorkManager(request.eventId))
                val parent = analyticsService.getLongRunningTransaction(AnalyticsLongRunningTransaction.PushToNotification(request.eventId))
                val transactionName = "WorkManager to event fetched"
                parent?.startChild(transactionName)?.let { request.eventId to it }
            }.toMap()

            Timber.d("Processing notification requests for session $sessionId")
            val results = eventResolver.resolveEvents(sessionId, requests)
                .fold(
                    onSuccess = { results ->
                        for ((_, transaction) in pendingAnalyticTransactions) {
                            transaction.finish()
                        }
                        // Update the resolved results in the queue
                        resultProcessor.emit(results)
                        results
                    },
                    onFailure = { throwable ->
                        // This is a failure at the fetch notification setup, not a failure for a single fetch notification operation
                        return handleSetupError(sessionId, requests, pendingAnalyticTransactions, throwable)
                    }
                )

            val updatedRequests = mutableListOf<PushRequest>()
            for (request in requests) {
                val result = results[request] ?: continue
                result.fold(
                    onSuccess = { updatedRequests.add(request.copy(status = PushRequestStatus.SUCCESS.value)) },
                    onFailure = { exception ->
                        if (exception is ClientException && exception.isNetworkError()) {
                            // Reset to pending so we can retry it later
                            updatedRequests.add(request.copy(status = PushRequestStatus.PENDING.value))
                            lastHadNetworkError = true
                        } else {
                            updatedRequests.add(request.copy(status = PushRequestStatus.FAILED.value))
                        }
                    }
                )
            }

            Timber.d("Notifications processed successfully (batch size: ${requests.size})")

            pushHistoryService.insertOrUpdatePushRequests(updatedRequests)
            totalProcessed += requests.size

            analyticsService.recordTransaction("Opportunistic sync", "opportunistic_sync") {
                performOpportunisticSyncIfNeeded(mapOf(sessionId to requests))
            }

            // 短暂让出执行权，避免 CPU 占用过高
            if (maxIterations > 0) {
                delay(100)
            }
        }

        Timber.d("FetchNotificationsWorker completed. Total processed: $totalProcessed, remaining iterations: $maxIterations")

        // 如果还有未处理的请求（超过 10 轮），返回 retry 让系统重新调度
        if (maxIterations <= 0) {
            val remaining = pushHistoryService.getPendingPushRequests(sessionId, fetchSince).getOrNull()
            if (!remaining.isNullOrEmpty()) {
                Timber.w("Reached max iterations with ${remaining.size} pending requests, returning retry")
                return Result.retry()
            }
        }

        return if (lastHadNetworkError) Result.retry() else Result.success()
    }

    private suspend fun performOpportunisticSyncIfNeeded(
        groupedRequests: Map<SessionId, List<PushRequest>>,
    ) {
        for ((sessionId, notificationRequests) in groupedRequests) {
            runCatchingExceptions {
                syncOnNotifiableEvent(notificationRequests)
            }.onFailure {
                Timber.e(it, "Failed to sync on notifiable events for session $sessionId")
            }
        }
    }

    private suspend fun checkNetworkConnection(requests: List<PushRequest>): Result? {
        val networkTimeoutSpans = requests.mapNotNull { request ->
            val parent = analyticsService.getLongRunningTransaction(AnalyticsLongRunningTransaction.PushToNotification(request.eventId))
            parent?.startChild("Waiting for network connectivity", "await_network")
        }

        // Wait for network to be available, but not more than 5 seconds
        // HaohaoChat v26.06.4: 10s → 5s, 减少无网络时的等待延迟
        val hasNetwork = withTimeoutOrNull(5.seconds) {
            networkMonitor.connectivity.first { it == NetworkStatus.Connected }
        } != null

        networkTimeoutSpans.finish()

        // If there is a problem with the updated network values, report it and retry if needed
        val isNetworkBlocked = networkMonitor.isNetworkBlocked.first()
        if (reportConnectivityError(requests = requests, hasNetwork = hasNetwork, isNetworkBlocked = isNetworkBlocked)) {
            pushHistoryService.insertOrUpdatePushRequests(requests.map { request ->
                request.copy(retries = request.retries + 1)
            })
            return Result.retry()
        }

        return null
    }

    private fun reportConnectivityError(requests: List<PushRequest>, hasNetwork: Boolean, isNetworkBlocked: Boolean): Boolean {
        return if (!hasNetwork || isNetworkBlocked) {
            for (request in requests) {
                val eventId = request.eventId
                analyticsService.finishLongRunningTransaction(AnalyticsLongRunningTransaction.PushToWorkManager(eventId)) {
                    it.putExtraData("has_network_connection", hasNetwork.toString())
                    it.putExtraData("is_network_blocked", isNetworkBlocked.toString())
                }
                val parent = analyticsService.getLongRunningTransaction(AnalyticsLongRunningTransaction.PushToNotification(eventId))
                // Since we're retrying, start a new transaction
                analyticsService.startLongRunningTransaction(AnalyticsLongRunningTransaction.PushToWorkManager(eventId), parent)
            }
            Timber.w("FetchNotificationsWorker will retry. Has network connectivity: $hasNetwork. Is network blocked: $isNetworkBlocked")
            true
        } else {
            false
        }
    }

    private suspend fun handleSetupError(
        sessionId: SessionId,
        requests: List<PushRequest>,
        pendingAnalyticTransactions: Map<String, AnalyticsTransaction>,
        throwable: Throwable,
    ): Result {
        for ((_, transaction) in pendingAnalyticTransactions) {
            transaction.attachError(throwable)
            transaction.finish()
        }

        // If there were failures on the setup step and they weren't recoverable, update the requests and fail
        if (throwable.cause is SessionRestorationException) {
            Timber.e(throwable, "Session $sessionId could not be restored, not retrying notification fetching")
            pushHistoryService.insertOrUpdatePushRequests(requests.map { request ->
                request.copy(status = PushRequestStatus.FAILED.value)
            })
            return Result.failure()
        }

        // If the failure is recoverable, retry
        for (request in requests) {
            val failedTransaction = pendingAnalyticTransactions[request.eventId]
            failedTransaction?.attachError(throwable)
            failedTransaction?.finish()

            val eventId = request.eventId
            val parent = analyticsService.getLongRunningTransaction(AnalyticsLongRunningTransaction.PushToNotification(eventId))
            // Since we're retrying, start a new transaction
            analyticsService.startLongRunningTransaction(AnalyticsLongRunningTransaction.PushToWorkManager(eventId), parent)
        }

        Timber.d("Re-scheduling ${requests.size} failed notification requests for session $sessionId")

        pushHistoryService.insertOrUpdatePushRequests(requests.map { request ->
            request.copy(retries = request.retries + 1)
        })

        return Result.retry()
    }

    @ContributesIntoMap(AppScope::class, binding = binding<MetroWorkerFactory.WorkerInstanceFactory<*>>())
    @WorkerKey(FetchPendingNotificationsWorker::class)
    @AssistedFactory
    interface Factory : MetroWorkerFactory.WorkerInstanceFactory<FetchPendingNotificationsWorker>
}

private fun <T : AnalyticsTransaction> Collection<T>.finish() = forEach { it.finish() }
