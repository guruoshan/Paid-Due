package com.dailymoneyregister.feature.customers.data

import com.dailymoneyregister.core.database.dao.CustomerDao
import com.dailymoneyregister.core.database.entity.CustomerEntity
import com.dailymoneyregister.core.model.ids.IdGenerator
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/** Thrown when a customer phone or name cannot be accepted. The message is safe to show. */
class CustomerIdentityException(message: String) : IllegalArgumentException(message)

data class ResolvedCustomer(
    val customer: CustomerEntity,
    /** True when this call created the customer; false when the phone already existed. */
    val wasCreated: Boolean,
    /** True when the phone already existed and the name typed differs from the saved name. */
    val enteredNameDiffers: Boolean
)

/**
 * The single rule for "who is this customer".
 *
 * One phone number in one business = one customer id. Credit, coupons,
 * invoices and anything added later must use this so a person never gets
 * two ids.
 *
 * - The phone is turned into one standard international form first, so
 *   "+968 9123 4567" and "+96891234567" are the same customer.
 * - If the phone already exists, that customer is returned. The SAVED name
 *   is kept; this never renames anyone.
 * - If the phone is new, one customer is created. A name is required.
 *
 * This does not open its own transaction. Call it inside the command's
 * transaction so the customer and the sale are saved together or not at all.
 */
object CustomerIdentity {

    fun canonicalPhone(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("+")) {
            throw CustomerIdentityException("Enter the phone number with its country code")
        }
        if (!text.matches(Regex("""^\+[0-9\s-]+$"""))) {
            throw CustomerIdentityException("Enter a valid international phone number")
        }
        val util = PhoneNumberUtil.getInstance()
        val parsed = try {
            util.parse(text, "ZZ")
        } catch (e: NumberParseException) {
            throw CustomerIdentityException("Enter a valid international phone number")
        }
        if (!util.isValidNumber(parsed)) {
            throw CustomerIdentityException("Enter a valid international phone number")
        }
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    suspend fun resolveOrCreate(
        customerDao: CustomerDao,
        businessId: String,
        rawPhone: String,
        rawName: String,
        now: Long,
        newId: () -> String = { IdGenerator.newId() }
    ): ResolvedCustomer {
        val phone = canonicalPhone(rawPhone)
        val name = rawName.trim()

        val existing = customerDao.getCustomerByPhone(businessId, phone)
        if (existing != null) {
            return ResolvedCustomer(
                customer = existing,
                wasCreated = false,
                enteredNameDiffers = name.isNotEmpty() && name != existing.name
            )
        }

        if (name.isEmpty()) {
            throw CustomerIdentityException("Enter the customer name")
        }
        val created = CustomerEntity(
            id = newId(),
            businessId = businessId,
            canonicalPhone = phone,
            name = name,
            createdAt = now,
            updatedAt = now
        )
        customerDao.insertCustomer(created)
        return ResolvedCustomer(created, wasCreated = true, enteredNameDiffers = false)
    }
}

