"""Генерира WAV и PNG с фиксирани salt/nonce и ги дава на Android кода, после обратно.

Пусни от корена на проекта или оттук:

    python python/test_compat.py
"""

from __future__ import annotations

import hashlib
import os
import shutil
import struct
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hide import (  # noqa: E402
    HDR_BITS,
    StegoError,
    derive,
    embed,
    extract,
    positions,
    read_wav,
    write_wav,
)
from hide_png import JPEG_MESSAGE, PNG_MESSAGE, embed as embed_png, extract as extract_png, load_rgb

ROOT = Path(__file__).resolve().parents[1]
TESTDATA = ROOT / "testdata"
ANDROID = ROOT / "android"
ASSETS = ANDROID / "app" / "src" / "androidTest" / "assets"
JBR = Path(r"C:\Program Files\Android\Android Studio\jbr")

PASSWORD = "Тайна-парола"
WRONG = "грешна"
SALT = bytes(range(16))
NONCE = bytes(range(0x20, 0x2C))
TEXT = "Скрито съобщение ✓ AudioStego"
TOO_BIG = "Твърде голямо: нужни 408 бита, налични 400"
BAD_PASSWORD = "Грешна парола или няма съобщение"
NOT_PCM = "Нужен е 16-bit PCM WAV"


def _stdio_utf8() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8")
        except (OSError, ValueError):
            pass


