package com.dailymoneyregister

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dailymoneyregister.core.database.DmrDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboundEnvelopeAuditMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DmrDatabase::class.java
    )

    @Test
    fun migration14To16AddsIdentityAndAuditTablesWithoutTouchingBusinessHistory() {
        val name = "dmr-staff-identity-audit-v14-v16"
        val old = migrationHelper.createDatabase(name, 14)
        old.execSQL(
            "INSERT INTO businesses (id, name, created_at, updated_at) VALUES ('existing-business', 'Existing Shop', 10, 10)"
        )
        old.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            16,
            true,
            DmrDatabase.MIGRATION_14_15,
            DmrDatabase.MIGRATION_15_16
        )
        migrated.query("SELECT name FROM businesses WHERE id = 'existing-business'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Existing Shop", it.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM cloud_staff_identities").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM inbound_envelope_audit_events").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        migrated.close()
    }
}
