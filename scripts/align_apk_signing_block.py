#!/usr/bin/env python3
"""把 Walle 写渠道后撑大的 APK Signing Block 缩回 4096 对齐。

母包签名时已经带了一块 padding（id 0x42726577），整块大小是 4096 的整数倍。
Walle 1.1.6 只追加渠道块，不缩小这块 padding，中央目录和 EOCD 会跟着后移。
v2 签名不覆盖 Signing Block，但覆盖中央目录和 EOCD。
多出来的字节只能从 padding 末尾扣掉，目录才能回到签名时的偏移。
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

_EOCD_SIG = b"PK\x05\x06"
_MAGIC = b"APK Sig Block 42"
_PADDING_ID = 0x42726577
_PAGE = 4096


def _find_eocd(data: bytes | bytearray) -> int:
    # 注释最长 65535，只在文件尾这一段里找，避免扫到压缩数据里的偶然字节。
    window = data[max(0, len(data) - (65535 + 22)) :]
    rel = window.rfind(_EOCD_SIG)
    if rel < 0:
        raise ValueError("找不到 EOCD")
    return max(0, len(data) - (65535 + 22)) + rel


def _signing_block(data: bytes | bytearray) -> tuple[int, int, int, int]:
    eocd = _find_eocd(data)
    if eocd + 22 > len(data):
        raise ValueError("EOCD 被截断")
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    magic_pos = cd_off - 16
    if magic_pos < 8 or data[magic_pos : magic_pos + 16] != _MAGIC:
        raise ValueError("中央目录前没有 APK Signing Block")
    size_footer = struct.unpack_from("<Q", data, magic_pos - 8)[0]
    total = size_footer + 8
    block_off = cd_off - total
    if block_off < 0 or block_off + 8 > len(data):
        raise ValueError("签名块偏移越界")
    size_header = struct.unpack_from("<Q", data, block_off)[0]
    if size_header != size_footer:
        raise ValueError(f"签名块前后 size 不一致: {size_header} != {size_footer}")
    return eocd, block_off, total, magic_pos


def _find_padding(data: bytes | bytearray, block_off: int, magic_pos: int) -> tuple[int, int]:
    pos = block_off + 8
    end = magic_pos - 8
    found: tuple[int, int] | None = None
    while pos + 12 <= end:
        pair_len = struct.unpack_from("<Q", data, pos)[0]
        if pair_len < 4 or pos + 8 + pair_len > end:
            raise ValueError("签名块里的 id-value 被截断")
        pair_id = struct.unpack_from("<I", data, pos + 8)[0]
        if pair_id == _PADDING_ID:
            found = (pos, pair_len)
        pos += 8 + pair_len
    if pos != end:
        raise ValueError("签名块 id-value 没有排到 footer")
    if found is None:
        raise ValueError("签名块里没有 padding，无法在不重签的情况下对齐")
    return found


def align_apk(path: Path, check_only: bool = False) -> int:
    """返回扣掉的字节数。已经对齐时返回 0。"""
    data = bytearray(path.read_bytes())
    eocd, block_off, total, magic_pos = _signing_block(data)
    excess = total % _PAGE
    if excess == 0:
        return 0
    pad_pos, pad_len = _find_padding(data, block_off, magic_pos)
    # pair_len 含 4 字节 id。值至少还要留下 0 字节，不能把 id 一起裁掉。
    if pad_len - 4 < excess:
        raise ValueError(f"padding 只剩 {pad_len - 4} 字节，不够扣掉 {excess} 字节")
    if check_only:
        raise ValueError(f"签名块大小 {total} 不是 {_PAGE} 的整数倍")

    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    pair_end = pad_pos + 8 + pad_len
    # padding 在中央目录之前，删掉多余字节后目录和 EOCD 一起前移。
    del data[pair_end - excess : pair_end]
    struct.pack_into("<Q", data, pad_pos, pad_len - excess)
    new_size = total - excess - 8
    struct.pack_into("<Q", data, block_off, new_size)
    struct.pack_into("<Q", data, magic_pos - excess - 8, new_size)
    struct.pack_into("<I", data, eocd - excess + 16, cd_off - excess)

    _new_eocd, new_block, new_total, _new_magic = _signing_block(data)
    if new_block != block_off or new_total % _PAGE != 0:
        raise ValueError(f"对齐后签名块仍异常: total={new_total}")
    path.write_bytes(data)
    return excess


def main() -> int:
    parser = argparse.ArgumentParser(description="把 APK Signing Block 缩回 4096 对齐")
    parser.add_argument("apk", type=Path)
    parser.add_argument("--check", action="store_true", help="只检查，不对齐时以非 0 退出")
    args = parser.parse_args()
    if not args.apk.is_file():
        print(f"Error: apk 不存在: {args.apk}", file=sys.stderr)
        return 1
    try:
        removed = align_apk(args.apk, check_only=args.check)
    except ValueError as exc:
        print(f"Error: {args.apk}: {exc}", file=sys.stderr)
        return 1
    if removed:
        print(f"{args.apk}: 从 padding 扣掉 {removed} 字节，签名块已按 {_PAGE} 对齐")
    else:
        print(f"{args.apk}: 签名块已是 {_PAGE} 对齐")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
