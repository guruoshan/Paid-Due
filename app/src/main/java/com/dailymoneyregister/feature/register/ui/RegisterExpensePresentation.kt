package com.dailymoneyregister.feature.register.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

import com.dailymoneyregister.core.ui.asString
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymoneyregister.R
import com.dailymoneyregister.core.ui.PhoneNumberInput
import com.dailymoneyregister.core.ui.PhoneNumberInputValue
import com.dailymoneyregister.feature.register.ui.components.*
import com.dailymoneyregister.feature.register.data.ExpenseReadProjection
import com.dailymoneyregister.feature.customers.ui.PreparedWhatsAppMessage
import com.dailymoneyregister.feature.customers.ui.UniversalWhatsAppMessageComposer
import com.dailymoneyregister.feature.customers.ui.WhatsAppTransport
import com.dailymoneyregister.feature.register.receipt.ReceiptSharing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun expenseDetailsDateTime(
    occurredAt: Long,
    timezoneId: String
): String =
    DateTimeFormatter
        .ofPattern(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        )
        .withZone(
            ZoneId.of(timezoneId)
        )
        .format(
            Instant.ofEpochMilli(occurredAt)
        )

private fun expenseDetailsLabel(
    value: String
): String =
    value
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar {
            if (it.isLowerCase()) {
                it.titlecase(Locale.getDefault())
            } else {
                it.toString()
            }
        }


