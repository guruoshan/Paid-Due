package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.database.DmrDatabase

/**
 * BOARDLOCK authoritative Credit Invoice read boundary.
 *
 * This component is read-only.
 *
 * It derives post-save communication evidence from:
 * - the persisted Credit transaction;
 * - the persisted Credit OBLIGATION;
 * - persisted FIFO repayment allocations for that transaction;
 * - the authoritative signed Credit ledger balance.
 *
 * It does not persist a second balance and performs no financial write.
 *
 * RegisterRepository.CreditInvoiceDetails remains the compatibility
 * model so existing ViewModel/UI callers do not change.
 */
internal class CreditInvoiceReadRepository(
    database: DmrDatabase
) {
    private val transactionDao =
        database.transactionDao()

    private val ledgerDao =
        database.ledgerDao()

    suspend fun getCreditInvoiceDetails(
        command: CreditSaleCommand
    ): RegisterRepository.CreditInvoiceDetails {

        val transaction =
            checkNotNull(
                transactionDao.getTransactionById(
                    command.transactionIdVal.value
                )
            ) {
                "Credit invoice transaction evidence is missing."
            }

        val customerId =
            checkNotNull(
                transaction.customerId
            ) {
                "Credit invoice customer evidence is missing."
            }

        val obligation =
            checkNotNull(
                ledgerDao
                    .getCreditLedgerForCustomer(
                        customerId
                    )
                    .singleOrNull { event ->
                        event.transactionId ==
                            transaction.id &&
                            event.currencyCode ==
                                command.currencyCode &&
                            event.type ==
                                com.dailymoneyregister.core.model.enums
                                    .CreditLedgerType.OBLIGATION
                    }
            ) {
                "Credit invoice obligation evidence is missing or ambiguous."
            }

        check(
            obligation.amountSignedMinor > 0L
        ) {
            "Credit invoice obligation must be positive."
        }

        val allocations =
            ledgerDao
                .getCreditRepaymentAllocationsForTransaction(
                    transaction.id
                )

        val allocatedMinor =
            allocations.fold(
                0L
            ) { total, allocation ->

                Math.addExact(
                    total,
                    allocation.allocatedAmountMinor
                )
            }

        val purchaseOutstandingMinor =
            Math.subtractExact(
                obligation.amountSignedMinor,
                allocatedMinor
            )

        check(
            purchaseOutstandingMinor >= 0L
        ) {
            "Credit invoice purchase outstanding cannot be negative."
        }

        val totalOutstandingMinor =
            ledgerDao.getCreditBalance(
                customerId,
                command.currencyCode
            )

        check(
            totalOutstandingMinor >= 0L
        ) {
            "Credit invoice total outstanding cannot be negative."
        }

        return RegisterRepository.CreditInvoiceDetails(
            customerId = customerId,
            customerName = command.customerName,
            customerPhone = command.customerPhone,
            transactionId = transaction.id,
            purchaseOccurredAt =
                obligation.occurredAt,
            creditGivenMinor =
                obligation.amountSignedMinor,
            purchaseOutstandingMinor =
                purchaseOutstandingMinor,
            totalOutstandingMinor =
                totalOutstandingMinor,
            currencyCode =
                command.currencyCode,
            currencyDecimalPlaces =
                command.currencyDecimalPlaces
        )
    }
}
