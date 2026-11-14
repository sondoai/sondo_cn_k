/*
 * SDCryptoModule —— 加密（契约见 shared/.../nativeapi/SDAppModule.kt 里的 SDCryptoModule）。
 * 全部是同步方法（同步模式模块），失败时返回空串 / "{}"，不抛异常。
 *
 *   rsaEncrypt {publicKey, text}     → Base64(RSA PKCS#1 v1.5(UTF-8 text))
 *   hybridEncrypt {publicKey, text}  → {"data","key","iv"}：
 *        随机 32 字节 AES key + 16 字节 IV，AES-256-CBC / PKCS7 加密 UTF-8 明文 → data；
 *        AES key 原始字节用 RSA PKCS#1 v1.5 加密 → key；iv 为原始 IV。全部标准 Base64。
 *        （与参考工程 CommonUtils.encrypt / encryptStr 一致）
 *   md5 {text} / sha256 {text}       → 小写十六进制（UTF-8）
 *   randomHex {length}               → CSPRNG 十六进制串，length 为字符数
 *   base64Encode {text} / base64Decode {base64}   UTF-8 文本 ↔ 标准 Base64
 *
 * 公钥：X.509 SubjectPublicKeyInfo 的 Base64，可带 PEM 头尾 / 换行；
 * 也兼容 PKCS#1（BEGIN RSA PUBLIC KEY），会先包成 SPKI。
 */
import { buffer } from '@kit.ArkTS';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SD, SDLog, SDParams, SDResult } from '../../support/SDModuleSupport';

const TAG: string = 'SDCryptoModule';

/** DER TLV 节点 */
class DerNode {
  tag: number = 0;
  contentStart: number = 0;
  contentLength: number = 0;

  get end(): number {
    return this.contentStart + this.contentLength;
  }
}

class RsaPublicKeyInfo {
  /** X.509 SPKI DER */
  spki: Uint8Array;
  /** 模长（bit） */
  bits: number;

  constructor(spki: Uint8Array, bits: number) {
    this.spki = spki;
    this.bits = bits;
  }
}

export class SDCryptoUtil {
  static utf8Bytes(text: string): Uint8Array {
    const buf: buffer.Buffer = buffer.from(text, 'utf-8');
    return new Uint8Array(buf.buffer, buf.byteOffset, buf.length);
  }

  static utf8String(bytes: Uint8Array): string {
    return buffer.from(bytes).toString('utf-8');
  }

  static toBase64(bytes: Uint8Array): string {
    return buffer.from(bytes).toString('base64');
  }

  static fromBase64(text: string): Uint8Array {
    const buf: buffer.Buffer = buffer.from(text, 'base64');
    return new Uint8Array(buf.buffer, buf.byteOffset, buf.length);
  }

  static toHex(bytes: Uint8Array): string {
    return buffer.from(bytes).toString('hex');
  }

  static randomBytes(length: number): Uint8Array {
    const random: cryptoFramework.Random = cryptoFramework.createRandom();
    return random.generateRandomSync(length).data;
  }

  /** 摘要（algorithm: "MD5" | "SHA256" | …）→ 小写十六进制 */
  static digestHex(algorithm: string, bytes: Uint8Array): string {
    const md: cryptoFramework.Md = cryptoFramework.createMd(algorithm);
    const input: cryptoFramework.DataBlob = { data: bytes };
    md.updateSync(input);
    return SDCryptoUtil.toHex(md.digestSync().data);
  }
}

