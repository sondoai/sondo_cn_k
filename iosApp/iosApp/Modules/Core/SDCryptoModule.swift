import Foundation
import Security
import CommonCrypto

/// 加密。契约见 shared/.../nativeapi/SDAppModule.kt（SDCryptoModule）。全部为同步方法（Kuikly 线程，纯计算）。
///
/// 与参考工程 `CommonUtils.encryptStr / encrypt`（Dart encrypt 包）逐项对齐：
/// - rsaEncrypt：对明文的 UTF-8 字节做 RSA PKCS#1 v1.5 加密 → Base64；
/// - hybridEncrypt：随机 32 字节 AES key + 随机 16 字节 IV，AES-256-CBC / PKCS7 加密明文 UTF-8；
///   RSA PKCS#1 v1.5 加密的是 **AES key 的原始 32 字节**；`iv` 为原始 IV 字节的 Base64。
/// 失败时 rsaEncrypt 返回空串，hybridEncrypt 返回 "{}"。
@objc(SDCryptoModule)
@objcMembers
final class SDCryptoModule: KRBaseModule {

    /// params: {publicKey, text} → Base64 密文
    func rsaEncrypt(_ args: NSDictionary) -> NSString {
        let params = SD.params(args)
        guard let key = SDCrypto.rsaPublicKey(from: params.sdString("publicKey")),
              let cipher = SDCrypto.rsaEncrypt(Data(params.sdString("text").utf8), with: key) else {
            return ""
        }
        return cipher.base64EncodedString() as NSString
    }

    /// params: {publicKey, text} → JSON 字符串 {data, key, iv}（全部 Base64）
    func hybridEncrypt(_ args: NSDictionary) -> NSString {
        let params = SD.params(args)
        guard let publicKey = SDCrypto.rsaPublicKey(from: params.sdString("publicKey")),
              let aesKey = SDCrypto.randomBytes(kCCKeySizeAES256),
              let iv = SDCrypto.randomBytes(kCCBlockSizeAES128),
              let data = SDCrypto.aesCBCEncrypt(Data(params.sdString("text").utf8), key: aesKey, iv: iv),
              let wrappedKey = SDCrypto.rsaEncrypt(aesKey, with: publicKey) else {
            return "{}"
        }
        let result: [String: Any] = [
            "data": data.base64EncodedString(),
            "key": wrappedKey.base64EncodedString(),
            "iv": iv.base64EncodedString(),
        ]
        return SD.jsonResult(result)
    }

    /// params: {text} → 十六进制小写
    func md5(_ args: NSDictionary) -> NSString {
        return SDHash.md5Hex(SD.params(args).sdString("text")) as NSString
    }

    /// params: {text} → 十六进制小写
    func sha256(_ args: NSDictionary) -> NSString {
        return SDHash.sha256Hex(Data(SD.params(args).sdString("text").utf8)) as NSString
    }

    /// CSPRNG 十六进制串。params: {length}（字符数）
    func randomHex(_ args: NSDictionary) -> NSString {
        let length = max(0, min(SD.params(args).sdInt("length", 0), 4096))
        guard length > 0, let bytes = SDCrypto.randomBytes((length + 1) / 2) else { return "" }
        return String(SDHash.hex(bytes).prefix(length)) as NSString
    }

    /// params: {text} → Base64（UTF-8）
    func base64Encode(_ args: NSDictionary) -> NSString {
        return Data(SD.params(args).sdString("text").utf8).base64EncodedString() as NSString
    }

    /// params: {base64} → 文本（UTF-8）；非法输入返回空串
    func base64Decode(_ args: NSDictionary) -> NSString {
        let text = SD.params(args).sdString("base64")
        guard let data = Data(base64Encoded: text, options: .ignoreUnknownCharacters) else { return "" }
        return String(decoding: data, as: UTF8.self) as NSString
    }
}

/// 加密原语（Security.framework + CommonCrypto）。
enum SDCrypto {

    // MARK: - 随机数

    static func randomBytes(_ count: Int) -> Data? {
        guard count > 0 else { return nil }
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        guard status == errSecSuccess else { return nil }
        return Data(bytes)
    }

    // MARK: - RSA

