package com.sondo.music.video.ai.kuikly.module

import android.util.Base64
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密 / 摘要（契约：shared/nativeapi/SDAppModule.kt 里的 SDCryptoModule）。全部是同步方法，运行在 Kuikly 线程；
 * RSA-2048 公钥加密与一次 AES 都是亚毫秒级，不会卡渲染。
 *
 * 与参考工程 `CommonUtils.encryptStr` / `CommonUtils.encrypt`（Dart encrypt 5.0.3）逐字节对齐：
 * - rsaEncrypt：明文 UTF-8 → RSA PKCS#1 v1.5 → Base64；
 * - hybridEncrypt：随机 32 字节 AES key + 16 字节 IV，AES-256/CBC/PKCS7 加密明文 UTF-8；
 *   **被 RSA 包裹的是 AES key 的原始 32 字节**（不是它的 Base64 文本）；
 *   返回 data = Base64(密文)，key = Base64(RSA(原始 key))，iv = Base64(原始 IV)。
 * Base64 均为标准字母表、带 padding、不换行。失败时返回空串（Kuikly 侧按空结果处理）。
 */
class SDCryptoModule : SDCoreBaseModule() {

    override fun handle(method: String, p: JSONObject, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "rsaEncrypt" -> safe { rsaEncrypt(p.optString("publicKey"), p.optString("text").toByteArray(Charsets.UTF_8)) }
            "hybridEncrypt" -> safe { hybridEncrypt(p.optString("publicKey"), p.optString("text")) }
            "md5" -> safe { digestHex("MD5", p.optString("text")) }
            "sha256" -> safe { digestHex("SHA-256", p.optString("text")) }
            "randomHex" -> safe { randomHex(p.optInt("length", 0)) }
            "base64Encode" -> safe { b64(p.optString("text").toByteArray(Charsets.UTF_8)) }
            "base64Decode" -> safe { String(decodeBase64Lenient(p.optString("base64")), Charsets.UTF_8) }
            else -> unknown(method, callback)
        }
    }

    private inline fun safe(block: () -> String): String = try {
        block()
    } catch (e: Throwable) {
        SDCoreKit.logE("crypto failed", e)
        ""
    }

    private fun rsaEncrypt(publicKey: String, plain: ByteArray): String {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKeyOf(publicKey))
        return b64(cipher.doFinal(plain))
    }

    private fun hybridEncrypt(publicKey: String, text: String): String {
        val key = ByteArray(32).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }
        // JCE 的 PKCS5Padding 在 16 字节分组下即 PKCS7
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val data = aes.doFinal(text.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("data", b64(data))
            .put("key", rsaEncrypt(publicKey, key))
            .put("iv", b64(iv))
            .toString()
    }

    private fun digestHex(algorithm: String, text: String): String =
        SDCoreKit.hex(MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8)))

    private fun randomHex(length: Int): String {
        if (length <= 0) return ""
        val n = length.coerceAtMost(MAX_RANDOM_HEX)
        val bytes = ByteArray((n + 1) / 2).also { random.nextBytes(it) }
        return SDCoreKit.hex(bytes).substring(0, n)
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** 兼容 URL-safe 字母表、缺失 padding 与夹带的空白。 */
    private fun decodeBase64Lenient(input: String): ByteArray {
        val compact = input.filterNot { it.isWhitespace() }
        val urlSafe = compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0
        return Base64.decode(compact, if (urlSafe) Base64.URL_SAFE else Base64.DEFAULT)
    }

    companion object {
        const val MODULE_NAME = "SDCryptoModule"

        private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        private const val MAX_RANDOM_HEX = 4096

        private val random = SecureRandom()

        // 公钥只有一把（assets/public_key.pem），解析结果按原文缓存
        @Volatile
        private var cachedKey: Pair<String, PublicKey>? = null

        private fun publicKeyOf(raw: String): PublicKey {
            cachedKey?.let { if (it.first == raw) return it.second }
            val key = parsePublicKey(raw)
            cachedKey = raw to key
            return key
        }

        /**
         * 公钥：X.509 SubjectPublicKeyInfo 的 Base64，可带 PEM 头尾 / 换行。
         * 另外兼容 PKCS#1（`BEGIN RSA PUBLIC KEY`）—— Dart 的 RSAKeyParser 两种都认。
         */
        private fun parsePublicKey(raw: String): PublicKey {
            val pkcs1Header = raw.contains("BEGIN RSA PUBLIC KEY")
            val body = raw.lineSequence()
                .map { it.trim() }
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .filterNot { it.isWhitespace() }
            require(body.isNotEmpty()) { "empty public key" }
            val der = Base64.decode(body, Base64.DEFAULT)
            val factory = KeyFactory.getInstance("RSA")
            if (!pkcs1Header) {
                try {
                    return factory.generatePublic(X509EncodedKeySpec(der))
                } catch (e: Exception) {
                    // 没有 PEM 头但内容是 PKCS#1：落到下面再试一次
                }
            }
            return factory.generatePublic(X509EncodedKeySpec(wrapPkcs1(der)))
        }

        /** PKCS#1 RSAPublicKey → SubjectPublicKeyInfo（外面套 rsaEncryption 的 AlgorithmIdentifier）。 */
        private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            // SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }
            val algorithm = byteArrayOf(
                0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
                0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
            )
            val bitString = derTlv(0x03, byteArrayOf(0x00) + pkcs1)
            return derTlv(0x30, algorithm + bitString)
        }

        private fun derTlv(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(content.size + 6)
            out.write(tag)
            val len = content.size
            when {
                len < 0x80 -> out.write(len)
                len <= 0xff -> {
                    out.write(0x81)
                    out.write(len)
                }
                len <= 0xffff -> {
                    out.write(0x82)
                    out.write(len ushr 8)
                    out.write(len and 0xff)
                }
                else -> {
                    out.write(0x83)
                    out.write(len ushr 16)
                    out.write((len ushr 8) and 0xff)
                    out.write(len and 0xff)
                }
            }
            out.write(content, 0, content.size)
            return out.toByteArray()
        }
    }
}
