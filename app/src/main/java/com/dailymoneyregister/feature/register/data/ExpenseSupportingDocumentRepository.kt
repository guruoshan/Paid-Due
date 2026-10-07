package com.dailymoneyregister.feature.register.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.database.entity.DocumentEntity
import com.dailymoneyregister.core.database.entity.DocumentMediaLinkEntity
import com.dailymoneyregister.core.database.entity.MediaAssetEntity
import com.dailymoneyregister.core.model.enums.DocumentType
import com.dailymoneyregister.core.model.enums.MediaType
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.dailymoneyregister.core.security.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Non-financial evidence repository for Expense supporting documents.
 *
 * BOARDLOCK invariants:
 *
 * 1. ExpenseEntity remains the authoritative Expense.
 * 2. This repository never creates or modifies an Expense.
 * 3. This repository never creates a settlement, sale, refund or financial operation.
 * 4. Supporting documents reuse the Expense's existing operationId.
 * 5. Selected external content is copied into app-controlled private storage.
 * 6. Document/media/link database materialization is atomic.
 * 7. A failure here must be reported separately from an already-successful
 *    authoritative Expense save.
 */
data class ExpenseSupportingDocument(
    val document: DocumentEntity,
    val media: List<MediaAssetEntity>
)

data class ExpenseSupportingDocumentAttachmentResult(
    val expenseId: String,
    val documentId: String,
    val mediaId: String,
    val localReference: String
)

