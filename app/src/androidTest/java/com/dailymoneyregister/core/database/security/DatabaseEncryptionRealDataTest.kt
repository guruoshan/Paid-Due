package com.dailymoneyregister.core.database.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.command.CommandExecutor
import com.dailymoneyregister.core.command.CommandResult
import com.dailymoneyregister.core.database.DmrDatabase
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import com.dailymoneyregister.core.i18n.LanguageManager
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.ids.OperationId
import com.dailymoneyregister.core.model.ids.TransactionId
import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.model.tax.TaxRoundingMode
import com.dailymoneyregister.core.security.PinThrottler
import com.dailymoneyregister.core.security.TrustedSessionStore
import com.dailymoneyregister.feature.admin.data.CashierManagementRepository
import com.dailymoneyregister.feature.admin.data.CashierPermissionsConfig
import com.dailymoneyregister.feature.admin.data.CreateCashierParams
import com.dailymoneyregister.feature.auth.data.AuthRepository
import com.dailymoneyregister.feature.auth.data.AuthResult
import com.dailymoneyregister.feature.register.data.CashSaleCommand
import com.dailymoneyregister.feature.register.data.RegisterRepository
import com.dailymoneyregister.feature.register.domain.DraftState
import com.dailymoneyregister.feature.setup.data.SetupParams
import com.dailymoneyregister.feature.setup.data.SetupRepository
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionRealDataTest {

    @Test
    fun realisticDmrDataSurvivesEncryptionAndRoomReopen() = runBlocking {
        val context =
            ApplicationProvider.getApplicationContext<Context>()

        val databaseName =
            "session7_real_data_survival.db"

        val passphrase =
            ByteArray(32) { index ->
                (index + 71).toByte()
            }

        cleanupDatabaseArtifacts(
            context,
            databaseName
        )

        var plaintextDatabase: DmrDatabase? = null
        var encryptedDatabase: DmrDatabase? = null
        var roomPassphrase: ByteArray? = null
        var completedSuccessfully = false

        try {
            /*
             * STEP 1:
             * Create a normal plaintext Room database using the
             * same schema, migrations and immutability callback
             * as Daily Money Register.
             */
            val db =
                Room.databaseBuilder(
                    context,
                    DmrDatabase::class.java,
                    databaseName
                )
                    .addMigrations(
                        DmrDatabase.MIGRATION_1_2
                    )
                    .addCallback(
                        FinancialImmutabilityCallback
                    )
                    .allowMainThreadQueries()
                    .build()

            plaintextDatabase = db

            val commandExecutor =
                CommandExecutor(db)

            val setupRepository =
                SetupRepository(
                    db,
                    commandExecutor,
                    DeviceIdentityProvider(context),
                    TrustedSessionStore(context)
                )

            val cashierRepository =
                CashierManagementRepository(
                    db,
                    commandExecutor,
                    DeviceIdentityProvider(context)
                )

            val authRepository =
                AuthRepository(
                    db,
                    PinThrottler(context = context),
                    LanguageManager(context),
                    TrustedSessionStore(context)
                )

            val registerRepository =
                RegisterRepository(
                    db,
                    commandExecutor
                )

            /*
             * STEP 2:
             * Real DMR first-run setup:
             * Business + Branch + Admin + Admin membership.
             */
            val setupOperationId =
                OperationId.generate()

            val setupResult =
                setupRepository.executeSetup(
                    SetupParams(
                        businessName =
                            "Session 7 Survival Shop",
                        branchName =
                            "Session 7 Main Branch",
                        branchCode =
                            "S7A",
                        countryCode =
                            "OM",
                        currencyCode =
                            "OMR",
                        currencyDecimalPlaces =
                            3,
                        currencySymbol =
                            "OMR",
                        timezoneId =
                            "Asia/Muscat",
                        taxRoundingMode =
                            TaxRoundingMode.HALF_UP,
                        defaultTaxRateBps =
                            0,
                        adminDisplayName =
                            "Session 7 Admin",
                        adminUsername =
                            "session7_admin",
                        adminPasswordPlain =
                            "Session7Pass123!",
                        languageCode =
                            "en"
                    ),
                    setupOperationId
                )

            assertTrue(
                "Setup must succeed",
                setupResult is CommandResult.Success
            )

            val setupData =
                (setupResult as CommandResult.Success).data

            /*
             * STEP 3:
             * Admin creates a real cashier with permission
             * to record cash sales.
             */
            val cashierOperationId =
                OperationId.generate()

            val cashierResult =
                cashierRepository.createCashier(
                    CreateCashierParams(
                        businessId =
                            setupData.businessId.value,
                        branchId =
                            setupData.branchId.value,
                        adminUserId =
                            setupData.adminUserId.value,
                        displayName =
                            "Session 7 Cashier",
                        username =
                            "session7_cashier",
                        pin =
                            "246810",
                        permissions =
                            CashierPermissionsConfig(
                                canCreateSale = true,
                                canAcceptCash = true
                            )
                    ),
                    cashierOperationId
                )

            assertTrue(
                "Cashier creation must succeed",
                cashierResult is CommandResult.Success
            )

            val cashierId =
                (cashierResult as CommandResult.Success)
                    .data
                    .value

            /*
             * STEP 4:
             * Prove credentials work BEFORE encryption.
             */
            assertTrue(
                "Admin login must work before encryption",
                authRepository.loginAdmin(
                    "session7_admin",
                    "Session7Pass123!"
                ) is AuthResult.Success
            )

            val temporaryLogin = authRepository.loginCashier(
                "session7_cashier",
                "246810"
            )
            assertTrue("Cashier temporary PIN login must work before encryption", temporaryLogin is AuthResult.Success)
            assertTrue("Temporary PIN login must require PIN change", (temporaryLogin as AuthResult.Success).session.requiresPinChange)
            assertTrue("Cashier PIN change must succeed", authRepository.changeCashierPin("135790") is AuthResult.Success)
            assertTrue("PIN change must clear mustChangePin", db.userDao().getUserById(cashierId)?.mustChangePin == false)
            assertTrue("Temporary PIN must be rejected after change", authRepository.loginCashier("session7_cashier", "246810") is AuthResult.InvalidCredentials)
            assertTrue("New cashier PIN must authenticate", authRepository.loginCashier("session7_cashier", "135790") is AuthResult.Success)

            val cashier =
                db.userDao()
                    .getUserById(cashierId)

            assertNotNull(cashier)

            val cashierMembership =
                db.userDao()
                    .getMembership(
                        cashierId,
                        setupData.branchId.value
                    )

            assertNotNull(cashierMembership)

            /*
             * STEP 5:
             * Record a REAL cash sale through RegisterRepository.
             */
            val saleOperationId =
                OperationId.generate()

            val transactionId =
                TransactionId.generate()

            val saleResult =
                registerRepository.executeCashSale(
                    CashSaleCommand(
                        operationId =
                            saleOperationId,
                        businessId =
                            setupData.businessId.value,
                        branchId =
                            setupData.branchId.value,
                        actorUserId =
                            cashierId,
                        transactionIdVal =
                            transactionId,
                        draftState =
                            DraftState(
                                manualAmount =
                                    MoneyMinorUnits(4250L),
                                taxRateBps =
                                    0,
                                isTaxEnabled =
                                    false
                            ),
                        currencyCode =
                            "OMR",
                        currencyDecimalPlaces =
                            3,
                        deviceId =
                            "session7-survival-device"
                    ),
                    cashier!!,
                    cashierMembership
                )

            assertTrue(
                "Real cash sale must succeed before encryption",
                saleResult is CommandResult.Success
            )

            /*
             * STEP 6:
             * Confirm the authoritative financial records exist.
             */
            val originalTransaction =
                db.transactionDao()
                    .getTransactionById(
                        transactionId.value
                    )

            assertNotNull(originalTransaction)
            assertEquals(
                4250L,
                originalTransaction!!.grandTotalMinor
            )

            val originalSettlements =
                db.transactionDao()
                    .getSettlementsForTransaction(
                        transactionId.value
                    )

            assertEquals(
                1,
                originalSettlements.size
            )

            assertEquals(
                EntryPurpose.SALE_FUNDING,
                originalSettlements.first().entryPurpose
            )

            assertEquals(
                PaymentCategory.CASH,
                originalSettlements.first().category
            )

            assertEquals(
                4250L,
                originalSettlements.first().amountSignedMinor
            )

            assertEquals(
                OperationStatus.SUCCEEDED,
                db.operationDao()
                    .getOperationById(
                        saleOperationId.value
                    )
                    ?.status
            )

            /*
             * STEP 7:
             * Room must be completely closed before migration.
             */
            db.close()
            plaintextDatabase = null

            /*
             * STEP 8:
             * Encrypt the existing populated DMR database.
             */
            System.loadLibrary("sqlcipher")

            val migrationResult =
                DatabaseEncryptionMigrator(
                    context,
                    databaseName
                ).prepareDatabase(
                    passphrase
                )

            assertEquals(
                DatabaseEncryptionPreparationResult.MIGRATED,
                migrationResult
            )

            /*
             * STEP 9:
             * Reopen the migrated database through Room using
             * SQLCipher — this is the important compatibility proof.
             */
            roomPassphrase =
                passphrase.copyOf()

            val reopened =
                Room.databaseBuilder(
                    context,
                    DmrDatabase::class.java,
                    databaseName
                )
                    .openHelperFactory(
                        SupportOpenHelperFactory(
                            roomPassphrase
                        )
                    )
                    .addMigrations(
                        DmrDatabase.MIGRATION_1_2
                    )
                    .addCallback(
                        FinancialImmutabilityCallback
                    )
                    .allowMainThreadQueries()
                    .build()

            encryptedDatabase = reopened

            /*
             * STEP 10:
             * Verify Business + Branch survived.
             */
            val business =
                reopened.businessBranchDao()
                    .getBusinessById(
                        setupData.businessId.value
                    )

            assertNotNull(business)
            assertEquals(
                "Session 7 Survival Shop",
                business!!.name
            )

            val branch =
                reopened.businessBranchDao()
                    .getBranchById(
                        setupData.branchId.value
                    )

            assertNotNull(branch)
            assertEquals(
                "OMR",
                branch!!.currencyCode
            )

            assertEquals(
                3,
                branch.currencyDecimalPlaces
            )

            /*
             * STEP 11:
             * Verify Admin + Cashier + Membership survived.
             */
            val admin =
                reopened.userDao()
                    .getUserById(
                        setupData.adminUserId.value
                    )

            assertNotNull(admin)

            val reopenedCashier =
                reopened.userDao()
                    .getUserById(
                        cashierId
                    )

            assertNotNull(reopenedCashier)

            val reopenedMembership =
                reopened.userDao()
                    .getMembership(
                        cashierId,
                        setupData.branchId.value
                    )

            assertNotNull(reopenedMembership)
            assertTrue(
                reopenedMembership!!.canCreateSale
            )
            assertTrue(
                reopenedMembership.canAcceptCash
            )

            /*
             * STEP 12:
             * Verify credentials still work AFTER encryption.
             */
            val reopenedAuthRepository =
                AuthRepository(
                    reopened,
                    PinThrottler(context = context),
                    LanguageManager(context),
                    TrustedSessionStore(context)
                )

            assertTrue(
                "Admin login must survive encryption",
                reopenedAuthRepository.loginAdmin(
                    "session7_admin",
                    "Session7Pass123!"
                ) is AuthResult.Success
            )

            assertNotNull(reopenedCashier)
            assertTrue("Cashier must not require PIN change after reopen", reopenedCashier!!.mustChangePin == false)
            assertTrue(
                "Temporary cashier PIN must remain rejected after encryption",
                reopenedAuthRepository.loginCashier("session7_cashier", "246810") is AuthResult.InvalidCredentials
            )
            assertTrue(
                "New cashier PIN must survive encryption",
                reopenedAuthRepository.loginCashier("session7_cashier", "135790") is AuthResult.Success
            )

            /*
             * STEP 13:
             * Verify the financial transaction survived exactly.
             */
            val migratedTransaction =
                reopened.transactionDao()
                    .getTransactionById(
                        transactionId.value
                    )

            assertNotNull(migratedTransaction)

            assertEquals(
                4250L,
                migratedTransaction!!.grandTotalMinor
            )

            assertEquals(
                "OMR",
                migratedTransaction.currencyCode
            )

            val migratedSettlements =
                reopened.transactionDao()
                    .getSettlementsForTransaction(
                        transactionId.value
                    )

            assertEquals(
                1,
                migratedSettlements.size
            )

            assertEquals(
                4250L,
                migratedSettlements.first().amountSignedMinor
            )

            assertEquals(
                PaymentCategory.CASH,
                migratedSettlements.first().category
            )

            /*
             * STEP 14:
             * Setup, cashier creation and sale operation audit
             * records must all survive.
             */
            assertEquals(
                OperationStatus.SUCCEEDED,
                reopened.operationDao()
                    .getOperationById(
                        setupOperationId.value
                    )
                    ?.status
            )

            assertEquals(
                OperationStatus.SUCCEEDED,
                reopened.operationDao()
                    .getOperationById(
                        cashierOperationId.value
                    )
                    ?.status
            )

            assertEquals(
                OperationStatus.SUCCEEDED,
                reopened.operationDao()
                    .getOperationById(
                        saleOperationId.value
                    )
                    ?.status
            )

            completedSuccessfully = true
        } finally {
            plaintextDatabase?.close()
            encryptedDatabase?.close()

            roomPassphrase?.fill(0)
            passphrase.fill(0)

            /*
             * Keep failed test database artifacts for diagnostics.
             * Delete them only after a fully successful test.
             */
            if (completedSuccessfully) {
                cleanupDatabaseArtifacts(
                    context,
                    databaseName
                )
            }
        }
    }

    private fun cleanupDatabaseArtifacts(
        context: Context,
        databaseName: String
    ) {
        context.deleteDatabase(databaseName)

        val databaseFile =
            context.getDatabasePath(databaseName)

        databaseFile.parentFile
            ?.listFiles()
            ?.filter {
                it.name.startsWith(databaseFile.name)
            }
            ?.forEach {
                it.delete()
            }
    }
}
