package com.sondo.app.data.api

import com.sondo.app.nativeapi.SDCryptoModule
import com.sondo.app.nativeapi.nativeModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 请求加密（Dart: lib/core/utils/common.dart `CommonUtils.encryptStr` / `CommonUtils.encrypt`，
 * 公钥来自 assets/public_key.pem）。
 *
 * - [rsaEncrypt]：RSA PKCS#1 v1.5 加密后 Base64（returning-user 接口的 `k2`）。
 * - [hybridEncrypt]：AES-256-CBC/PKCS7 加密正文，随机 key 再用 RSA 加密，
 *   返回 `{data, key, iv}`（均为 Base64），用于 V2 验单。
 * 实际运算在原生 SDCryptoModule。
 */
object ApiCrypto {
    const val PUBLIC_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n" +
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwaXUoa/3rD7Wa5K4h1wi\n" +
        "0vpyFG12BgOUkZBnRtimrn7dJbiADHeiOK2W9vtkO4enjleXcITF3D0PZZC7LUdX\n" +
        "KTCBl0lYc4mLnbH2XU8K3DszgBnkja6sbXVHWvW1rexKLPWAmKLOpk1M8bBCIxyq\n" +
        "N2Zxr6nYfjqz4rurQBE13rx30gO2dmd3l7JOsq0wDIOmijYO+MQogD02kM40mnmp\n" +
        "FCHZCB9xv4kWOWlatHH0AsoLzaY6gRncFUTLYPE6sj8/rwQE/ab1UI+Q3YkthT/d\n" +
        "3Cw86IvICVwEh97+veRHHrwB3bPGogrF3t3wFW6+SDdXSdJyDIgf9+rxSAWAXojl\n" +
        "+QIDAQAB\n" +
        "-----END PUBLIC KEY-----\n"

    private fun module(): SDCryptoModule = nativeModule(SDCryptoModule.MODULE_NAME)

    /** RSA(PKCS#1 v1.5) 加密 UTF-8 文本，返回 Base64；失败返回空串。 */
    fun rsaEncrypt(text: String): String = try {
        module().rsaEncrypt(PUBLIC_KEY_PEM, text)
    } catch (e: Throwable) {
        ""
    }

    /** AES+RSA 信封 `{data, key, iv}`；失败返回空对象（调用方须检查三个字段非空）。 */
    fun hybridEncrypt(text: String): JSONObject = try {
        module().hybridEncrypt(PUBLIC_KEY_PEM, text)
    } catch (e: Throwable) {
        JSONObject()
    }
}
