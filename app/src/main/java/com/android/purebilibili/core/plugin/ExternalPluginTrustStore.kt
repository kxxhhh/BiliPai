package com.android.purebilibili.core.plugin

import java.security.MessageDigest
import java.util.Base64

/** Public keys trusted for external plugin package previews. Private keys never belong in the app. */
object ExternalPluginTrustStore {
    const val BILI_COMPANION_KEY_ID = "bili-companion-release-2026"

    private const val BILI_COMPANION_PUBLIC_KEY_BASE64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqlE64Iwr8SxCrOOCu57RtEGhYm1/BHfsixG2upcXTrSaqdhYFs1K1MqGQ6G6k350/Pxo4RGCoHP7IldypTXBYx+xYPedIBzflfzRz17wjMBW+rhn5DvYbVcZp4X+eQspdU9J1a24gVfqBNLz307mH0Nwl5CIlqogh/h3SuVlvxm4VGSCG4eCiRaseXZ54y8VHO9kNnci1jqpWYQmJUw/KvuRDZaQwt5ZeT227TFxi9X7bSDchBS6EVqKNRwM3JNxnlZnBgX+m0Qp4A7yCONRZ4EWmTosMch/De+f3mZ/bUF0eabpDoTexwJ42GyrF9zZ5hVy8qb4zTZNlZLE1CGx9wIDAQAB"

    private val biliCompanionPublicKey: ByteArray by lazy {
        Base64.getDecoder().decode(BILI_COMPANION_PUBLIC_KEY_BASE64)
    }

    val trustedSignerSha256: Set<String> by lazy {
        setOf(MessageDigest.getInstance("SHA-256").digest(biliCompanionPublicKey).toHex())
    }

    fun trustedPublicKeys(): Map<String, ByteArray> = mapOf(
        BILI_COMPANION_KEY_ID to biliCompanionPublicKey.copyOf()
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
