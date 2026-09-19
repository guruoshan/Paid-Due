package com.dailymoneyregister.core.identity

import com.google.i18n.phonenumbers.PhoneNumberUtil

class InvalidPhoneNumberException(message: String) : IllegalArgumentException(message)

object PhoneNumberNormalizer {
    private val util = PhoneNumberUtil.getInstance()

    fun normalize(raw: String, defaultRegion: String): String {
        val input = raw.trim()
        if (input.isEmpty()) throw InvalidPhoneNumberException("WhatsApp / Mobile Number is required")
        val parsed = try {
            util.parse(input, defaultRegion.uppercase())
        } catch (e: Exception) {
            throw InvalidPhoneNumberException("Invalid WhatsApp / Mobile Number")
        }
        if (!util.isValidNumber(parsed)) throw InvalidPhoneNumberException("Invalid WhatsApp / Mobile Number")
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }
}