class ExpenseSupportingDocumentRepository @Inject constructor(
    private val database: DmrDatabase,
    @ApplicationContext private val context: Context
) {
    private val expenseDao = database.expenseDao()
    private val documentMediaDao = database.documentMediaDao()

    /**
     * Copies one selected attachment into private app storage and links it
     * to the already-persisted authoritative Expense.
     *
     * One invocation creates one SUPPORTING_DOCUMENT with one media item.
     * Repeated invocations therefore support multiple independent attachments
     * without mutating previous evidence.
     */
    suspend fun attach(
        expenseId: String,
        sourceUri: String
    ): ExpenseSupportingDocumentAttachmentResult =
        withContext(Dispatchers.IO) {

            require(expenseId.isNotBlank()) {
                "Expense ID is required."
            }

            require(sourceUri.isNotBlank()) {
                "Supporting document source is required."
            }

            val session =
                SessionManager.currentSession.value
                    ?: throw IllegalStateException(
                        "An authenticated session is required."
                    )

            check(
                session.business.id.isNotBlank() &&
                    session.branch.id.isNotBlank() &&
                    session.branch.businessId == session.business.id
            ) {
                "Expense supporting-document business context is invalid."
            }

            val expense =
                expenseDao.getExpenseById(expenseId)
                    ?: throw IllegalArgumentException(
                        "Expense was not found."
                    )

            check(
                expense.businessId == session.business.id &&
                    expense.branchId == session.branch.id
            ) {
                "Expense does not belong to the active business and branch."
            }

            /*
             * The supporting document is evidence for the persisted Expense.
             * It MUST reuse the Expense operation. We do not create another
             * financial operation for an attachment.
             */
            val documentId = IdGenerator.newId()
            val mediaId = IdGenerator.newId()
            val now = System.currentTimeMillis()

            val attachmentDirectory =
                File(
                    context.filesDir,
                    "expense_supporting_documents/$expenseId"
                ).apply {
                    check(exists() || mkdirs()) {
                        "Could not prepare Expense supporting-document storage."
                    }
                }

            val source = Uri.parse(sourceUri)

            /*
             * Keep a private opaque identifier while preserving only a safe
             * media extension derived from ContentResolver MIME metadata.
             *
             * We deliberately do NOT persist the external URI or trust its
             * display name as a filesystem path. Picker permissions may be
             * temporary and the external document may later move/disappear.
             *
             * Unknown MIME types remain ".bin", preserving the previous
             * fail-safe behavior.
             */
            val sourceMimeType =
                context.contentResolver
                    .getType(source)
                    ?.lowercase()
                    ?.substringBefore(';')
                    ?.trim()

            val safeExtension =
                when (sourceMimeType) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    "application/pdf" -> "pdf"
                    else -> "bin"
                }

            val storedFile =
                File(
                    attachmentDirectory,
                    "$mediaId.$safeExtension"
                )

            try {
                context.contentResolver.openInputStream(source).use { input ->

                    requireNotNull(input) {
                        "The selected supporting document could not be opened."
                    }

                    storedFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                check(storedFile.isFile && storedFile.length() > 0L) {
                    "The selected supporting document produced no stored evidence."
                }

                val document =
                    DocumentEntity(
                        id = documentId,
                        documentType = DocumentType.SUPPORTING_DOCUMENT,
                        subjectType = SubjectType.EXPENSE,
                        subjectId = expense.id,
                        transactionId = null,
                        operationId = expense.operationId,

                        /*
                         * The relationship itself is normalized in
                         * document_media_links. Do not hide media identifiers,
                         * file paths or financial state inside snapshotJson.
                         */
                        snapshotJson =
                            """{"kind":"expense_supporting_document","version":1}""",

                        displayNumber =
                            "EXP-${expense.id.take(8)}-${documentId.take(8)}",

                        createdAt = now,
                        createdByUserId = session.user.id,
                        customerId = expense.payeeId
                    )

                val media =
                    MediaAssetEntity(
                        id = mediaId,
                        businessId = session.business.id,
                        branchId = session.branch.id,
                        mediaType = MediaType.DOCUMENT_ATTACHMENT,
                        localReference = storedFile.absolutePath,
                        cloudReference = null,
                        createdAt = now
                    )

                val link =
                    DocumentMediaLinkEntity(
                        documentId = documentId,
                        mediaId = mediaId,
                        position = 0,
                        createdAt = now
                    )

                /*
                 * Database evidence is atomic.
                 *
                 * File I/O intentionally happens BEFORE this transaction.
                 * The authoritative Expense is NOT part of this transaction.
                 */
                database.withTransaction {

                    /*
                     * Re-read inside the DB transaction so we do not knowingly
                     * materialize evidence against an Expense that vanished or
                     * changed branch context between preparation and commit.
                     */
                    val authoritativeExpense =
                        expenseDao.getExpenseById(expenseId)
                            ?: throw IllegalStateException(
                                "Expense disappeared before supporting-document materialization."
                            )

                    check(
                        authoritativeExpense.id == expense.id &&
                            authoritativeExpense.operationId == expense.operationId &&
                            authoritativeExpense.businessId == session.business.id &&
                            authoritativeExpense.branchId == session.branch.id
                    ) {
                        "Expense changed before supporting-document materialization."
                    }

                    documentMediaDao.insertDocument(document)
                    documentMediaDao.insertMedia(media)
                    documentMediaDao.insertDocumentMediaLink(link)
                }

                ExpenseSupportingDocumentAttachmentResult(
                    expenseId = expense.id,
                    documentId = documentId,
                    mediaId = mediaId,
                    localReference = storedFile.absolutePath
                )
            } catch (failure: Throwable) {

                /*
                 * If DB materialization fails, do not leave an unreferenced
                 * private file behind.
                 *
                 * Deleting this newly-created file cannot affect the Expense.
                 */
                if (storedFile.exists()) {
                    runCatching {
                        storedFile.delete()
                    }
                }

                throw failure
            }
        }

    /**
     * Read-only supporting evidence for the active business/branch Expense.
     *
     * Missing private files are excluded from the returned media list rather
     * than silently pretending they still exist.
     */
    suspend fun getForExpense(
        expenseId: String
    ): List<ExpenseSupportingDocument> =
        withContext(Dispatchers.IO) {

            val session =
                SessionManager.currentSession.value
                    ?: return@withContext emptyList()

            val expense =
                expenseDao.getExpenseById(expenseId)
                    ?: return@withContext emptyList()

            if (
                expense.businessId != session.business.id ||
                expense.branchId != session.branch.id
            ) {
                return@withContext emptyList()
            }

            documentMediaDao
                .getDocumentsForSubject(
                    SubjectType.EXPENSE.name,
                    expense.id
                )
                .asSequence()
                .filter {
                    it.documentType ==
                        DocumentType.SUPPORTING_DOCUMENT
                }
                .filter {
                    /*
                     * Supporting evidence must be tied to the same
                     * authoritative Expense operation.
                     */
                    it.operationId == expense.operationId &&
                        it.transactionId == null
                }
                .sortedWith(
                    compareBy<DocumentEntity> { it.createdAt }
                        .thenBy { it.id }
                )
                .toList()
                .map { document ->

                    val media =
                        documentMediaDao
                            .getMediaForDocument(document.id)
                            .filter {
                                it.mediaType ==
                                    MediaType.DOCUMENT_ATTACHMENT &&
                                    it.businessId ==
                                    session.business.id &&
                                    it.branchId ==
                                    session.branch.id &&
                                    File(it.localReference).isFile
                            }

                    ExpenseSupportingDocument(
                        document = document,
                        media = media
                    )
                }
                .toList()
        }
}