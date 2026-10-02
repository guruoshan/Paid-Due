package com.dailymoneyregister.feature.customers.ui

import com.dailymoneyregister.core.model.money.MoneyMinorUnits
import com.dailymoneyregister.core.security.SessionManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun branchZone(): ZoneId =
    runCatching { ZoneId.of(SessionManager.activeBranch?.timezoneId.orEmpty()) }
        .getOrDefault(ZoneId.systemDefault())

internal fun formatCustomerDate(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(branchZone())
        .format(Instant.ofEpochMilli(epochMillis))

internal fun formatCustomerDateTime(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(branchZone())
        .format(Instant.ofEpochMilli(epochMillis))

internal fun formatCustomerMoney(minor: Long, decimalPlaces: Int, currencyCode: String): String =
    MoneyMinorUnits(minor).formatDisplay(decimalPlaces, currencyCode)
