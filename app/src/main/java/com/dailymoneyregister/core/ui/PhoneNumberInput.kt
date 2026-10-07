package com.dailymoneyregister.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.identity.InvalidPhoneNumberException
import com.dailymoneyregister.core.identity.PhoneNumberNormalizer
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** Hoist and retain this draft in the caller; initialize with the merchant registration country. */
data class PhoneNumberInputValue(val countryCode: String, val rawNumber: String = "") {
    init {
        require(countryCode in PhoneNumberUtil.getInstance().supportedRegions) {
            "A supported uppercase merchant/cashier country code is required"
        }
    }

    /** The existing normalizer is the sole validation and canonicalization authority. */
    fun normalizedNumber(): String = PhoneNumberNormalizer.normalize(rawNumber, countryCode)

    fun internationalNumber(): String {
        val util = PhoneNumberUtil.getInstance()
        return util.format(util.parse(normalizedNumber(), countryCode), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
    }
}

/** Shared, offline phone input. The caller supplies the initial merchant country and owns the draft.
 * Valid input shows an international preview without rewriting the cursor or partially typed text.
 * Call normalizedNumber() on submission; invalid/empty input throws InvalidPhoneNumberException.
 */
@Composable
fun PhoneNumberInput(
    value: PhoneNumberInputValue,
    onValueChange: (PhoneNumberInputValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showValidation: Boolean = false,
    allowCountrySelection: Boolean = true,
) {
    var selectingCountry by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val locale = LocalConfiguration.current.locales[0]
    val util = remember { PhoneNumberUtil.getInstance() }
    val countries = remember(locale) {
        util.supportedRegions.map { code ->
            code to "${Locale.Builder().setRegion(code).build().getDisplayCountry(locale)} ($code) +${util.getCountryCodeForRegion(code)}"
        }.sortedBy { it.second }
    }
    val international = remember(value) {
        try { value.internationalNumber() } catch (_: InvalidPhoneNumberException) { null }
    }
    val invalid = international == null && (showValidation || value.rawNumber.isNotBlank())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { query = ""; selectingCountry = true },
            enabled = enabled && allowCountrySelection
        ) {
            Text(stringResource(R.string.global_phone_country) + ": " + countries.first { it.first == value.countryCode }.second)
        }
        OutlinedTextField(
            value = value.rawNumber,
            onValueChange = { onValueChange(value.copy(rawNumber = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.global_phone_number)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            isError = invalid,
            supportingText = {
                if (invalid) Text(stringResource(R.string.global_phone_invalid))
                else if (international != null) Text(international,
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
            },
        )
    }
    if (selectingCountry && enabled && allowCountrySelection) {
        AlertDialog(
            onDismissRequest = { selectingCountry = false },
            title = { Text(stringResource(R.string.global_phone_country)) },
            text = {
                Column {
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.global_phone_search)) })
                    val filtered = countries.filter { it.second.contains(query.trim(), ignoreCase = true) }
                    if (filtered.isEmpty()) Text(stringResource(R.string.global_phone_no_results))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(filtered, key = { it.first }) { (code, label) ->
                            TextButton(onClick = {
                                onValueChange(value.copy(countryCode = code))
                                selectingCountry = false
                            }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectingCountry = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
