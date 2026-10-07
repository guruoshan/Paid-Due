package com.dailymoneyregister.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dailymoneyregister.core.database.entity.DocumentEntity
import com.dailymoneyregister.core.database.entity.DocumentMediaLinkEntity
import com.dailymoneyregister.core.database.entity.MediaAssetEntity
import com.dailymoneyregister.core.database.entity.MerchantQrConfigEntity
import com.dailymoneyregister.core.model.enums.DocumentType

@Dao
interface DocumentMediaDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocument(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    suspend fun getDocumentById(documentId: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE transaction_id = :transactionId AND document_type = :type LIMIT 1")
    suspend fun getDocumentForTransaction(transactionId: String, type: DocumentType): DocumentEntity?

    @Query("SELECT * FROM documents WHERE subject_type = :subjectType AND subject_id = :subjectId")
    suspend fun getDocumentsForSubject(subjectType: String, subjectId: String): List<DocumentEntity>

    // ---- Invoice support (added) -------------------------------------------------

    @Query("SELECT * FROM documents WHERE operation_id = :operationId LIMIT 1")
    suspend fun getDocumentByOperationId(operationId: String): DocumentEntity?

    /** Invoices issued by a branch inside [fromMillis, toMillis) - used for numbering. */
    @Query(
        """
        SELECT COUNT(*)
        FROM documents AS d
        INNER JOIN operations AS o ON o.id = d.operation_id
        WHERE d.document_type = 'INVOICE'
          AND o.branch_id = :branchId
          AND d.created_at >= :fromMillis
          AND d.created_at < :toMillis
        """
    )
    suspend fun countInvoicesInRange(branchId: String, fromMillis: Long, toMillis: Long): Int

    @Query(
        """
        SELECT d.*
        FROM documents AS d
        INNER JOIN operations AS o ON o.id = d.operation_id
        WHERE d.document_type = 'INVOICE'
          AND o.branch_id = :branchId
        ORDER BY d.created_at DESC
        LIMIT :limit
        """
    )
    suspend fun getInvoicesForBranch(branchId: String, limit: Int): List<DocumentEntity>

    // Document <-> Media links
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocumentMediaLink(link: DocumentMediaLinkEntity)

    @Query(
        """
        SELECT *
        FROM document_media_links
        WHERE document_id = :documentId
        ORDER BY position ASC, created_at ASC, media_id ASC
        """
    )
    suspend fun getMediaLinksForDocument(documentId: String): List<DocumentMediaLinkEntity>

    @Query(
        """
        SELECT m.*
        FROM media_assets AS m
        INNER JOIN document_media_links AS l
            ON l.media_id = m.id
        WHERE l.document_id = :documentId
        ORDER BY l.position ASC, l.created_at ASC, l.media_id ASC
        """
    )
    suspend fun getMediaForDocument(documentId: String): List<MediaAssetEntity>

    @Query(
        """
        SELECT COUNT(*)
        FROM document_media_links
        WHERE document_id = :documentId
          AND media_id = :mediaId
        """
    )
    suspend fun countDocumentMediaLink(documentId: String, mediaId: String): Int
    // Media Assets
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMedia(media: MediaAssetEntity)

    @Query("SELECT * FROM media_assets WHERE id = :mediaId LIMIT 1")
    suspend fun getMediaById(mediaId: String): MediaAssetEntity?

    // Merchant QR Configs
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQrConfig(config: MerchantQrConfigEntity)

    @Update
    suspend fun updateQrConfig(config: MerchantQrConfigEntity)

    @Query("SELECT * FROM merchant_qr_configs WHERE branch_id = :branchId AND is_active = 1")
    suspend fun getActiveQrConfigs(branchId: String): List<MerchantQrConfigEntity>

    @Query("SELECT * FROM merchant_qr_configs WHERE branch_id = :branchId AND is_default = 1 LIMIT 1")
    suspend fun getDefaultQrConfig(branchId: String): MerchantQrConfigEntity?

    /** Replaces the active QR database state as one observable unit. */
    @Transaction
    suspend fun replaceActiveQrConfig(
        branchId: String,
        media: MediaAssetEntity,
        config: MerchantQrConfigEntity
    ) {
        getActiveQrConfigs(branchId).forEach {
            updateQrConfig(it.copy(isActive = false, isDefault = false))
        }
        insertMedia(media)
        insertQrConfig(config)
    }
}
