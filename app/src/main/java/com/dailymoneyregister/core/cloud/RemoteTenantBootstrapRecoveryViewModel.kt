package com.dailymoneyregister.core.cloud

import androidx.lifecycle.ViewModel
import com.dailymoneyregister.core.model.ids.BranchId
import com.dailymoneyregister.core.model.ids.BusinessId
import com.dailymoneyregister.core.model.ids.UserId
import com.dailymoneyregister.feature.setup.data.SetupRepository
import com.dailymoneyregister.feature.setup.data.SetupResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RemoteTenantBootstrapRecoveryViewModel @Inject constructor(
    private val coordinator:
        RemoteTenantBootstrapCoordinator,
    private val setupRepository:
        SetupRepository
) : ViewModel() {

    suspend fun recoverPendingBootstrap():
        String? {

        return when (
            val result =
                coordinator
                    .resumePendingIfNeeded()
        ) {

            is RemoteTenantCoordinationResult.Success -> {

                /*
                 * Remote recovery succeeded using the already durable
                 * local Business / Branch / Admin.
                 *
                 * Only now establish the trusted Admin session.
                 */
                setupRepository.establishAdminSession(
                    SetupResult(
                        businessId =
                            BusinessId(result.businessId),
                        branchId =
                            BranchId(result.branchId),
                        adminUserId =
                            UserId(result.adminUserId)
                    )
                )

                null
            }

            RemoteTenantCoordinationResult.NothingPending ->
                null

            is RemoteTenantCoordinationResult.Failed ->
                result.message
        }
    }
}