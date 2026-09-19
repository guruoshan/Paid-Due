package com.dailymoneyregister.core.database

import androidx.room.TypeConverter
import com.dailymoneyregister.core.model.enums.AuthType
import com.dailymoneyregister.core.model.enums.AuthorizedUserEventType
import com.dailymoneyregister.core.model.enums.CashShiftEventType
import com.dailymoneyregister.core.model.enums.ConfirmationType
import com.dailymoneyregister.core.model.enums.CouponLedgerType
import com.dailymoneyregister.core.model.enums.CreditLedgerType
import com.dailymoneyregister.core.model.enums.DocumentType
import com.dailymoneyregister.core.model.enums.EntryPurpose
import com.dailymoneyregister.core.model.enums.MediaType
import com.dailymoneyregister.core.model.enums.OperationStatus
import com.dailymoneyregister.core.model.enums.OperationType
import com.dailymoneyregister.core.model.enums.PaymentCategory
import com.dailymoneyregister.core.model.enums.PaymentChannel
import com.dailymoneyregister.core.model.enums.SubjectType
import com.dailymoneyregister.core.model.enums.TransactionType
import com.dailymoneyregister.core.model.enums.UserRole
import com.dailymoneyregister.core.model.tax.TaxRoundingMode

class Converters {

    @TypeConverter fun fromOperationStatus(value: OperationStatus?): String? = value?.name
    @TypeConverter fun toOperationStatus(value: String?): OperationStatus? = value?.let { OperationStatus.valueOf(it) }

    @TypeConverter fun fromOperationType(value: OperationType?): String? = value?.name
    @TypeConverter fun toOperationType(value: String?): OperationType? = value?.let { OperationType.valueOf(it) }

    @TypeConverter fun fromSubjectType(value: SubjectType?): String? = value?.name
    @TypeConverter fun toSubjectType(value: String?): SubjectType? = value?.let { SubjectType.valueOf(it) }

    @TypeConverter fun fromPaymentCategory(value: PaymentCategory?): String? = value?.name
    @TypeConverter fun toPaymentCategory(value: String?): PaymentCategory? = value?.let { PaymentCategory.valueOf(it) }

    @TypeConverter fun fromPaymentChannel(value: PaymentChannel?): String? = value?.name
    @TypeConverter fun toPaymentChannel(value: String?): PaymentChannel? = value?.let { PaymentChannel.valueOf(it) }

    @TypeConverter fun fromConfirmationType(value: ConfirmationType?): String? = value?.name
    @TypeConverter fun toConfirmationType(value: String?): ConfirmationType? = value?.let { ConfirmationType.valueOf(it) }

    @TypeConverter fun fromEntryPurpose(value: EntryPurpose?): String? = value?.name
    @TypeConverter fun toEntryPurpose(value: String?): EntryPurpose? = value?.let { EntryPurpose.valueOf(it) }

    @TypeConverter fun fromTransactionType(value: TransactionType?): String? = value?.name
    @TypeConverter fun toTransactionType(value: String?): TransactionType? = value?.let { TransactionType.valueOf(it) }

    @TypeConverter fun fromCreditLedgerType(value: CreditLedgerType?): String? = value?.name
    @TypeConverter fun toCreditLedgerType(value: String?): CreditLedgerType? = value?.let { CreditLedgerType.valueOf(it) }

    @TypeConverter fun fromCouponLedgerType(value: CouponLedgerType?): String? = value?.name
    @TypeConverter fun toCouponLedgerType(value: String?): CouponLedgerType? = value?.let { CouponLedgerType.valueOf(it) }

    @TypeConverter fun fromAuthorizedUserEventType(value: AuthorizedUserEventType?): String? = value?.name
    @TypeConverter fun toAuthorizedUserEventType(value: String?): AuthorizedUserEventType? = value?.let { AuthorizedUserEventType.valueOf(it) }

    @TypeConverter fun fromCashShiftEventType(value: CashShiftEventType?): String? = value?.name
    @TypeConverter fun toCashShiftEventType(value: String?): CashShiftEventType? = value?.let { CashShiftEventType.valueOf(it) }

    @TypeConverter fun fromUserRole(value: UserRole?): String? = value?.name
    @TypeConverter fun toUserRole(value: String?): UserRole? = value?.let { UserRole.valueOf(it) }

    @TypeConverter fun fromAuthType(value: AuthType?): String? = value?.name
    @TypeConverter fun toAuthType(value: String?): AuthType? = value?.let { AuthType.valueOf(it) }

    @TypeConverter fun fromDocumentType(value: DocumentType?): String? = value?.name
    @TypeConverter fun toDocumentType(value: String?): DocumentType? = value?.let { DocumentType.valueOf(it) }

    @TypeConverter fun fromMediaType(value: MediaType?): String? = value?.name
    @TypeConverter fun toMediaType(value: String?): MediaType? = value?.let { MediaType.valueOf(it) }

    @TypeConverter fun fromTaxRoundingMode(value: TaxRoundingMode?): String? = value?.name
    @TypeConverter fun toTaxRoundingMode(value: String?): TaxRoundingMode? = value?.let { TaxRoundingMode.valueOf(it) }
}
