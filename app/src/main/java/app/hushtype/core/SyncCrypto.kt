package app.hushtype.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The keys and sealing for encrypted device sync, as specified in hushtype's sync-server/README.md.
 * One random 32-byte secret, shared by QR code, gives the group id, the server token and the
 * AES-256-GCM key. The server only ever sees the sealed blob.
 */
class SyncKeys(val secret: ByteArray) {
    init {
        require(secret.size == 32) { "The sync secret is 32 bytes." }
    }

    /** The group's id on the server: 32 lowercase hex characters. */
    val groupId: String = derive("group-id").copyOf(16).joinToString("") { "%02x".format(it) }

    /** Sent as `Authorization: Bearer …`. */
    val authToken: String = Base64.getUrlEncoder().withoutPadding().encodeToString(derive("auth"))

    /** What the QR code holds and what you can type on a Mac. */
    val pairingCode: String get() = PairingCode.encode(secret)

    private val encryptionKey = derive("encryption")

    /** 0x01, a 12-byte nonce, then the ciphertext and 16-byte tag, with the group id as associated data. */
    fun seal(plaintext: ByteArray, nonce: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(groupId.toByteArray())
        return byteArrayOf(VERSION) + nonce + cipher.doFinal(plaintext)
    }

    /** Opens a blob; throws when it isn't ours or was changed. */
    fun open(blob: ByteArray): ByteArray {
        require(blob.size > 1 + 12 + 16 && blob[0] == VERSION) { "Not a Hushtype sync blob." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(128, blob, 1, 12))
        cipher.updateAAD(groupId.toByteArray())
        return cipher.doFinal(blob, 13, blob.size - 13)
    }

    private fun derive(info: String): ByteArray = hkdf(secret, SALT.toByteArray(), info.toByteArray(), 32)

    companion object {
        private const val SALT = "hushtype-sync-v1"
        private const val VERSION: Byte = 1

        fun random() = SyncKeys(ByteArray(32).also { SecureRandom().nextBytes(it) })

        /** HKDF-SHA256 (RFC 5869). */
        fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var filled = 0
            var counter = 1
            while (filled < length) {
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val n = minOf(previous.size, length - filled)
                previous.copyInto(out, filled, 0, n)
                filled += n
                counter++
            }
            return out
        }
    }
}

/** `HT1-` and the secret in Crockford base32, in groups of four. */
object PairingCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(secret: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in secret) {
            buffer = ((buffer shl 8) or (byte.toInt() and 0xff)) and 0xffff
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return "HT1-" + out.chunked(4).joinToString("-")
    }

    /** Reads a typed or scanned code. Returns null when it isn't a valid Hushtype code. */
    fun decode(code: String): ByteArray? {
        val clean = code.uppercase().filter { it.isLetterOrDigit() }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
        if (!clean.startsWith("HT1")) return null
        val body = clean.removePrefix("HT1")
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in body) {
            val value = ALPHABET.indexOf(ch)
            if (value < 0) return null
            buffer = ((buffer shl 5) or value) and 0xffff
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray().takeIf { it.size == 32 }
    }
}
