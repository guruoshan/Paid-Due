package com.dailymoneyregister.core.cloud

import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.feature.setup.data.SetupResult
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RemoteTenantCoordinationResult {

    data class Success(
        val businessId: String,
        val branchId: String,
        val adminUserId: String,
        val authUserId: String
    ) : RemoteTenantCoordinationResult

    data object NothingPending :
        RemoteTenantCoordinationResult

    data class Failed(
        val message: String
    ) : RemoteTenantCoordinationResult
}

@Singleton
class RemoteTenantBootstrapCoordinator @Inject constructor(
    private val database: DmrDatabase,
    private val remoteAdminAuthRepository:
        RemoteAdminAuthRepository,
    private val remoteAdminBootstrapRepository:
        RemoteAdminBootstrapRepository,
    private val bootstrapStore:
        RemoteTenantBootstrapStore
) {

    private val businessBranchDao =
        database.businessBranchDao()

    private val userDao =
        database.userDao()

    fun armForSetup(
        email: String
    ): Result<Unit> =
        bootstrapStore.arm(email)

    suspend fun bootstrapAfterLocalSetup(
        result: SetupResult
    ): RemoteTenantCoordinationResult {

        val pending =
            bootstrapStore.pendingOrNull()
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Cloud bootstrap recovery state is missing"
                )

        return bootstrap(
            businessId = result.businessId.value,
            branchId = result.branchId.value,
            adminUserId = result.adminUserId.value,
            expectedEmail = pending.email
        )
    }

    suspend fun resumePendingIfNeeded():
        RemoteTenantCoordinationResult {

        val pending =
            bootstrapStore.pendingOrNull()
                ?: return RemoteTenantCoordinationResult.NothingPending

        val business =
            businessBranchDao.getFirstBusiness()
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Cloud setup is pending but the local business is missing"
                )

        val branch =
            businessBranchDao
                .getFirstBranch(business.id)
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Cloud setup is pending but the local branch is missing"
                )

        val admin =
            userDao
                .getFirstAdmin(business.id)
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Cloud setup is pending but the local Admin is missing"
                )

        return bootstrap(
            businessId = business.id,
            branchId = branch.id,
            adminUserId = admin.id,
            expectedEmail = pending.email
        )
    }

    private suspend fun bootstrap(
        businessId: String,
        branchId: String,
        adminUserId: String,
        expectedEmail: String
    ): RemoteTenantCoordinationResult {

        val authUserId =
            remoteAdminAuthRepository
                .currentAuthUserId()
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Cloud setup is incomplete. " +
                        "The Admin Supabase session is not available."
                )

        val authEmail =
            remoteAdminAuthRepository
                .currentEmail()
                ?.trim()
                ?.lowercase()
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Cloud setup is incomplete. " +
                        "The authenticated Admin email is unavailable."
                )

        if (
            authEmail !=
            expectedEmail.trim().lowercase()
        ) {
            return RemoteTenantCoordinationResult.Failed(
                "Cloud setup is linked to a different Admin email"
            )
        }

        val business =
            businessBranchDao
                .getBusinessById(businessId)
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Local business could not be loaded"
                )

        val branch =
            businessBranchDao
                .getBranchById(branchId)
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Local branch could not be loaded"
                )

        if (branch.businessId != business.id) {
            return RemoteTenantCoordinationResult.Failed(
                "Local branch does not belong to the setup business"
            )
        }

        val admin =
            userDao
                .getUserById(adminUserId)
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Local Admin could not be loaded"
                )

        if (
            admin.businessId != business.id ||
            admin.role != UserRole.ADMIN ||
            !admin.isActive
        ) {
            return RemoteTenantCoordinationResult.Failed(
                "Local Admin authority does not match the setup business"
            )
        }

        val membership =
            userDao.getMembership(
                admin.id,
                branch.id
            )
                ?: return RemoteTenantCoordinationResult.Failed(
                    "Local Admin branch membership could not be loaded"
                )

        if (membership.role != UserRole.ADMIN) {
            return RemoteTenantCoordinationResult.Failed(
                "Local membership is not ADMIN"
            )
        }

        val identity =
            admin.identityId
                ?.let {
                    userDao.getHumanIdentityById(it)
                }

        val parameters =
            RemoteAdminBootstrapParams(
                businessId = business.id,
                businessName = business.name,

                branchId = branch.id,
                branchName = branch.name,
                branchCode = branch.code,
                countryCode = branch.countryCode,
                currencyCode = branch.currencyCode,
                currencyDecimalPlaces =
                    branch.currencyDecimalPlaces,
                currencySymbol =
                    branch.currencySymbol,
                timezoneId =
                    branch.timezoneId,
                taxRoundingMode =
                    branch.taxRoundingMode.name,
                defaultTaxRateBps =
                    branch.defaultTaxRateBps,

                branchAddress =
                    branch.address,
                branchPhone =
                    branch.phone,
                taxRegistrationNumber =
                    branch.taxRegistrationNumber,

                localAdminUserId =
                    admin.id,
                adminDisplayName =
                    admin.displayName,
                adminUsername =
                    admin.username,
                adminCanonicalPhone =
                    identity?.canonicalPhone,
                languageCode =
                    admin.languageCode,

                membershipId =
                    membership.id
            )

        return when (
            val remoteResult =
                remoteAdminBootstrapRepository
                    .bootstrap(parameters)
        ) {

            is RemoteBootstrapResult.Success -> {

                if (
                    remoteResult.value.authUserId !=
                    authUserId
                ) {
                    return RemoteTenantCoordinationResult.Failed(
                        "Remote bootstrap returned a different Auth identity"
                    )
                }

                val persisted =
                    bootstrapStore.markLinked(
                        businessId = business.id,
                        branchId = branch.id,
                        adminUserId = admin.id,
                        authUserId = authUserId
                    )

                if (persisted.isFailure) {
                    return RemoteTenantCoordinationResult.Failed(
                        persisted.exceptionOrNull()?.message
                            ?: "Cloud tenant link could not be recorded"
                    )
                }

                RemoteTenantCoordinationResult.Success(
                    businessId = business.id,
                    branchId = branch.id,
                    adminUserId = admin.id,
                    authUserId = authUserId
                )
            }

            is RemoteBootstrapResult.Failed -> {

                RemoteTenantCoordinationResult.Failed(
                    "Cloud business setup is pending. " +
                        remoteResult.message
                )
            }
        }
    }
}