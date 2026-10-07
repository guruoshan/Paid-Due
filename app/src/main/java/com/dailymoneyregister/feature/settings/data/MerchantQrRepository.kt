package com.dailymoneyregister.feature.settings.data

import android.content.Context
import android.net.Uri
import com.dailymoneyregister.core.database.dao.DocumentMediaDao
import com.dailymoneyregister.core.database.entity.MediaAssetEntity
import com.dailymoneyregister.core.database.entity.MerchantQrConfigEntity
import com.dailymoneyregister.core.model.enums.MediaType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.security.ActiveSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class MerchantQrSelection(
    val config: MerchantQrConfigEntity,
    val media: MediaAssetEntity
)

class MerchantQrRepository @Inject constructor(
    private val documentMediaDao: DocumentMediaDao,
    @ApplicationContext private val context: Context
) {
    suspend fun getActive(branchId: String): MerchantQrSelection? = withContext(Dispatchers.IO) {
        val config = documentMediaDao.getDefaultQrConfig(branchId)
            ?: documentMediaDao.getActiveQrConfigs(branchId).firstOrNull()
            ?: return@withContext null
        val media = documentMediaDao.getMediaById(config.mediaId)
            ?: return@withContext null
        if (!File(media.localReference).isFile) return@withContext null
        MerchantQrSelection(config, media)
    }

    suspend fun save(
        session: ActiveSession,
        sourceUri: String,
        displayLabel: String,
        bankAppName: String,
        accountLabel: String?
    ): MerchantQrSelection = withContext(Dispatchers.IO) {
        check(session.user.role == UserRole.ADMIN) { "Only an administrator can manage merchant QR settings." }
        check(session.business.id.isNotBlank() && session.branch.businessId == session.business.id) {
            "Merchant QR business context is invalid."
        }

        val mediaId = IdGenerator.newId()
        val qrDirectory = File(context.filesDir, "merchant_qr").apply { mkdirs() }
        val storedFile = File(qrDirectory, "$mediaId.bin")
        val uri = Uri.parse(sourceUri)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected QR image could not be opened." }
            storedFile.outputStream().use { output -> input.copyTo(output) }
        }

        val now = System.currentTimeMillis()
        val media = MediaAssetEntity(
            id = mediaId,
            businessId = session.business.id,
            branchId = session.branch.id,
            mediaType = MediaType.MERCHANT_QR,
            localReference = storedFile.absolutePath,
            createdAt = now
        )
        val config = MerchantQrConfigEntity(
            id = IdGenerator.newId(),
            businessId = session.business.id,
            branchId = session.branch.id,
            displayLabel = displayLabel.trim().ifBlank { "Merchant QR" },
            bankAppName = bankAppName.trim(),
            accountLabel = accountLabel?.trim()?.ifBlank { null },
            mediaId = media.id,
            isDefault = true,
            isActive = true,
            createdAt = now
        )
        documentMediaDao.replaceActiveQrConfig(
            branchId = session.branch.id,
            media = media,
            config = config
        )
        MerchantQrSelection(config, media)
    }
}