export class SDCryptoModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDCryptoModule';

  private static readonly RSA_SIZES: Array<number> = [512, 768, 1024, 2048, 3072, 4096, 8192];

  syncMode(): boolean {
    return true;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const p: SDParams = SD.parse(params);
    try {
      switch (method) {
        case 'rsaEncrypt':
          return this.rsaEncrypt(SD.str(p, 'publicKey'), SDCryptoUtil.utf8Bytes(SD.str(p, 'text')));
        case 'hybridEncrypt':
          return this.hybridEncrypt(SD.str(p, 'publicKey'), SD.str(p, 'text'));
        case 'md5':
          return SDCryptoUtil.digestHex('MD5', SDCryptoUtil.utf8Bytes(SD.str(p, 'text')));
        case 'sha256':
          return SDCryptoUtil.digestHex('SHA256', SDCryptoUtil.utf8Bytes(SD.str(p, 'text')));
        case 'randomHex':
          return this.randomHex(SD.int(p, 'length', 0));
        case 'base64Encode':
          return SDCryptoUtil.toBase64(SDCryptoUtil.utf8Bytes(SD.str(p, 'text')));
        case 'base64Decode':
          return SDCryptoUtil.utf8String(SDCryptoUtil.fromBase64(this.compact(SD.str(p, 'base64'))));
        default:
          return '';
      }
    } catch (e) {
      SDLog.e(TAG, `${method} failed: ${SD.errMsg(e as Object)}`);
      return method === 'hybridEncrypt' ? '{}' : '';
    }
  }

  // -----------------------------------------------------------------------
  // RSA PKCS#1 v1.5
  // -----------------------------------------------------------------------

  private rsaEncrypt(publicKey: string, plain: Uint8Array): string {
    const info: RsaPublicKeyInfo = this.parsePublicKey(publicKey);
    const generator: cryptoFramework.AsyKeyGenerator = cryptoFramework.createAsyKeyGenerator(`RSA${info.bits}`);
    const keyBlob: cryptoFramework.DataBlob = { data: info.spki };
    const keyPair: cryptoFramework.KeyPair = generator.convertKeySync(keyBlob, null);
    const cipher: cryptoFramework.Cipher = cryptoFramework.createCipher(`RSA${info.bits}|PKCS1`);
    cipher.initSync(cryptoFramework.CryptoMode.ENCRYPT_MODE, keyPair.pubKey, null);
    const input: cryptoFramework.DataBlob = { data: plain };
    const output: cryptoFramework.DataBlob = cipher.doFinalSync(input);
    return SDCryptoUtil.toBase64(output.data);
  }

  // -----------------------------------------------------------------------
  // AES-256-CBC/PKCS7 + RSA 包裹密钥
  // -----------------------------------------------------------------------

  private hybridEncrypt(publicKey: string, text: string): string {
    const aesKey: Uint8Array = SDCryptoUtil.randomBytes(32);
    const iv: Uint8Array = SDCryptoUtil.randomBytes(16);

    const keyGenerator: cryptoFramework.SymKeyGenerator = cryptoFramework.createSymKeyGenerator('AES256');
    const aesKeyBlob: cryptoFramework.DataBlob = { data: aesKey };
    const symKey: cryptoFramework.SymKey = keyGenerator.convertKeySync(aesKeyBlob);
    const ivBlob: cryptoFramework.DataBlob = { data: iv };
    const ivSpec: cryptoFramework.IvParamsSpec = { algName: 'IvParamsSpec', iv: ivBlob };
    const cipher: cryptoFramework.Cipher = cryptoFramework.createCipher('AES256|CBC|PKCS7');
    cipher.initSync(cryptoFramework.CryptoMode.ENCRYPT_MODE, symKey, ivSpec);
    const input: cryptoFramework.DataBlob = { data: SDCryptoUtil.utf8Bytes(text) };
    const encrypted: Uint8Array = this.cipherFinal(cipher, input);

    const wrappedKey: string = this.rsaEncrypt(publicKey, aesKey);

    const result: SDResult = {};
    result['data'] = SDCryptoUtil.toBase64(encrypted);
    result['key'] = wrappedKey;
    result['iv'] = SDCryptoUtil.toBase64(iv);
    return SD.json(result);
  }

  /** update + doFinal(null)，兼容 doFinal 只返回尾块的实现。 */
  private cipherFinal(cipher: cryptoFramework.Cipher, input: cryptoFramework.DataBlob): Uint8Array {
    const head: cryptoFramework.DataBlob | null = cipher.updateSync(input);
    const tail: cryptoFramework.DataBlob | null = cipher.doFinalSync(null);
    const headBytes: Uint8Array = head === null || head === undefined ? new Uint8Array(0) : head.data;
    const tailBytes: Uint8Array = tail === null || tail === undefined ? new Uint8Array(0) : tail.data;
    const out: Uint8Array = new Uint8Array(headBytes.length + tailBytes.length);
    out.set(headBytes, 0);
    out.set(tailBytes, headBytes.length);
    return out;
  }

  private randomHex(length: number): string {
    if (length <= 0) {
      return '';
    }
    const bytes: Uint8Array = SDCryptoUtil.randomBytes(Math.ceil(length / 2));
    return SDCryptoUtil.toHex(bytes).substring(0, length);
  }

  // -----------------------------------------------------------------------
  // 公钥解析
  // -----------------------------------------------------------------------

  /** 去掉 PEM 头尾与所有空白。 */
  private compact(text: string): string {
    return text.replace(new RegExp('-----[^-]*-----', 'g'), '').replace(new RegExp('\\s+', 'g'), '');
  }

  private parsePublicKey(publicKey: string): RsaPublicKeyInfo {
    const der: Uint8Array = SDCryptoUtil.fromBase64(this.compact(publicKey));
    if (der.length < 16) {
      throw new Error('invalid RSA public key');
    }
    const outer: DerNode = this.readDer(der, 0);
    if (outer.tag !== 0x30) {
      throw new Error('invalid RSA public key: not a SEQUENCE');
    }
    const first: DerNode = this.readDer(der, outer.contentStart);
    let spki: Uint8Array = der;
    let modulus: DerNode;
    if (first.tag === 0x02) {
      // PKCS#1 RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }
      modulus = first;
      spki = this.wrapPkcs1(der);
    } else {
      // SPKI ::= SEQUENCE { AlgorithmIdentifier, BIT STRING { RSAPublicKey } }
      const bitString: DerNode = this.readDer(der, first.end);
      if (bitString.tag !== 0x03) {
        throw new Error('invalid RSA public key: missing BIT STRING');
      }
      const inner: DerNode = this.readDer(der, bitString.contentStart + 1);
      if (inner.tag !== 0x30) {
        throw new Error('invalid RSA public key: missing RSAPublicKey');
      }
      modulus = this.readDer(der, inner.contentStart);
      if (modulus.tag !== 0x02) {
        throw new Error('invalid RSA public key: missing modulus');
      }
    }
    let modulusBytes: number = modulus.contentLength;
    if (modulusBytes > 0 && der[modulus.contentStart] === 0x00) {
      modulusBytes -= 1;
    }
    const bits: number = modulusBytes * 8;
    return new RsaPublicKeyInfo(spki, SDCryptoModule.RSA_SIZES.indexOf(bits) >= 0 ? bits : 2048);
  }

  private readDer(der: Uint8Array, offset: number): DerNode {
    if (offset + 2 > der.length) {
      throw new Error('invalid DER: truncated');
    }
    const node: DerNode = new DerNode();
    node.tag = der[offset];
    let length: number = der[offset + 1];
    let cursor: number = offset + 2;
    if ((length & 0x80) !== 0) {
      const count: number = length & 0x7f;
      if (count === 0 || count > 4 || cursor + count > der.length) {
        throw new Error('invalid DER: bad length');
      }
      length = 0;
      for (let i = 0; i < count; i++) {
        length = length * 256 + der[cursor + i];
      }
      cursor += count;
    }
    if (cursor + length > der.length) {
      throw new Error('invalid DER: length overflow');
    }
    node.contentStart = cursor;
    node.contentLength = length;
    return node;
  }

  private derLength(length: number): Array<number> {
    if (length < 0x80) {
      return [length];
    }
    const bytes: Array<number> = [];
    let rest: number = length;
    while (rest > 0) {
      bytes.unshift(rest % 256);
      rest = Math.floor(rest / 256);
    }
    bytes.unshift(0x80 | bytes.length);
    return bytes;
  }

  /** PKCS#1 RSAPublicKey → X.509 SubjectPublicKeyInfo */
  private wrapPkcs1(pkcs1: Uint8Array): Uint8Array {
    // AlgorithmIdentifier { rsaEncryption (1.2.840.113549.1.1.1), NULL }
    const algorithm: Array<number> =
      [0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00];
    const bitStringHeader: Array<number> = [0x03].concat(this.derLength(pkcs1.length + 1)).concat([0x00]);
    const bodyLength: number = algorithm.length + bitStringHeader.length + pkcs1.length;
    const header: Array<number> = [0x30].concat(this.derLength(bodyLength));
    const out: Uint8Array = new Uint8Array(header.length + bodyLength);
    let cursor: number = 0;
    out.set(header, cursor);
    cursor += header.length;
    out.set(algorithm, cursor);
    cursor += algorithm.length;
    out.set(bitStringHeader, cursor);
    cursor += bitStringHeader.length;
    out.set(pkcs1, cursor);
    return out;
  }
}