@Composable
internal fun ExpenseDetailsScreen(
    details: ExpenseReadProjection,
    decimalPlaces: Int,
    currencySymbol: String,
    timezoneId: String,
    businessName: String,
    supportingDocuments: List<com.dailymoneyregister.feature.register.data.ExpenseSupportingDocument>,
    supportingDocumentsLoading: Boolean,
    supportingDocumentAttaching: Boolean,
    onRefreshSupportingDocuments: () -> Unit,
    onAttachSupportingDocument: (String) -> Unit,
    canManageFinancialLifecycle: Boolean = false,
    onCorrectExpense: (String, String) -> Unit = { _, _ -> },
    onVoidExpense: (String) -> Unit = {},
    onBack: () -> Unit
) {

    val context =
        LocalContext.current

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showExpenseCorrection by rememberSaveable { mutableStateOf(false) }
    var showExpenseVoid by rememberSaveable { mutableStateOf(false) }
    var correctedAmountInput by rememberSaveable(details.expenseId) {
        mutableStateOf(details.amount.formatDisplay(decimalPlaces, "").replace(",", "").trim())
    }
    var lifecycleReason by rememberSaveable(details.expenseId) { mutableStateOf("") }

    val chooseDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                onAttachSupportingDocument(uri.toString())
            }
        }

    val takePhotoLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.TakePicture()
        ) { success ->
            val uri = pendingCameraUri
            if (success && uri != null) {
                onAttachSupportingDocument(uri.toString())
            }
            pendingCameraUri = null
        }

    androidx.compose.runtime.LaunchedEffect(details.expenseId) {
        onRefreshSupportingDocuments()
    }

    BackHandler(
        enabled = true,
        onBack = onBack
    )

    val amount =
        details.signedEffect
            .formatDisplay(
                decimalPlaces,
                currencySymbol
            )

    val recipientPhone =
        details.payeePhone
            ?.takeIf {
                it.isNotBlank()
            }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(16.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        ExpenseDetailsHeader(onBack = onBack)

        ExpenseDetailsSummaryCard(
            amount = amount,
            category = expenseDetailsLabel(details.category),
            paymentMethod = expenseDetailsLabel(details.paymentCategory.name),
            paymentChannel = expenseDetailsLabel(details.paymentChannel.name),
            payeeName = details.payeeName?.takeIf { it.isNotBlank() } ?: "-",
            payeePhone = details.payeePhone?.takeIf { it.isNotBlank() } ?: "-",
            reference = details.displayReference,
            note = details.note?.takeIf { it.isNotBlank() } ?: "-",
            dateTime = expenseDetailsDateTime(details.occurredAt, timezoneId)
        )

        if (canManageFinancialLifecycle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        correctedAmountInput = (details.amount.amount.toBigDecimal()
                            .divide(java.math.BigDecimal.TEN.pow(decimalPlaces))
                            .stripTrailingZeros().toPlainString())
                        lifecycleReason = ""
                        showExpenseCorrection = true
                    }
                ) { Text(stringResource(R.string.audit_correct_expense)) }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        lifecycleReason = ""
                        showExpenseVoid = true
                    }
                ) { Text(stringResource(R.string.audit_void_expense)) }
            }
        }

        if (showExpenseCorrection) {
            AlertDialog(
                onDismissRequest = { showExpenseCorrection = false },
                title = { Text(stringResource(R.string.audit_correct_expense)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = correctedAmountInput,
                            onValueChange = { correctedAmountInput = it },
                            label = { Text(stringResource(R.string.audit_corrected_amount)) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = lifecycleReason,
                            onValueChange = { lifecycleReason = it },
                            label = { Text(stringResource(R.string.audit_reason)) }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = lifecycleReason.isNotBlank() && correctedAmountInput.isNotBlank(),
                        onClick = {
                            onCorrectExpense(correctedAmountInput, lifecycleReason)
                            showExpenseCorrection = false
                        }
                    ) { Text(stringResource(R.string.audit_save_correction)) }
                },
                dismissButton = {
                    TextButton(onClick = { showExpenseCorrection = false }) { Text(stringResource(R.string.audit_cancel)) }
                }
            )
        }

        if (showExpenseVoid) {
            AlertDialog(
                onDismissRequest = { showExpenseVoid = false },
                title = { Text(stringResource(R.string.audit_void_expense)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.audit_void_expense_explanation))
                        OutlinedTextField(
                            value = lifecycleReason,
                            onValueChange = { lifecycleReason = it },
                            label = { Text(stringResource(R.string.audit_reason)) }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = lifecycleReason.isNotBlank(),
                        onClick = {
                            onVoidExpense(lifecycleReason)
                            showExpenseVoid = false
                        }
                    ) { Text(stringResource(R.string.audit_confirm_void)) }
                },
                dismissButton = {
                    TextButton(onClick = { showExpenseVoid = false }) { Text(stringResource(R.string.audit_cancel)) }
                }
            )
        }

        Button(
            onClick = {

                val phone =
                    recipientPhone
                        ?: return@Button

                // BOARDLOCK_UNIVERSAL_RECEIPT_IMAGE_V4
                // Payment voucher image + the same text as before.
                val expenseMessage =
                    UniversalWhatsAppMessageComposer.Expense(
                        party = UniversalWhatsAppMessageComposer.Party(
                            businessName = businessName,
                            customerName = details.payeeName,
                            customerPhone = details.payeePhone,
                            labels = UniversalWhatsAppMessageComposer.MessageLabels.from(context)
                        ),
                        formattedAmount =
                            com.dailymoneyregister.core.model.money.MoneyMinorUnits(
                                details.amount.amount
                            ).formatDisplay(decimalPlaces, currencySymbol),
                        category = expenseDetailsLabel(details.category),
                        paymentMethod = expenseDetailsLabel(
                            details.paymentCategory.name
                        ),
                        paymentChannel = expenseDetailsLabel(
                            details.paymentChannel.name
                        ),
                        reference = details.displayReference,
                        eventDateTime = expenseDetailsDateTime(
                            details.occurredAt,
                            timezoneId
                        ),
                        note = details.note
                    )

                runCatching {
                    ReceiptSharing.shareExpenseVoucherWhatsApp(
                        context = context,
                        event = expenseMessage,
                        recipientPhone = phone
                    )
                }.onFailure {
                    WhatsAppTransport.share(
                        context = context,
                        prepared = PreparedWhatsAppMessage(
                            text = UniversalWhatsAppMessageComposer.compose(expenseMessage),
                            recipientPhone = phone
                        )
                    )
                }
            },
            enabled =
                recipientPhone != null,
            modifier =
                Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        Color(0xFF25D366),
                    contentColor =
                        Color.White
                )
        ) {
            Text(stringResource(R.string.audit_share_whatsapp))
        }

        if (recipientPhone == null) {
            Text(
                text =
                    stringResource(R.string.audit_no_phone_for_expense),
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
        HorizontalDivider()

        Text(
            text = stringResource(R.string.audit_supporting_documents),
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !supportingDocumentAttaching,
                onClick = {
                    val directory = File(context.cacheDir, "expense_supporting_document_capture")
                    if (!directory.exists()) directory.mkdirs()
                    if (directory.exists()) {
                        val captureFile = File.createTempFile("expense-", ".jpg", directory)
                        val captureUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", captureFile)
                        pendingCameraUri = captureUri
                        takePhotoLauncher.launch(captureUri)
                    }
                }
            ) {
                Text(stringResource(R.string.audit_take_photo))
            }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !supportingDocumentAttaching,
                onClick = {
                    chooseDocumentLauncher.launch(arrayOf("image/*", "application/pdf"))
                }
            ) {
                Text(stringResource(R.string.audit_choose_file))
            }
        }

        if (supportingDocumentAttaching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (supportingDocumentsLoading) {
            Text(stringResource(R.string.audit_loading_documents))
        } else if (supportingDocuments.isEmpty()) {
            Text(
                text = stringResource(R.string.audit_no_supporting_documents),
                style = MaterialTheme.typography.bodySmall
            )
        }

        supportingDocuments.flatMap { it.media }.forEachIndexed { index, media ->
            val evidenceFile = File(media.localReference)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.audit_supporting_document_number, index + 1),
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = evidenceFile.name,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", evidenceFile)
                                val mime = expenseEvidenceMimeType(evidenceFile)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        ) {
                            Text(stringResource(R.string.audit_open))
                        }

                        TextButton(
                            onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", evidenceFile)
                                val mime = expenseEvidenceMimeType(evidenceFile)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, "Share supporting document")
                                )
                            }
                        ) {
                            Text(stringResource(R.string.audit_share))
                        }
                    }
                }
            }
        }
    }
}

