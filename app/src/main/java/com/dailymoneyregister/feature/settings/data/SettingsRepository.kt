package com.dailymoneyregister.feature.settings.data

import com.dailymoneyregister.core.database.dao.UserDao
import com.dailymoneyregister.core.database.entity.UserAccountEntity
import com.dailymoneyregister.core.i18n.LanguageRegistry
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

sealed class SettingsLanguageResult {
    data class Success(val user: UserAccountEntity) : SettingsLanguageResult()
    data class UnsupportedLanguage(val requestedCode: String) : SettingsLanguageResult()
    data class PersistenceFailure(val message: String) : SettingsLanguageResult()
}

class SettingsRepository @Inject constructor(
    private val userDao: UserDao
) {
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
}
