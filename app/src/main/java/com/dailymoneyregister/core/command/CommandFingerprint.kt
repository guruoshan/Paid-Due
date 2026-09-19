package com.dailymoneyregister.core.command

import java.security.MessageDigest

object CommandFingerprint {

    fun sha256(canonicalRequest: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalRequest.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }
}
