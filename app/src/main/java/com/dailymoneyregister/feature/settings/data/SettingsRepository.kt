package com.dailymoneyregister.feature.settings.data

import com.dailymoneyregister.core.database.dao.BusinessBranchDao
import com.dailymoneyregister.core.database.dao.UserDao
import com.dailymoneyregister.core.database.entity.BranchEntity
import com.dailymoneyregister.core.database.entity.BusinessEntity
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.i18n.LanguageRegistry
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

sealed class SettingsLanguageResult {
    data class Success(val user: UserAccountEntity) : SettingsLanguageResult()
    data class UnsupportedLanguage(val requestedCode: String) : SettingsLanguageResult()
    data class PersistenceFailure(val message: String) : SettingsLanguageResult()
}

data class BusinessDetailsInput(
    val businessName: String,
    val branchAddress: String?,
    val branchPhone: String?,
    val branchTaxRegistrationNumber: String?
)

sealed class SettingsBusinessDetailsResult {
    object Success : SettingsBusinessDetailsResult()
    data class Invalid(val message: String) : SettingsBusinessDetailsResult()
    data class PersistenceFailure(val message: String) : SettingsBusinessDetailsResult()
}

class SettingsRepository @Inject constructor(
    private val userDao: UserDao,
    private val businessBranchDao: BusinessBranchDao
) {
    suspend fun getBusinessAndBranch(
        businessId: String,
        branchId: String
    ): Pair<BusinessEntity, BranchEntity>? {
        val business = businessBranchDao.getBusinessById(businessId) ?: return null
        val branch = businessBranchDao.getBranchById(branchId) ?: return null
        if (branch.businessId != business.id) return null
        return business to branch
    }

    suspend fun updateLanguage(
        userId: String,
        businessId: String,
        requestedLanguageCode: String
    ): SettingsLanguageResult {
        val canonicalLanguageCode = LanguageRegistry.allLanguages
            .firstOrNull { it.code.equals(requestedLanguageCode, ignoreCase = true) }
            ?.code
            ?: return SettingsLanguageResult.UnsupportedLanguage(requestedLanguageCode)

        return try {
            val updatedRows = userDao.updateLanguageForUser(
                userId = userId,
                businessId = businessId,
                languageCode = canonicalLanguageCode,
                updatedAt = System.currentTimeMillis()
            )
            if (updatedRows != 1) {
                SettingsLanguageResult.PersistenceFailure("Language update affected $updatedRows rows")
            } else {
                val persistedUser = userDao.getUserByIdInBusiness(userId, businessId)
                    ?: return SettingsLanguageResult.PersistenceFailure(
                        "Updated user could not be reread"
                    )
                SettingsLanguageResult.Success(persistedUser)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            SettingsLanguageResult.PersistenceFailure(
                exception.message ?: "Language update failed"
            )
        }
    }

    // ------------------------------------------------------------------
    // Business details (business name + branch address/phone/tax id)
    // ------------------------------------------------------------------

    /**
     * These fields only affect display (invoices, receipts). Editing them
     * is a plain settings update: no operations/documents row, no command
     * spine involvement - matching how updateLanguage() already works.
     */
    suspend fun updateBusinessDetails(
        businessId: String,
        branchId: String,
        input: BusinessDetailsInput
    ): SettingsBusinessDetailsResult {
        val name = input.businessName.trim()
        if (name.isBlank()) {
            return SettingsBusinessDetailsResult.Invalid("Business name is required")
        }
        if (name.length > 120) {
            return SettingsBusinessDetailsResult.Invalid("Business name is too long")
        }

        val address = input.branchAddress?.trim()?.takeIf { it.isNotEmpty() }
        val phone = input.branchPhone?.trim()?.takeIf { it.isNotEmpty() }
        val taxId = input.branchTaxRegistrationNumber?.trim()?.takeIf { it.isNotEmpty() }

        if (address != null && address.length > 300) {
            return SettingsBusinessDetailsResult.Invalid("Address is too long")
        }
        if (phone != null && phone.length > 40) {
            return SettingsBusinessDetailsResult.Invalid("Phone number is too long")
        }
        if (taxId != null && taxId.length > 60) {
            return SettingsBusinessDetailsResult.Invalid("Tax registration number is too long")
        }

        return try {
            val business = businessBranchDao.getBusinessById(businessId)
                ?: return SettingsBusinessDetailsResult.PersistenceFailure("Business not found")
            val branch = businessBranchDao.getBranchById(branchId)
                ?: return SettingsBusinessDetailsResult.PersistenceFailure("Branch not found")
            if (branch.businessId != businessId) {
                return SettingsBusinessDetailsResult.PersistenceFailure("Branch does not belong to this business")
            }

            val now = System.currentTimeMillis()

            val updatedBusiness =
                business.takeIf { it.name != name }
                    ?.copy(name = name, updatedAt = now)

            val updatedBranch =
                branch.takeIf {
                    it.address != address ||
                        it.phone != phone ||
                        it.taxRegistrationNumber != taxId
                }?.copy(
                        address = address,
                        phone = phone,
                        taxRegistrationNumber = taxId,
                        updatedAt = now
                )

            businessBranchDao.updateBusinessDetailsAtomically(
                business = updatedBusiness,
                branch = updatedBranch
            )

            SettingsBusinessDetailsResult.Success
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            SettingsBusinessDetailsResult.PersistenceFailure(
                exception.message ?: "Business details update failed"
            )
        }
    }
}