def samples(n: int, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return rng.integers(-32768, 32768, size=n, dtype=np.int32).astype(np.int16)


def sparse_positions(pkey: bytes, pool: int, nbits: int) -> np.ndarray:
    """Същият частичен Fisher-Yates, но с речник. Android го ползва при дълъг носител."""
    enc = Cipher(algorithms.AES(pkey), modes.CTR(bytes(16))).encryptor()
    raw = enc.update(bytes(4 * nbits)) + enc.finalize()
    if len(raw) != 4 * nbits:
        raise SystemExit("CTR дължината не съвпада")
    over: dict[int, int] = {}
    out = np.empty(nbits, dtype=np.int64)
    for i in range(nbits):
        value = int.from_bytes(raw[4 * i : 4 * i + 4], "big")
        j = i + value % (pool - i)
        vi = over[i] if i in over else i
        vj = over[j] if j in over else j
        over[i] = vj
        over[j] = vi
        out[i] = vj
    return out


def write_junk(path: Path, pcm: np.ndarray, rate: int, channels: int) -> None:
    data = pcm.astype("<i2").tobytes()
    fmt = struct.pack("<HHLLHH", 1, channels, rate, rate * channels * 2, channels * 2, 16)

    def chunk(tag: bytes, payload: bytes) -> bytes:
        pad = b"\x00" if len(payload) % 2 else b""
        return tag + struct.pack("<I", len(payload)) + payload + pad

    body = b"WAVE" + chunk(b"JUNK", b"HELLO") + chunk(b"fmt ", fmt) + chunk(b"data", data)
    path.write_bytes(b"RIFF" + struct.pack("<I", len(body)) + body)
    got = read_wav(str(path))
    if got.channels != channels or got.rate != rate or not np.array_equal(got.samples, pcm):
        raise SystemExit("JUNK носителят не се чете от wave")


def write_8bit(path: Path) -> None:
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(1)
        handle.setframerate(8000)
        handle.writeframes(bytes(range(200)))


def expect_error(fn, message: str) -> None:
    try:
        fn()
    except StegoError as exc:
        if str(exc) != message:
            raise SystemExit(f"очакван текст [{message}], получен [{exc}]")
        return
    raise SystemExit(f"очакваше се грешка: {message}")


def lsb_intact(before: np.ndarray, after_path: Path) -> None:
    after = read_wav(str(after_path)).samples
    mask = np.int16(~np.int16(1))
    if not np.array_equal(before & mask, after & mask):
        raise SystemExit(f"променени са битове над LSB в {after_path.name}")


def case(name: str, pcm: np.ndarray, rate: int, channels: int, payload: bytes, junk: bool = False) -> None:
    cover = TESTDATA / f"cover_{name}.wav"
    stego = TESTDATA / f"from_python_{name}.wav"
    blob = TESTDATA / f"payload_{name}.bin"
    blob.write_bytes(payload)
    if junk:
        write_junk(cover, pcm, rate, channels)
    else:
        write_wav(str(cover), pcm, rate, channels)
    embed(str(cover), str(stego), payload, PASSWORD, SALT, NONCE)
    got = extract(str(stego), PASSWORD)
    if got != payload:
        raise SystemExit(f"{name}: Python extract не върна същия payload")
    lsb_intact(pcm, stego)
    raw = stego.read_bytes()
    if payload in raw or SALT in raw or NONCE in raw:
        raise SystemExit(f"{name}: payload или ключът се виждат в WAV като обикновени байтове")
    _, pkey = derive(PASSWORD, SALT)
    pool = int(pcm.size) - HDR_BITS
    nbits = (len(payload) + 28) * 8
    full = positions(pkey, pool, nbits)
    sparse = sparse_positions(pkey, pool, nbits)
    if not np.array_equal(full, sparse):
        raise SystemExit(f"{name}: sparse Fisher-Yates се разминава с пълния")


def write_rgb(path: Path, image: np.ndarray) -> None:
    height, width, channels = image.shape
    if channels != 3:
        raise SystemExit("RGB файлът трябва да е 3 канала")
    path.write_bytes(struct.pack("<II", width, height) + np.ascontiguousarray(image, dtype=np.uint8).tobytes())


def case_png() -> None:
    image = np.random.default_rng(11).integers(0, 256, size=(130, 180, 3), dtype=np.uint8)
    payload = "PNG скрито ✓".encode("utf-8")
    cover = TESTDATA / "cover_png.png"
    stego = TESTDATA / "from_python_png.png"
    (TESTDATA / "payload_png.bin").write_bytes(payload)
    Image.fromarray(image, "RGB").save(cover, format="PNG")
    loaded, shape = load_rgb(str(cover))
    if shape != image.shape or not np.array_equal(loaded, image.reshape(-1)):
        raise SystemExit("Pillow не върна същите RGB пиксели")
    embed_png(str(cover), str(stego), payload, PASSWORD, SALT, NONCE)
    if extract_png(str(stego), PASSWORD) != payload:
        raise SystemExit("png: Python extract не върна същия payload")
    stego_pixels, stego_shape = load_rgb(str(stego))
    if stego_shape != image.shape:
        raise SystemExit("png: размерът на картината се смени")
    if not np.array_equal(image.reshape(-1) & np.uint8(0xFE), stego_pixels & np.uint8(0xFE)):
        raise SystemExit("png: променени са битове над LSB")
    raw = stego.read_bytes()
    if payload in raw or SALT in raw or NONCE in raw:
        raise SystemExit("png: payload или ключът се виждат в PNG като обикновени байтове")
    _, pkey = derive(PASSWORD, SALT)
    pool = int(image.size) - HDR_BITS
    nbits = (len(payload) + 28) * 8
    if not np.array_equal(positions(pkey, pool, nbits), sparse_positions(pkey, pool, nbits)):
        raise SystemExit("png: sparse Fisher-Yates се разминава с пълния")
    write_rgb(TESTDATA / "cover_png.rgb", image)
    write_rgb(TESTDATA / "from_python_png.rgb", stego_pixels.reshape(image.shape))


def prepare() -> None:
    if TESTDATA.exists():
        shutil.rmtree(TESTDATA)
    TESTDATA.mkdir()
    case("mono", samples(80_000, 1), 16_000, 1, TEXT.encode("utf-8"))
    case("stereo", samples(30_000, 2), 44_100, 2, bytes(range(256)) + b"\xff\xfe")
    case("tight", samples(560, 3), 8_000, 1, bytes([0xA5]) * 22)
    case("junk", samples(4_000, 4), 22_050, 1, "Чънк".encode("utf-8"), junk=True)
    write_8bit(TESTDATA / "cover_8bit.wav")
    (TESTDATA / "not_wav.bin").write_bytes(b"this is not a wav file at all")
    case_png()
    Image.fromarray(np.zeros((8, 8, 3), dtype=np.uint8), "RGB").save(TESTDATA / "cover.jpg", format="JPEG")
    Image.fromarray(np.zeros((8, 8), dtype=np.uint8), "L").save(TESTDATA / "cover_gray.png", format="PNG")
    expect_error(lambda: load_rgb(str(TESTDATA / "cover.jpg")), JPEG_MESSAGE)
    expect_error(lambda: load_rgb(str(TESTDATA / "cover_gray.png")), PNG_MESSAGE)

    expect_error(
        lambda: embed(
            str(TESTDATA / "cover_tight.wav"),
            str(TESTDATA / "overflow.wav"),
            bytes([0xA5]) * 23,
            PASSWORD,
            SALT,
            NONCE,
        ),
        TOO_BIG,
    )
    expect_error(lambda: extract(str(TESTDATA / "from_python_mono.wav"), WRONG), BAD_PASSWORD)
    expect_error(lambda: read_wav(str(TESTDATA / "cover_8bit.wav")), NOT_PCM)
    expect_error(lambda: read_wav(str(TESTDATA / "not_wav.bin")), NOT_PCM)
    digest = hashlib.sha256((TESTDATA / "from_python_mono.wav").read_bytes()).hexdigest()
    print("Python векторите са готови.", digest[:16])


def copy_assets() -> None:
    if ASSETS.exists():
        shutil.rmtree(ASSETS)
    ASSETS.mkdir(parents=True)
    names = [
        "cover_mono.wav",
        "from_python_mono.wav",
        "payload_mono.bin",
        "cover_stereo.wav",
        "from_python_stereo.wav",
        "payload_stereo.bin",
        "cover_tight.wav",
        "from_python_tight.wav",
        "payload_tight.bin",
        "cover_junk.wav",
        "from_python_junk.wav",
        "payload_junk.bin",
        "cover_8bit.wav",
        "not_wav.bin",
        "cover_png.png",
        "from_python_png.png",
        "payload_png.bin",
        "cover_png.rgb",
        "from_python_png.rgb",
        "cover.jpg",
        "cover_gray.png",
    ]
    for name in names:
        shutil.copy(TESTDATA / name, ASSETS / name)


def gradle_env() -> dict[str, str]:
    env = os.environ.copy()
    java_home = env.get("JAVA_HOME", "")
    if not java_home and JBR.is_dir():
        env["JAVA_HOME"] = str(JBR)
    return env


def run_gradle(args: list[str]) -> None:
    cmd = ["cmd", "/c", "gradlew.bat", *args, "--console=plain"]
    print("Gradle:", " ".join(args))
    subprocess.run(cmd, cwd=ANDROID, env=gradle_env(), check=True)


def verify_kotlin() -> None:
    for name in ("mono", "stereo", "tight", "junk"):
        python_wav = (TESTDATA / f"from_python_{name}.wav").read_bytes()
        kotlin_path = TESTDATA / f"from_kotlin_{name}.wav"
        if not kotlin_path.is_file():
            raise SystemExit(f"Android тестът не записа {kotlin_path.name}")
        kotlin_wav = kotlin_path.read_bytes()
        if python_wav != kotlin_wav:
            limit = min(len(python_wav), len(kotlin_wav))
            at = next((i for i in range(limit) if python_wav[i] != kotlin_wav[i]), limit)
            raise SystemExit(
                f"{name}: WAV байтовете се разминават при {at} "
                f"(python {len(python_wav)}, kotlin {len(kotlin_wav)})"
            )
        payload = (TESTDATA / f"payload_{name}.bin").read_bytes()
        if extract(str(kotlin_path), PASSWORD) != payload:
            raise SystemExit(f"{name}: Python не извлече файла, записан от Android кода")
    kotlin_png = TESTDATA / "from_kotlin_png.png"
    if not kotlin_png.is_file():
        raise SystemExit("Android тестът не записа from_kotlin_png.png")
    expected, _ = load_rgb(str(TESTDATA / "from_python_png.png"))
    actual, _ = load_rgb(str(kotlin_png))
    if not np.array_equal(expected, actual):
        raise SystemExit("png: пикселите на Python и Android се разминават")
    payload = (TESTDATA / "payload_png.bin").read_bytes()
    if extract_png(str(kotlin_png), PASSWORD) != payload:
        raise SystemExit("png: Python не извлече картината, записана от Android кода")
    print("Съвместимост: Python и Android съвпадат за WAV (байтове) и PNG (пиксели), и в двете посоки.")


def main() -> None:
    _stdio_utf8()
    prepare()
    copy_assets()
    run_gradle([":app:testDebugUnitTest", "--tests", "com.audiostego.stego.CompatTest"])
    verify_kotlin()


if __name__ == "__main__":
    main()
