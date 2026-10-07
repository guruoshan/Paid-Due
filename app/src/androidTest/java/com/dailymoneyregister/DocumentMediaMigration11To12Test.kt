package com.dailymoneyregister

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentMediaMigration11To12Test {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DmrDatabase::class.java
    )

    @Test
    fun migration11To12PreservesExistingEvidenceAndCreatesValidatedDocumentMediaLinks() {
        val databaseName = "dmr-migration-11-12-document-media-test"

        val source =
            migrationHelper.createDatabase(databaseName, 11)

        /*
         * Exact VERSION 11 fixture.
         *
         * Only rows needed to establish the existing document/media
         * evidence are seeded.
         */

        source.execSQL(
            """
            INSERT INTO businesses (
                id,
                name,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v12-business",
                "V12 Business",
                1000L,
                1000L
            )
        )

        source.execSQL(
            """
            INSERT INTO branches (
                id,
                business_id,
                name,
                code,
                country_code,
                currency_code,
                currency_decimal_places,
                currency_symbol,
                timezone_id,
                tax_rounding_mode,
                default_tax_rate_bps,
                address,
                phone,
                tax_registration_number,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v12-branch",
                "v12-business",
                "V12 Branch",
                "V12",
                "OM",
                "OMR",
                3,
                "OMR",
                "Asia/Muscat",
                "HALF_UP",
                0,
                null,
                null,
                null,
                1001L,
                1001L
            )
        )

        source.execSQL(
            """
            INSERT INTO operations (
                id,
                business_id,
                branch_id,
                operation_type,
                subject_type,
                subject_id,
                transaction_id,
                cash_shift_id,
                actor_user_id,
                device_id,
                occurred_at,
                request_fingerprint,
                command_schema_version,
                intent_payload,
                status,
                result_reference_type,
                result_reference_id,
                reason
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v12-operation",
                "v12-business",
                "v12-branch",
                "ISSUE_INVOICE",
                "DOCUMENT",
                "v12-document",
                null,
                null,
                "v12-user",
                "v12-device",
                1100L,
                "v12-fingerprint",
                1,
                """{"migration":"v11-fixture"}""",
                "COMMITTED",
                "DOCUMENT",
                "v12-document",
                null
            )
        )

        source.execSQL(
            """
            INSERT INTO documents (
                id,
                document_type,
                subject_type,
                subject_id,
                transaction_id,
                operation_id,
                snapshot_json,
                display_number,
                created_at,
                created_by_user_id,
                customer_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v12-document",
                "INVOICE",
                "DOCUMENT",
                "v12-document",
                null,
                "v12-operation",
                """{"migration":"keep-document"}""",
                "V12-DOC-001",
                1200L,
                "v12-user",
                null
            )
        )

        source.execSQL(
            """
            INSERT INTO media_assets (
                id,
                business_id,
                branch_id,
                media_type,
                local_reference,
                cloud_reference,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v12-media",
                "v12-business",
                "v12-branch",
                "DOCUMENT_ATTACHMENT",
                "files/v12-evidence.jpg",
                null,
                1300L
            )
        )

        source.close()

        // This is the point at which production MIGRATION_11_12
        // is actually exercised.
        val migrated =
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                12,
                true,
                DmrDatabase.MIGRATION_11_12
            )

        
        /*
         * MigrationTestHelper exposes a raw SQLite connection.
         * SQLite foreign-key enforcement is connection-scoped.
         *
         * First observe the connection state, then explicitly enable
         * enforcement for the behavioural FK assertions below.
         *
         * This does NOT modify production migration behaviour.
         */
        var foreignKeysBefore = -1

        migrated.query(
            "PRAGMA foreign_keys"
        ).use { cursor ->
            assertTrue(
                "PRAGMA foreign_keys must return one row",
                cursor.moveToFirst()
            )

            foreignKeysBefore =
                cursor.getInt(0)
        }

        assertTrue(
            "Unexpected PRAGMA foreign_keys value: $foreignKeysBefore",
            foreignKeysBefore == 0 || foreignKeysBefore == 1
        )

        if (foreignKeysBefore == 0) {
            migrated.execSQL(
                "PRAGMA foreign_keys = ON"
            )
        }

        var foreignKeysAfter = -1

        migrated.query(
            "PRAGMA foreign_keys"
        ).use { cursor ->
            assertTrue(
                "PRAGMA foreign_keys must remain readable",
                cursor.moveToFirst()
            )

            foreignKeysAfter =
                cursor.getInt(0)
        }

        assertEquals(
            "Focused migration test requires FK enforcement for orphan checks",
            1,
            foreignKeysAfter
        )
// ----------------------------------------------------
        // Existing VERSION 11 document survives unchanged.
        // ----------------------------------------------------

        migrated.query(
            """
            SELECT
                document_type,
                snapshot_json,
                display_number,
                operation_id
            FROM documents
            WHERE id = 'v12-document'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(
                "Existing v11 document must survive migration",
                cursor.moveToFirst()
            )

            assertEquals("INVOICE", cursor.getString(0))
            assertEquals(
                """{"migration":"keep-document"}""",
                cursor.getString(1)
            )
            assertEquals("V12-DOC-001", cursor.getString(2))
            assertEquals("v12-operation", cursor.getString(3))
        }

        // ----------------------------------------------------
        // Existing VERSION 11 media survives unchanged.
        // ----------------------------------------------------

        migrated.query(
            """
            SELECT
                business_id,
                branch_id,
                media_type,
                local_reference,
                cloud_reference,
                created_at
            FROM media_assets
            WHERE id = 'v12-media'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(
                "Existing v11 media must survive migration",
                cursor.moveToFirst()
            )

            assertEquals("v12-business", cursor.getString(0))
            assertEquals("v12-branch", cursor.getString(1))
            assertEquals("DOCUMENT_ATTACHMENT", cursor.getString(2))
            assertEquals("files/v12-evidence.jpg", cursor.getString(3))
            assertTrue(cursor.isNull(4))
            assertEquals(1300L, cursor.getLong(5))
        }

        // ----------------------------------------------------
        // New table exists.
        // ----------------------------------------------------

        migrated.query(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'table'
              AND name = 'document_media_links'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(
                "document_media_links table must exist",
                cursor.moveToFirst()
            )
        }

        // ----------------------------------------------------
        // Columns and composite primary key.
        // ----------------------------------------------------

        var documentIdFound = false
        var mediaIdFound = false
        var positionFound = false
        var createdAtFound = false

        var documentPkOrder = 0
        var mediaPkOrder = 0

        migrated.query(
            "PRAGMA table_info(document_media_links)"
        ).use { cursor ->

            val nameIndex =
                cursor.getColumnIndexOrThrow("name")

            val notNullIndex =
                cursor.getColumnIndexOrThrow("notnull")

            val pkIndex =
                cursor.getColumnIndexOrThrow("pk")

            while (cursor.moveToNext()) {

                when (cursor.getString(nameIndex)) {

                    "document_id" -> {
                        documentIdFound = true
                        assertEquals(
                            1,
                            cursor.getInt(notNullIndex)
                        )
                        documentPkOrder =
                            cursor.getInt(pkIndex)
                    }

                    "media_id" -> {
                        mediaIdFound = true
                        assertEquals(
                            1,
                            cursor.getInt(notNullIndex)
                        )
                        mediaPkOrder =
                            cursor.getInt(pkIndex)
                    }

                    "position" -> {
                        positionFound = true
                        assertEquals(
                            1,
                            cursor.getInt(notNullIndex)
                        )
                    }

                    "created_at" -> {
                        createdAtFound = true
                        assertEquals(
                            1,
                            cursor.getInt(notNullIndex)
                        )
                    }
                }
            }
        }

        assertTrue("document_id missing", documentIdFound)
        assertTrue("media_id missing", mediaIdFound)
        assertTrue("position missing", positionFound)
        assertTrue("created_at missing", createdAtFound)

        assertEquals(
            "document_id must be PK position 1",
            1,
            documentPkOrder
        )

        assertEquals(
            "media_id must be PK position 2",
            2,
            mediaPkOrder
        )

        // ----------------------------------------------------
        // Foreign-key metadata.
        // ----------------------------------------------------

        var documentForeignKeyFound = false
        var mediaForeignKeyFound = false

        migrated.query(
            "PRAGMA foreign_key_list(document_media_links)"
        ).use { cursor ->

            val tableIndex =
                cursor.getColumnIndexOrThrow("table")

            val fromIndex =
                cursor.getColumnIndexOrThrow("from")

            val toIndex =
                cursor.getColumnIndexOrThrow("to")

            val deleteIndex =
                cursor.getColumnIndexOrThrow("on_delete")

            while (cursor.moveToNext()) {

                val table =
                    cursor.getString(tableIndex)

                val from =
                    cursor.getString(fromIndex)

                val to =
                    cursor.getString(toIndex)

                val onDelete =
                    cursor.getString(deleteIndex)

                if (
                    table == "documents" &&
                    from == "document_id" &&
                    to == "id" &&
                    onDelete == "RESTRICT"
                ) {
                    documentForeignKeyFound = true
                }

                if (
                    table == "media_assets" &&
                    from == "media_id" &&
                    to == "id" &&
                    onDelete == "RESTRICT"
                ) {
                    mediaForeignKeyFound = true
                }
            }
        }

        assertTrue(
            "document_id must reference documents(id) RESTRICT",
            documentForeignKeyFound
        )

        assertTrue(
            "media_id must reference media_assets(id) RESTRICT",
            mediaForeignKeyFound
        )

        // ----------------------------------------------------
        // Required lookup indexes.
        // ----------------------------------------------------

        val indexes =
            mutableSetOf<String>()

        migrated.query(
            "PRAGMA index_list(document_media_links)"
        ).use { cursor ->

            val nameIndex =
                cursor.getColumnIndexOrThrow("name")

            while (cursor.moveToNext()) {
                indexes +=
                    cursor.getString(nameIndex)
            }
        }

        assertTrue(
            "Missing document_id index",
            "index_document_media_links_document_id" in indexes
        )

        assertTrue(
            "Missing media_id index",
            "index_document_media_links_media_id" in indexes
        )

        // ----------------------------------------------------
        // Valid relationship must persist.
        // ----------------------------------------------------

        migrated.execSQL(
            """
            INSERT INTO document_media_links (
                document_id,
                media_id,
                position,
                created_at
            )
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v12-document",
                "v12-media",
                0,
                1400L
            )
        )

        migrated.query(
            """
            SELECT
                document_id,
                media_id,
                position,
                created_at
            FROM document_media_links
            WHERE document_id = 'v12-document'
              AND media_id = 'v12-media'
            """.trimIndent()
        ).use { cursor ->

            assertTrue(
                "Valid document/media link must persist",
                cursor.moveToFirst()
            )

            assertEquals("v12-document", cursor.getString(0))
            assertEquals("v12-media", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(1400L, cursor.getLong(3))
        }

        // ----------------------------------------------------
        // Composite PK must reject duplicate link.
        // ----------------------------------------------------

        var duplicateFailure: Throwable? = null

        try {
            migrated.execSQL(
                """
                INSERT INTO document_media_links (
                    document_id,
                    media_id,
                    position,
                    created_at
                )
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "v12-document",
                    "v12-media",
                    1,
                    1500L
                )
            )
        } catch (throwable: Throwable) {
            duplicateFailure = throwable
        }

        assertNotNull(
            "Duplicate relationship must be rejected",
            duplicateFailure
        )

        assertTrue(
            "Duplicate must fail with SQLite constraint",
            throwableChainContainsConstraint(
                duplicateFailure
            )
        )

        // ----------------------------------------------------
        // FK enforcement: orphan document.
        // ----------------------------------------------------

        var orphanDocumentFailure: Throwable? = null

        try {
            migrated.execSQL(
                """
                INSERT INTO document_media_links (
                    document_id,
                    media_id,
                    position,
                    created_at
                )
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "missing-document",
                    "v12-media",
                    2,
                    1600L
                )
            )
        } catch (throwable: Throwable) {
            orphanDocumentFailure = throwable
        }

        assertNotNull(
            "Missing document must be rejected",
            orphanDocumentFailure
        )

        assertTrue(
            "Missing document must fail with SQLite constraint",
            throwableChainContainsConstraint(
                orphanDocumentFailure
            )
        )

        // ----------------------------------------------------
        // FK enforcement: orphan media.
        // ----------------------------------------------------

        var orphanMediaFailure: Throwable? = null

        try {
            migrated.execSQL(
                """
                INSERT INTO document_media_links (
                    document_id,
                    media_id,
                    position,
                    created_at
                )
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "v12-document",
                    "missing-media",
                    3,
                    1700L
                )
            )
        } catch (throwable: Throwable) {
            orphanMediaFailure = throwable
        }

        assertNotNull(
            "Missing media must be rejected",
            orphanMediaFailure
        )

        assertTrue(
            "Missing media must fail with SQLite constraint",
            throwableChainContainsConstraint(
                orphanMediaFailure
            )
        )

        // Failed writes must not create partial relationships.
        migrated.query(
            "SELECT COUNT(*) FROM document_media_links"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }

        migrated.close()
    }

    private fun throwableChainContainsConstraint(
        throwable: Throwable?
    ): Boolean {

        var current = throwable

        while (current != null) {

            if (
                current is SQLiteConstraintException ||
                current.javaClass.simpleName.contains(
                    "Constraint",
                    ignoreCase = true
                ) ||
                current.message?.contains(
                    "constraint",
                    ignoreCase = true
                ) == true
            ) {
                return true
            }

            current = current.cause
        }

        return false
    }
}