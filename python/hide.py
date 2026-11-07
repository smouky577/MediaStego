"""MediaStego CLI: скриване и извличане в LSB на 16-bit PCM WAV."""

from __future__ import annotations

import sys
import wave
from collections import namedtuple

import numpy as np

from stego_core import (
    HDR_BITS,
    GCM_OVERHEAD,
    StegoError,
    cli_main,
    derive,
    embed_carrier,
    extract_carrier,
    positions,
)

__all__ = [
    "HDR_BITS",
    "GCM_OVERHEAD",
    "StegoError",
    "derive",
    "embed",
    "embed_pcm",
    "extract",
    "positions",
    "read_wav",
    "write_wav",
]

Wav = namedtuple("Wav", "channels rate samples")


def read_wav(path: str) -> Wav:
    try:
        with wave.open(path, "rb") as handle:
            if handle.getsampwidth() != 2 or handle.getcomptype() != "NONE":
                raise StegoError("Нужен е 16-bit PCM WAV")
            channels = handle.getnchannels()
            rate = handle.getframerate()
            frames = handle.readframes(handle.getnframes())
    except StegoError:
        raise
    except wave.Error as exc:
        raise StegoError("Нужен е 16-bit PCM WAV") from exc
    samples = np.frombuffer(frames, dtype="<i2").copy()
    if channels < 1 or samples.size % channels != 0:
        raise StegoError("Нужен е 16-bit PCM WAV")
    return Wav(channels, rate, samples)


def write_wav(path: str, samples: np.ndarray, rate: int, channels: int) -> None:
    data = np.asarray(samples, dtype=np.int16)
    if data.size % channels != 0:
        raise StegoError("Броят проби не се дели на каналите")
    with wave.open(path, "wb") as handle:
        handle.setnchannels(channels)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.setnframes(data.size // channels)
        handle.writeframes(data.astype("<i2").tobytes())


def embed_pcm(wav: Wav, data: bytes, password: str, salt: bytes | None = None, nonce: bytes | None = None) -> Wav:
    if int(wav.samples.size) < HDR_BITS:
        raise StegoError("Нужен е по-дълъг 16-bit PCM WAV")
    samples = embed_carrier(wav.samples, data, password, salt, nonce)
    return Wav(wav.channels, wav.rate, samples)


def embed(cover: str, out: str, data: bytes, password: str, salt: bytes | None = None, nonce: bytes | None = None) -> None:
    wav = read_wav(cover)
    result = embed_pcm(wav, data, password, salt, nonce)
    write_wav(out, result.samples, result.rate, result.channels)


def extract(stego: str, password: str) -> bytes:
    return extract_carrier(read_wav(stego).samples, password)


def main(argv: list[str] | None = None) -> None:
    cli_main(
        embed,
        extract,
        prog="hide.py",
        description="Скриване и извличане на криптиран текст или файл в LSB на WAV.",
        argv=argv,
    )


if __name__ == "__main__":
    main()