private fun expenseEvidenceMimeType(file: File): String =
    when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

internal data class ExpenseDialogActions(
    val onStageSupportingDocument: (String) -> Unit,
    val onLookupPayeeByPhone: (String?) -> Unit,
    val onDismiss: () -> Unit,
    val onRemoveSupportingDocument: (String) -> Unit,
    val onOpenExpenseDetails: (String) -> Unit,
    val onSave: (
        String,
        com.dailymoneyregister.feature.register.data.ExpenseCategory,
        com.dailymoneyregister.core.model.enums.PaymentCategory,
        String,
        String?,
        String,
        String
    ) -> Unit
)

@Composable
internal fun ExpenseDialog(
    state: RegisterUiState,
    decimalPlaces: Int,
    currencyCode: String,
    currencySymbol: String,
    merchantCountryCode: String,
    actions: ExpenseDialogActions
) {
    val context = LocalContext.current
    var pendingExpenseCameraUri by remember { mutableStateOf<Uri?>(null) }
    val expenseChooseDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) actions.onStageSupportingDocument(uri.toString()) }
    val expenseTakePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> val uri = pendingExpenseCameraUri; if (success && uri != null) actions.onStageSupportingDocument(uri.toString()); pendingExpenseCameraUri = null }

    var amount by rememberSaveable {
        mutableStateOf("")
    }

    var categoryName by rememberSaveable {
        mutableStateOf(
            com.dailymoneyregister.feature.register.data
                .ExpenseCategory.OTHER.name
        )
    }

    var paymentName by rememberSaveable {
        mutableStateOf(
            com.dailymoneyregister.core.model.enums
                .PaymentCategory.CASH.name
        )
    }

    var note by rememberSaveable {
        mutableStateOf("")
    }

    var reference by rememberSaveable {
        mutableStateOf("")
    }

    var payeeName by rememberSaveable {
        mutableStateOf("")
    }

    var payeePhoneCountryCode by rememberSaveable {
        mutableStateOf(merchantCountryCode)
    }

    var payeePhoneRawNumber by rememberSaveable {
        mutableStateOf("")
    }

    val payeePhone =
        PhoneNumberInputValue(
            countryCode = payeePhoneCountryCode,
            rawNumber = payeePhoneRawNumber
        )

    var categoryExpanded by remember {
        mutableStateOf(false)
    }

    val retryPending =
        state.inFlightExpenseCommand != null

    val locked =
        state.isExecuting

    val category =
        com.dailymoneyregister.feature.register.data
            .ExpenseCategory.valueOf(categoryName)

    val payment =
        com.dailymoneyregister.core.model.enums
            .PaymentCategory.valueOf(paymentName)

    val amountValid =
        remember(amount, decimalPlaces) {
            val entered =
                amount.trim().toBigDecimalOrNull()

            if (entered == null || entered.signum() <= 0) {
                false
            } else {
                try {
                    entered
                        .multiply(
                            java.math.BigDecimal.TEN.pow(
                                decimalPlaces
                            )
                        )
                        .setScale(
                            0,
                            java.math.RoundingMode.UNNECESSARY
                        )
                        .longValueExact() > 0L
                } catch (_: ArithmeticException) {
                    false
                }
            }
        }

    val payeePhoneNormalized =
        if (payeePhone.rawNumber.isBlank()) {
            null
        } else {
            try {
                payeePhone.normalizedNumber()
            } catch (
                _: com.dailymoneyregister.core.identity.InvalidPhoneNumberException
            ) {
                null
            }
        }

    val payeePhoneValid =
        payeePhone.rawNumber.isBlank() ||
            payeePhoneNormalized != null

    val expenseExistingCustomer =
        if (
            payeePhoneNormalized != null &&
            state.expensePayeeLookupPhone == payeePhoneNormalized
        ) {
            state.expensePayeeLookupResult
        } else {
            null
        }

    val expenseLookupComplete =
        payeePhoneNormalized != null &&
            state.expensePayeeLookupPhone == payeePhoneNormalized &&
            !state.expensePayeeLookupLoading

    val expenseNewCustomer =
        payeePhoneNormalized != null &&
            expenseLookupComplete &&
            expenseExistingCustomer == null

    val expensePayeeValid =
        when {
            payeePhone.rawNumber.isBlank() -> true
            !payeePhoneValid -> false
            state.expensePayeeLookupLoading -> false
            !expenseLookupComplete -> false
            expenseExistingCustomer != null -> true
            else -> payeeName.isNotBlank()
        }

    androidx.compose.runtime.LaunchedEffect(
        payeePhoneNormalized
    ) {
        when {
            payeePhone.rawNumber.isBlank() ->
                actions.onLookupPayeeByPhone(null)

            payeePhoneNormalized != null ->
                actions.onLookupPayeeByPhone(
                    payeePhoneNormalized
                )
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        expenseExistingCustomer?.id
    ) {
        expenseExistingCustomer?.let { customer ->
            payeeName = customer.name
        }
    }
    AlertDialog(
        onDismissRequest = {
            if (!locked) {
                actions.onDismiss()
            }
        },

        title = {
            Text(
                text = stringResource(R.string.dmr20_expenses),
                fontWeight = FontWeight.Bold
            )
        },

        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(
                            rememberScrollState()
                        ),

                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.dmr20_add_expense),
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = amount,

                    onValueChange = { value ->
                        if (!locked) {
                            val cleaned =
                                value.filter {
                                    it.isDigit() || it == '.'
                                }

                            if (
                                cleaned.count {
                                    it == '.'
                                } <= 1
                            ) {
                                amount = cleaned
                            }
                        }
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    enabled = !locked,

                    singleLine = true,

                    label = {
                        Text(
                            stringResource(R.string.dmr20_amount_currency, currencyCode)
                        )
                    }
                )

                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!locked) {
                                categoryExpanded = true
                            }
                        },

                        enabled = !locked,

                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            registerExpenseCategoryLabel20(
                                category.name
                            )
                        )
                    }

                    DropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = {
                            categoryExpanded = false
                        }
                    ) {
                        com.dailymoneyregister.feature.register.data
                            .ExpenseCategory
                            .entries
                            .forEach { option ->

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            registerExpenseCategoryLabel20(
                                                option.name
                                            )
                                        )
                                    },

                                    onClick = {
                                        categoryName =
                                            option.name

                                        categoryExpanded =
                                            false
                                    }
                                )
                            }
                    }
                }

                Text(
                    text = stringResource(R.string.dmr20_payment_method),
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected =
                            payment ==
                                com.dailymoneyregister.core.model.enums
                                    .PaymentCategory.CASH,

                        onClick = {
                            if (!locked) {
                                paymentName =
                                    com.dailymoneyregister.core.model.enums
                                        .PaymentCategory.CASH.name
                            }
                        },

                        enabled = !locked,

                        label = {
                            Text(stringResource(R.string.cash))
                        }
                    )

                    FilterChip(
                        selected =
                            payment ==
                                com.dailymoneyregister.core.model.enums
                                    .PaymentCategory.ONLINE,

                        onClick = {
                            if (!locked) {
                                paymentName =
                                    com.dailymoneyregister.core.model.enums
                                        .PaymentCategory.ONLINE.name
                            }
                        },

                        enabled = !locked,

                        label = {
                            Text(stringResource(R.string.online))
                        }
                    )
                }

                Text(
                    text = stringResource(R.string.dmr20_payee),
                    fontWeight = FontWeight.SemiBold
                )

                PhoneNumberInput(
                    value = payeePhone,
                    onValueChange = {
                        if (!state.isExecuting && !retryPending) {
                            payeePhoneCountryCode =
                                it.countryCode
                            payeePhoneRawNumber =
                                it.rawNumber
                            payeeName = ""
                        }
                    },
                    enabled = !state.isExecuting && !retryPending,
                    modifier = Modifier.fillMaxWidth()
                )

                when {
                    payeePhone.rawNumber.isBlank() -> {
                        Text(
                            text = stringResource(R.string.dmr20_find_customer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    !payeePhoneValid -> {
                        Text(
                            text = stringResource(R.string.dmr20_invalid_phone),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    state.expensePayeeLookupLoading -> {
                        Text(
                            text = stringResource(R.string.dmr20_finding_customer),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    expenseExistingCustomer != null -> {
                        Text(
                            text = expenseExistingCustomer.name,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.dmr20_existing_customer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    expenseNewCustomer -> {
                        OutlinedTextField(
                            value = payeeName,
                            onValueChange = {
                                if (!state.isExecuting && !retryPending) {
                                    payeeName = it
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isExecuting && !retryPending,
                            singleLine = true,
                            label = { Text(stringResource(R.string.name)) },
                            supportingText = {
                                if (payeeName.isBlank()) {
                                    Text(stringResource(R.string.dmr20_enter_customer_name))
                                }
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = {
                        if (!locked) {
                            note = it
                        }
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    enabled = !locked,
                    label = {
                        Text(stringResource(R.string.dmr20_note_optional))
                    }
                )

                OutlinedTextField(
                    value = reference,
                    onValueChange = {
                        if (!locked) {
                            reference = it
                        }
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    enabled = !locked,
                    singleLine = true,
                    label = {
                        Text(stringResource(R.string.dmr20_reference_optional))
                    }
                )

                state.errorMessage?.let {
                    Text(
                        text = it.asString(),
                        color =
                            MaterialTheme
                                .colorScheme
                                .error
                    )
                }

                Text(
                    text =
                        if (
                            payment ==
                                com.dailymoneyregister.core.model.enums
                                    .PaymentCategory.CASH
                        ) {
                            stringResource(R.string.dmr20_cash_expense_info)
                        } else {
                            stringResource(R.string.dmr20_online_expense_info)
                        },

                    style =
                        MaterialTheme.typography.bodySmall
                )

                Text(stringResource(R.string.dmr20_supporting_docs), fontWeight = FontWeight.SemiBold)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), enabled = !locked, onClick = {
                        val directory = File(context.cacheDir, "expense_supporting_document_capture")
                        if (!directory.exists()) directory.mkdirs()
                        val file = File.createTempFile("expense-", ".jpg", directory)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        pendingExpenseCameraUri = uri
                        expenseTakePhotoLauncher.launch(uri)
                    }) { Text(stringResource(R.string.dmr20_take_photo)) }

                    OutlinedButton(modifier = Modifier.weight(1f), enabled = !locked, onClick = {
                        expenseChooseDocumentLauncher.launch(arrayOf("image/*", "application/pdf"))
                    }) { Text(stringResource(R.string.dmr20_choose_file)) }
                }

                state.stagedExpenseSupportingDocumentUris.forEachIndexed { index, uri ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dmr20_selected_document) + " " + (index + 1), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !locked, onClick = { actions.onRemoveSupportingDocument(uri) }) {
                            Text(stringResource(R.string.dmr20_remove))
                        }
                    }
                }
                HorizontalDivider()

                Text(
                    text =
                        stringResource(R.string.dmr20_today_expenses) + " (${state.todayExpenses.size})",
                    fontWeight = FontWeight.SemiBold
                )

                if (state.expenseLoading) {
                    CircularProgressIndicator()
                } else if (state.todayExpenses.isEmpty()) {
                    Text(stringResource(R.string.dmr20_no_expenses))
                } else {
                    state.todayExpenses
                        .take(10)
                        .forEach { expense ->
                            val displayAmount =
                                com.dailymoneyregister.core.model.money
                                    .MoneyMinorUnits(
                                        expense.amountMinor
                                    )
                                    .formatDisplay(
                                        decimalPlaces,
                                        currencySymbol
                                    )

                            Surface(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            actions.onOpenExpenseDetails(expense.id)
                                        },

                                shape =
                                    RoundedCornerShape(12.dp),

                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .surfaceVariant
                                        .copy(alpha = 0.45f)
                            ) {
                                Column(
                                    modifier =
                                        Modifier.padding(10.dp),

                                    verticalArrangement =
                                        Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text =
                                            registerExpenseCategoryLabel20(
                                                expense.category
                                            ),

                                        fontWeight =
                                            FontWeight.SemiBold
                                    )

                                    Text(
                                        text =
                                            "$displayAmount \u2022 " +
                                                registerExpensePaymentLabel20(
                                                    expense.paymentCategory
                                                )
                                    )

                                    expense.payeeNameSnapshot
                                        ?.takeIf {
                                            it.isNotBlank()
                                        }
                                        ?.let { payee ->
                                            Text(
                                                text = stringResource(R.string.dmr20_paid_to, payee),
                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall
                                            )
                                        }

                                    expense.note
                                        ?.takeIf {
                                            it.isNotBlank()
                                        }
                                        ?.let {
                                            Text(
                                                text = it,
                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall
                                            )
                                        }
                                }
                            }
                        }

                    if (state.todayExpenses.size > 10) {
                        Text(
                            text =
                                stringResource(R.string.dmr20_latest_10),
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                }

            }
        },

        confirmButton = {
            Button(
                onClick = {
                    actions.onSave(
                        amount,
                        category,
                        payment,
                        expenseExistingCustomer?.name ?: payeeName,
                        payeePhoneNormalized,
                        note,
                        reference
                    )
                },

                enabled =
                    !locked &&
                        amountValid &&
                        payeePhoneValid &&
                        expensePayeeValid
            ) {
                Text(
                    if (state.isExecuting) {
                        stringResource(R.string.dmr20_saving)
                    } else {
                        stringResource(R.string.dmr20_save_expense)
                    }
                )
            }
        },

        dismissButton = {
            TextButton(
                onClick =
                    actions.onDismiss,

                enabled = !locked
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun registerExpenseCategoryLabel20(
    categoryName: String
): String =
    when (categoryName) {
        com.dailymoneyregister.feature.register.data.ExpenseCategory.RENT.name ->
            stringResource(R.string.dmr20_cat_rent)

        com.dailymoneyregister.feature.register.data.ExpenseCategory.UTILITIES.name ->
            stringResource(R.string.dmr20_cat_utilities)

        com.dailymoneyregister.feature.register.data.ExpenseCategory.SUPPLIES.name ->
            stringResource(R.string.dmr20_cat_supplies)

        com.dailymoneyregister.feature.register.data.ExpenseCategory.TRANSPORT.name ->
            stringResource(R.string.dmr20_cat_transport)

        com.dailymoneyregister.feature.register.data.ExpenseCategory.SALARY_WAGES.name ->
            stringResource(R.string.dmr20_cat_salary)

        com.dailymoneyregister.feature.register.data.ExpenseCategory.MAINTENANCE.name ->
            stringResource(R.string.dmr20_cat_maintenance)

        com.dailymoneyregister.feature.register.data.ExpenseCategory.FEES.name ->
            stringResource(R.string.dmr20_cat_fees)

        com.dailymoneyregister.feature.register.data.ExpenseCategory.FOOD_REFRESHMENTS.name ->
            stringResource(R.string.dmr20_cat_food)

        com.dailymoneyregister.feature.register.data.ExpenseCategory.OTHER.name ->
            stringResource(R.string.dmr20_cat_other)

        else ->
            categoryName
                .replace('_', ' ')
                .lowercase()
                .replaceFirstChar { it.uppercase() }
    }

@Composable
private fun registerExpensePaymentLabel20(
    categoryName: String
): String =
    when (categoryName) {
        com.dailymoneyregister.core.model.enums.PaymentCategory.CASH.name ->
            stringResource(R.string.tx_payment_cash)

        com.dailymoneyregister.core.model.enums.PaymentCategory.ONLINE.name ->
            stringResource(R.string.tx_payment_online)

        com.dailymoneyregister.core.model.enums.PaymentCategory.CREDIT.name ->
            stringResource(R.string.tx_payment_credit)

        com.dailymoneyregister.core.model.enums.PaymentCategory.COUPON.name ->
            stringResource(R.string.tx_payment_coupon)

        else ->
            categoryName
    }
