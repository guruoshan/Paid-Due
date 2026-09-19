package com.dailymoneyregister.core.startup

import android.util.Log
import com.dailymoneyregister.feature.register.data.RegisterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup financial recovery gate.
 *
 * Financial entry must not become available until durable PENDING
 * operations have been examined and reconciled.
 *
 * Recovery is serialized so multiple callers cannot run competing
 * startup recovery passes.
 */
@Singleton
class StartupRecoveryCoordinator @Inject constructor(
    private val registerRepository: RegisterRepository
) {

    private val recoveryMutex = Mutex()

    @Volatile
    private var recoveryCompleted = false

    suspend fun recoverBeforeFinancialUse(): Result<Unit> {
        return recoveryMutex.withLock {

            if (recoveryCompleted) {
                return@withLock Result.success(Unit)
            }

            try {
                withContext(Dispatchers.IO) {
                    registerRepository.recoverPendingCashSales()
                }

                recoveryCompleted = true

                Result.success(Unit)

            } catch (error: Exception) {

                Log.e(
                    "StartupRecovery",
                    "Unable to safely recover pending financial operations",
                    error
                )

                Result.failure(error)
            }
        }
    }
}
