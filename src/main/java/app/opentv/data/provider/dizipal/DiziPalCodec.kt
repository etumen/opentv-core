/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.dizipal

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Android-free helpers for DiziPal's encrypted player payload. */
internal object DiziPalCodec {
    private const val PASSPHRASE =
        "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

    fun decryptRmk(rawJson: String): String? =
        runCatching {
            val ciphertext = field(rawJson, "ciphertext")
                ?.replace("\\/", "/")
                ?: return null
            val ivHex = field(rawJson, "iv") ?: return null
            val saltHex = field(rawJson, "salt") ?: return null

            val iv = decodeHex(ivHex)
            val salt = decodeHex(saltHex)
            val encrypted = decodeBase64(ciphertext)

            val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val keySpec = PBEKeySpec(
                PASSPHRASE.toCharArray(),
                salt,
                999,
                256,
            )
            val key = SecretKeySpec(keyFactory.generateSecret(keySpec).encoded, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            val plain = String(
                cipher.doFinal(encrypted),
                StandardCharsets.UTF_8,
            ).replace("\\/", "/").trim()

            normalizeUrl(plain)
        }.getOrNull()

    private fun field(raw: String, name: String): String? =
        Regex(
            """"${Regex.escape(name)}"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun decodeBase64(value: String): ByteArray {
        val clean = value.filterNot(Char::isWhitespace)
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }

    private fun decodeHex(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "Hex value must have even length" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun normalizeUrl(raw: String): String? =
        when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("://") -> "https$raw"
            raw.isNotBlank() -> "https://" + raw.trimStart('/')
            else -> null
        }
}