    /// 解析公钥：X.509 SubjectPublicKeyInfo 的 Base64，可带 `-----BEGIN PUBLIC KEY-----` 头尾和换行；
    /// 也兼容直接给 PKCS#1 `RSAPublicKey` 的情况。
    static func rsaPublicKey(from text: String) -> SecKey? {
        let body = text
            .components(separatedBy: .newlines)
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("-----") }
            .joined()
        let base64 = body.components(separatedBy: .whitespacesAndNewlines).joined()
        guard !base64.isEmpty,
              let der = Data(base64Encoded: base64, options: .ignoreUnknownCharacters),
              !der.isEmpty else {
            return nil
        }
        // SecKeyCreateWithData 要的是 PKCS#1 RSAPublicKey；SPKI 需要先剥掉算法标识头。
        if let pkcs1 = pkcs1PublicKey(fromSPKI: der), let key = createRSAPublicKey(pkcs1) {
            return key
        }
        return createRSAPublicKey(der)
    }

    private static func createRSAPublicKey(_ der: Data) -> SecKey? {
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass: kSecAttrKeyClassPublic,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(der as CFData, attributes as CFDictionary, &error) else {
            error?.release()
            return nil
        }
        return key
    }

    /// RSA PKCS#1 v1.5 加密（单块：明文长度不能超过 密钥字节数 − 11，与 Dart encrypt 包一致）。
    static func rsaEncrypt(_ plaintext: Data, with key: SecKey) -> Data? {
        let algorithm = SecKeyAlgorithm.rsaEncryptionPKCS1
        guard SecKeyIsAlgorithmSupported(key, .encrypt, algorithm),
              plaintext.count <= SecKeyGetBlockSize(key) - 11 else {
            return nil
        }
        var error: Unmanaged<CFError>?
        guard let cipher = SecKeyCreateEncryptedData(key, algorithm, plaintext as CFData, &error) else {
            error?.release()
            return nil
        }
        return cipher as Data
    }

    /// X.509 SubjectPublicKeyInfo → 内层的 PKCS#1 RSAPublicKey。
    ///
    ///     SubjectPublicKeyInfo ::= SEQUENCE {
    ///         algorithm        SEQUENCE { OID rsaEncryption, NULL },
    ///         subjectPublicKey BIT STRING { RSAPublicKey } }
    ///
    /// 输入不是 SPKI（例如已经是 PKCS#1）时返回 nil。
    static func pkcs1PublicKey(fromSPKI der: Data) -> Data? {
        let bytes = [UInt8](der)
        var index = 0

        // 外层 SEQUENCE
        guard readTag(0x30, bytes, &index), readLength(bytes, &index) != nil else { return nil }
        // 第一个元素是 INTEGER 说明已经是 PKCS#1（modulus），不是 SPKI
        guard index < bytes.count, bytes[index] == 0x30 else { return nil }
        // AlgorithmIdentifier：整体跳过
        guard readTag(0x30, bytes, &index),
              let algorithmLength = readLength(bytes, &index),
              index + algorithmLength <= bytes.count else {
            return nil
        }
        index += algorithmLength
        // subjectPublicKey BIT STRING
        guard readTag(0x03, bytes, &index),
              let bitStringLength = readLength(bytes, &index),
              bitStringLength > 1,
              index + bitStringLength <= bytes.count,
              bytes[index] == 0x00 else {
            return nil
        }
        // 跳过「未使用位数」那个字节
        let start = index + 1
        let end = index + bitStringLength
        return Data(bytes[start..<end])
    }

    private static func readTag(_ tag: UInt8, _ bytes: [UInt8], _ index: inout Int) -> Bool {
        guard index < bytes.count, bytes[index] == tag else { return false }
        index += 1
        return true
    }

    /// DER 长度字段（短格式 / 长格式，最多 4 字节）。
    private static func readLength(_ bytes: [UInt8], _ index: inout Int) -> Int? {
        guard index < bytes.count else { return nil }
        let first = bytes[index]
        index += 1
        if first & 0x80 == 0 {
            return Int(first)
        }
        let count = Int(first & 0x7F)
        guard count >= 1, count <= 4, index + count <= bytes.count else { return nil }
        var length = 0
        for offset in 0..<count {
            length = (length << 8) | Int(bytes[index + offset])
        }
        index += count
        return length
    }

    // MARK: - AES

    /// AES-256-CBC + PKCS7。key 必须 32 字节，iv 必须 16 字节。
    static func aesCBCEncrypt(_ plaintext: Data, key: Data, iv: Data) -> Data? {
        guard key.count == kCCKeySizeAES256, iv.count == kCCBlockSizeAES128 else { return nil }
        let inputLength = plaintext.count
        let capacity = inputLength + kCCBlockSizeAES128
        var output = [UInt8](repeating: 0, count: capacity)
        var produced = 0
        let keyBytes = [UInt8](key)
        let ivBytes = [UInt8](iv)
        let inputBytes = [UInt8](plaintext)
        let status = CCCrypt(
            CCOperation(kCCEncrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionPKCS7Padding),
            keyBytes, keyBytes.count,
            ivBytes,
            inputBytes, inputLength,
            &output, capacity,
            &produced
        )
        guard status == CCCryptorStatus(kCCSuccess), produced > 0, produced <= capacity else { return nil }
        return Data(output[0..<produced])
    }
}
