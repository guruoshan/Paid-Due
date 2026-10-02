package com.dailymoneyregister.feature.customers.data

/**
 * Pure deterministic FIFO allocator.
 *
 * It does not read or write the database.
 * The repository supplies authoritative outstanding obligations and persists
 * the returned allocation plan atomically with repayment evidence.
 */
object CreditRepaymentFifoAllocator {

    data class OutstandingObligation(
        val transactionId: String,
        val occurredAt: Long,
        val remainingMinor: Long
    ) {
        init {
            require(transactionId.isNotBlank())
            require(remainingMinor > 0L)
        }
    }

    data class Allocation(
        val transactionId: String,
        val amountMinor: Long
    ) {
        init {
            require(transactionId.isNotBlank())
            require(amountMinor > 0L)
        }
    }

    fun allocate(
        repaymentAmountMinor: Long,
        obligations: List<OutstandingObligation>
    ): List<Allocation> {

        require(repaymentAmountMinor > 0L) {
            "Repayment amount must be positive."
        }

        val totalOutstanding =
            obligations.fold(0L) { total, obligation ->
                Math.addExact(
                    total,
                    obligation.remainingMinor
                )
            }

        require(repaymentAmountMinor <= totalOutstanding) {
            "Repayment cannot exceed outstanding Credit."
        }

        /*
         * FIFO is determined by authoritative obligation time.
         * transactionId provides deterministic ordering if two obligations
         * have the exact same persisted timestamp.
         */
        val ordered =
            obligations.sortedWith(
                compareBy<OutstandingObligation>(
                    { it.occurredAt },
                    { it.transactionId }
                )
            )

        var remaining = repaymentAmountMinor

        val allocations =
            mutableListOf<Allocation>()

        for (obligation in ordered) {

            if (remaining == 0L) {
                break
            }

            val amount =
                minOf(
                    remaining,
                    obligation.remainingMinor
                )

            allocations +=
                Allocation(
                    transactionId =
                        obligation.transactionId,
                    amountMinor = amount
                )

            remaining =
                Math.subtractExact(
                    remaining,
                    amount
                )
        }

        check(remaining == 0L) {
            "FIFO allocation did not consume the complete repayment."
        }

        check(
            allocations.fold(0L) { total, allocation ->
                Math.addExact(
                    total,
                    allocation.amountMinor
                )
            } == repaymentAmountMinor
        ) {
            "FIFO allocation total does not equal repayment."
        }

        return allocations
    }
}
