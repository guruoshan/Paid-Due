package com.dailymoneyregister.feature.register.data

import com.dailymoneyregister.core.model.ids.CashShiftId

data class RegisterShiftResult(
    val shiftId: CashShiftId,
    val branchId: String,
    val actorUserId: String,
    val openedAt: Long,
    val isOpen: Boolean
)
