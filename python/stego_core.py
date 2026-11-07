"""Общ формат за WAV и PNG: ключ, AES-GCM и позиции в LSB.

Носителят вече е плосък масив s[0..N-1]. WAV подава int16 проби,
PNG подава uint8 стойностите на R, G, B. Пипа се само най-младшият бит.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import struct
import sys

import numpy as np
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HDR_BITS = 160  # salt 16 байта + uint32 дължина
GCM_OVERHEAD = 28  # nonce 12 + tag 16
PBKDF2_ITERS = 200_000


class StegoError(Exception):
    pass


def stdio_utf8() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8")
        except (OSError, ValueError):
            pass


def derive(password: str, salt: bytes) -> tuple[bytes, bytes]:
    key = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PBKDF2_ITERS, 64
    )
    return key[:32], key[32:]


def positions(pkey: bytes, pool: int, nbits: int) -> np.ndarray:
    """Частичен Fisher-Yates. Индексите са в пула [0, pool)."""
    if nbits == 0:
        return np.zeros(0, dtype=np.int64)
    enc = Cipher(algorithms.AES(pkey), modes.CTR(bytes(16))).encryptor()
    raw = enc.update(bytes(4 * nbits)) + enc.finalize()
    if len(raw) != 4 * nbits:
        raise StegoError("Вътрешна грешка в AES-CTR")
    stream = np.frombuffer(raw, dtype=">u4")
    idx = np.arange(pool, dtype=np.int64)
    for i in range(nbits):
        j = i + int(stream[i]) % (pool - i)
        vi = int(idx[i])
        vj = int(idx[j])
        idx[i] = vj
        idx[j] = vi
    return idx[:nbits].copy()


def _apply_bits(samples: np.ndarray, indices: np.ndarray, bits: np.ndarray) -> None:
    idx = np.asarray(indices, dtype=np.int64)
    bit = np.asarray(bits, dtype=np.int32) & 1
    current = samples[idx].astype(np.int32)
    samples[idx] = ((current & ~np.int32(1)) | bit).astype(samples.dtype)


def _read_bits(samples: np.ndarray, indices: np.ndarray) -> np.ndarray:
    idx = np.asarray(indices, dtype=np.int64)
    return (samples[idx].astype(np.int32) & 1).astype(np.uint8)


def _salt_nonce(salt: bytes | None, nonce: bytes | None) -> tuple[bytes, bytes]:
    if salt is None:
        salt = os.urandom(16)
    elif len(salt) != 16:
        raise StegoError("salt трябва да е 16 байта")
    if nonce is None:
        nonce = os.urandom(12)
    elif len(nonce) != 12:
        raise StegoError("nonce трябва да е 12 байта")
    return salt, nonce


def embed_carrier(
    samples: np.ndarray,
    data: bytes,
    password: str,
    salt: bytes | None = None,
    nonce: bytes | None = None,
) -> np.ndarray:
    out = np.array(samples, copy=True)
    if int(out.size) < HDR_BITS:
        raise StegoError("Носителят е твърде къс")
    pool = int(out.size) - HDR_BITS
    need = (len(data) + GCM_OVERHEAD) * 8
    if need > pool:
        raise StegoError(f"Твърде голямо: нужни {need} бита, налични {pool}")

    salt, nonce = _salt_nonce(salt, nonce)
    ekey, pkey = derive(password, salt)
    body = nonce + AESGCM(ekey).encrypt(nonce, data, None)
    if len(body) != len(data) + GCM_OVERHEAD:
        raise StegoError("Вътрешна грешка в AES-GCM")

    header = salt + struct.pack(">I", len(body))
    header_bits = np.unpackbits(np.frombuffer(header, dtype=np.uint8))
    body_bits = np.unpackbits(np.frombuffer(body, dtype=np.uint8))
    _apply_bits(out, np.arange(HDR_BITS, dtype=np.int64), header_bits)
    places = positions(pkey, pool, int(body_bits.size)) + HDR_BITS
    _apply_bits(out, places, body_bits)
    return out


def extract_carrier(samples: np.ndarray, password: str) -> bytes:
    if int(samples.size) < HDR_BITS:
        raise StegoError("Няма съобщение или файлът е повреден")
    header_bits = _read_bits(samples, np.arange(HDR_BITS, dtype=np.int64))
    header = np.packbits(header_bits).tobytes()
    salt = header[:16]
    length = struct.unpack(">I", header[16:20])[0]
    pool = int(samples.size) - HDR_BITS
    if length < GCM_OVERHEAD or length * 8 > pool:
        raise StegoError("Няма съобщение или файлът е повреден")
    ekey, pkey = derive(password, salt)
    places = positions(pkey, pool, length * 8) + HDR_BITS
    body = np.packbits(_read_bits(samples, places)).tobytes()
    try:
        return AESGCM(ekey).decrypt(body[:12], body[12:], None)
    except Exception as exc:
        raise StegoError("Грешна парола или няма съобщение") from exc


def cli_main(embed_fn, extract_fn, prog: str, description: str, argv: list[str] | None = None) -> None:
    stdio_utf8()
    parser = argparse.ArgumentParser(prog=prog, description=description)
    sub = parser.add_subparsers(dest="cmd", required=True)

    embed_p = sub.add_parser("embed")
    embed_p.add_argument("cover")
    embed_p.add_argument("out")
    embed_p.add_argument("password")
    payload = embed_p.add_mutually_exclusive_group(required=True)
    payload.add_argument("-t", "--text")
    payload.add_argument("-f", "--file")

    extract_p = sub.add_parser("extract")
    extract_p.add_argument("stego")
    extract_p.add_argument("password")
    extract_p.add_argument("-o", "--out", default="extracted.bin")

    args = parser.parse_args(argv)
    try:
        if args.cmd == "embed":
            if args.text is not None:
                data = args.text.encode("utf-8")
            else:
                with open(args.file, "rb") as handle:
                    data = handle.read()
            embed_fn(args.cover, args.out, data, args.password)
            print("Готово:", args.out)
            return
        data = extract_fn(args.stego, args.password)
        try:
            print(data.decode("utf-8"))
        except UnicodeDecodeError:
            with open(args.out, "wb") as handle:
                handle.write(data)
            print("Записано в", args.out)
    except StegoError as exc:
        print(exc, file=sys.stderr)
        sys.exit(1)
    except OSError as exc:
        print(exc, file=sys.stderr)
        sys.exit(1)
