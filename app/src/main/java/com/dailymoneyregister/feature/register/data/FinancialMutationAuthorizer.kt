package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.security.PermissionAction
import com.dailymoneyregister.core.security.PermissionChecker
import com.dailymoneyregister.core.security.PermissionDeniedException
import com.dailymoneyregister.core.security.SessionManager

/**
 * BOARDLOCK shared authorization boundary for NEW financial mutations.
 *
 * This component decides authorization only.
 *
 * It does not:
 * - execute financial commands;
 * - mutate financial records;
 * - calculate monetary amounts;
 * - redefine durable PENDING recovery intent.
 */
internal class FinancialMutationAuthorizer(
    private val database: DmrDatabase
) {
    private val userDao =
        database.userDao()

    private val businessBranchDao =
        database.businessBranchDao()

    suspend fun authorizeNewFinancialMutation(
        actorUserId: String,
        businessId: String,
        branchId: String,
        action: PermissionAction,
        actionLabel: String
    ): CommandResult<Nothing>? {
        val session =
            SessionManager.currentSession.value
                ?: return CommandResult.PermissionDenied(
                    "$actionLabel requires an authenticated session"
                )

        if (session.user.id != actorUserId) {
            return CommandResult.PermissionDenied(
                "$actionLabel actor does not match the authenticated user"
            )
        }

        if (
            session.business.id != businessId ||
            session.branch.id != branchId
        ) {
            return CommandResult.PermissionDenied(
                "$actionLabel business/branch does not match the authenticated session"
            )
        }

        if (session.requiresPinChange) {
            return CommandResult.PermissionDenied(
                "Cashier must create a new PIN before using the register"
            )
        }

        val actor =
            userDao.getUserById(actorUserId)
                ?: return CommandResult.PermissionDenied(
                    "$actionLabel actor does not exist"
                )

        if (actor.mustChangePin) {
            return CommandResult.PermissionDenied(
                "Cashier must create a new PIN before using the register"
            )
        }

        if (!actor.isActive) {
            return CommandResult.PermissionDenied(
                "Inactive user cannot perform $actionLabel"
            )
        }

        if (
            actor.businessId != businessId ||
            session.user.businessId != businessId
        ) {
            return CommandResult.PermissionDenied(
                "$actionLabel business does not match the authenticated user"
            )
        }

        val branch =
            businessBranchDao.getBranchById(branchId)
                ?: return CommandResult.Error(
                    IllegalArgumentException(
                        "$actionLabel branch does not exist"
                    )
                )

        if (
            branch.businessId != businessId ||
            session.branch.businessId != businessId
        ) {
            return CommandResult.PermissionDenied(
                "$actionLabel branch does not belong to the command business"
            )
        }

        val membership =
            userDao.getMembership(actor.id, branch.id)
                ?: return CommandResult.PermissionDenied(
                    "User is not a member of the $actionLabel branch"
                )

        session.membership?.let { sessionMembership ->
            if (
                sessionMembership.userId != actor.id ||
                sessionMembership.branchId != branch.id
            ) {
                return CommandResult.PermissionDenied(
                    "$actionLabel membership does not match the authenticated session"
                )
            }
        }

        try {
            PermissionChecker.assertPermitted(
                actor,
                membership,
                action
            )
        } catch (error: PermissionDeniedException) {
            return CommandResult.PermissionDenied(
                error.message ?: "Permission denied for $actionLabel"
            )
        }

        return null
    }
}
