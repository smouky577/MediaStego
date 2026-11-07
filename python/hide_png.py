"""MediaStego CLI: скриване и извличане в LSB на 8-bit RGB PNG.

Пипат се само каналите R, G, B, ред по ред и пиксел по пиксел.
Alpha се пропуска. JPEG и други формати със загуби се отказват.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image

from stego_core import StegoError, cli_main, embed_carrier, extract_carrier

JPEG_MESSAGE = "JPEG е със загуби — не ползвай като носител, конвертирай в PNG"
PNG_MESSAGE = "PNG трябва да е RGB, 8 бита на канал"
LOSSY_MESSAGE = "Нужен е PNG с 8 бита на канал, не формат със загуби"


def load_rgb(path: str) -> tuple[np.ndarray, tuple[int, ...]]:
    raw = Path(path).read_bytes()
    if raw[:2] == b"\xff\xd8" or path.lower().endswith((".jpg", ".jpeg")):
        raise StegoError(JPEG_MESSAGE)
    try:
        image = Image.open(Path(path))
    except Exception as exc:
        raise StegoError(LOSSY_MESSAGE) from exc
    if image.format != "PNG":
        raise StegoError(LOSSY_MESSAGE)
    if image.mode not in ("RGB", "RGBA"):
        raise StegoError(PNG_MESSAGE)
    array = np.array(image)
    if array.dtype != np.uint8:
        raise StegoError(PNG_MESSAGE)
    if image.mode == "RGBA":
        array = np.ascontiguousarray(array[:, :, :3])
    flat = array.reshape(-1).copy()
    return flat, array.shape


def save_rgb(path: str, samples: np.ndarray, shape: tuple[int, ...]) -> None:
    image = Image.fromarray(np.asarray(samples, dtype=np.uint8).reshape(shape), "RGB")
    image.save(path, format="PNG")


def embed(cover: str, out: str, data: bytes, password: str, salt: bytes | None = None, nonce: bytes | None = None) -> None:
    samples, shape = load_rgb(cover)
    save_rgb(out, embed_carrier(samples, data, password, salt, nonce), shape)


def extract(stego: str, password: str) -> bytes:
    samples, _ = load_rgb(stego)
    return extract_carrier(samples, password)


def main(argv: list[str] | None = None) -> None:
    cli_main(
        embed,
        extract,
        prog="hide_png.py",
        description="Скриване и извличане на криптиран текст или файл в LSB на PNG.",
        argv=argv,
    )


if __name__ == "__main__":
    main()
