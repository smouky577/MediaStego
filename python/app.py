"""Прозорец за скриване и извличане. Пуска се с двоен клик върху MediaStego."""

from __future__ import annotations

import ctypes
import io
import threading
import time
import tkinter as tk
import wave
from ctypes import wintypes
from pathlib import Path
from tkinter import filedialog, ttk

from hide import embed as embed_wav
from hide import extract as extract_wav
from hide_png import JPEG_MESSAGE
from hide_png import embed as embed_png
from hide_png import extract as extract_png
from stego_core import StegoError

ABOUT_EN = "Donate to Dr. Baysinger. He promises to spend it on hookers and beer."
ABOUT_BG = "Дарете на Dr. Baysinger. Той обещава да ги изхарчи за курви и бело."
REVOLUT = "revolut.me/georgi09qc"
BTC = (
    "bc1qr84akpfry7sjs6759wwq722fyert9apcarpst6",
    "bc1p6hp5q20u9t9upsjt7425wgqdrs0cr8wnnlf9atu24z92pzsnq85q62uc8e",
)
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
BG = "#14160F"
RAISED = "#22261B"
FIELD = "#1c2118"
TEXT = "#F4F1E6"
MUTED = "#C9C4B4"
ACCENT = "#E4A04A"
LINE = "#3A4030"
CLAY = "#D46A58"
WHDR_DONE = 0x00000001
WAVE_MAPPER = 0xFFFFFFFF


class WAVEFORMATEX(ctypes.Structure):
    _fields_ = (
        ("wFormatTag", wintypes.WORD),
        ("nChannels", wintypes.WORD),
        ("nSamplesPerSec", wintypes.DWORD),
        ("nAvgBytesPerSec", wintypes.DWORD),
        ("nBlockAlign", wintypes.WORD),
        ("wBitsPerSample", wintypes.WORD),
        ("cbSize", wintypes.WORD),
    )


class WAVEHDR(ctypes.Structure):
    _fields_ = (
        ("lpData", ctypes.c_void_p),
        ("dwBufferLength", wintypes.DWORD),
        ("dwBytesRecorded", wintypes.DWORD),
        ("dwUser", ctypes.c_size_t),
        ("dwFlags", wintypes.DWORD),
        ("dwLoops", wintypes.DWORD),
        ("lpNext", ctypes.c_void_p),
        ("reserved", ctypes.c_size_t),
    )


class MicError(Exception):
    pass


class MicRecorder:
    """Кратък глас като WAV, 16 kHz, моно. После се крие като файл."""

    def __init__(self) -> None:
        self.winmm = ctypes.WinDLL("winmm")
        self.winmm.waveInGetNumDevs.restype = wintypes.UINT
        self.winmm.waveInOpen.argtypes = [
            ctypes.POINTER(wintypes.HANDLE),
            wintypes.UINT,
            ctypes.POINTER(WAVEFORMATEX),
            ctypes.c_void_p,
            ctypes.c_void_p,
            wintypes.DWORD,
        ]
        self.winmm.waveInOpen.restype = wintypes.UINT
        header_args = [wintypes.HANDLE, ctypes.POINTER(WAVEHDR), wintypes.UINT]
        for name in ("waveInPrepareHeader", "waveInUnprepareHeader", "waveInAddBuffer"):
            getattr(self.winmm, name).argtypes = header_args
            getattr(self.winmm, name).restype = wintypes.UINT
        for name in ("waveInStart", "waveInStop", "waveInReset", "waveInClose"):
            getattr(self.winmm, name).argtypes = [wintypes.HANDLE]
            getattr(self.winmm, name).restype = wintypes.UINT
        self.hwave = wintypes.HANDLE()
        self.slots: list[tuple[WAVEHDR, ctypes.Array]] = []
        self.chunks: list[bytes] = []
        self.open = False

    def start(self) -> None:
        self.close()
        if self.winmm.waveInGetNumDevs() < 1:
            raise MicError("mic")
        fmt = WAVEFORMATEX(1, 1, 16000, 32000, 2, 16, 0)
        result = self.winmm.waveInOpen(
            ctypes.byref(self.hwave),
            WAVE_MAPPER,
            ctypes.byref(fmt),
            None,
            None,
            0,
        )
        if result != 0:
            raise MicError("start")
        self.open = True
        self.chunks = []
        for _ in range(6):
            raw = ctypes.create_string_buffer(3200)
            header = WAVEHDR()
            header.lpData = ctypes.addressof(raw)
            header.dwBufferLength = 3200
            if self.winmm.waveInPrepareHeader(self.hwave, ctypes.byref(header), ctypes.sizeof(header)) != 0:
                self.close()
                raise MicError("start")
            if self.winmm.waveInAddBuffer(self.hwave, ctypes.byref(header), ctypes.sizeof(header)) != 0:
                self.close()
                raise MicError("start")
            self.slots.append((header, raw))
        if self.winmm.waveInStart(self.hwave) != 0:
            self.close()
            raise MicError("start")

    def drain(self, requeue: bool) -> None:
        if not self.open:
            return
        for header, raw in self.slots:
            if not header.dwFlags & WHDR_DONE:
                continue
            count = int(header.dwBytesRecorded)
            if count:
                self.chunks.append(bytes(raw[:count]))
            header.dwBytesRecorded = 0
            header.dwFlags &= ~WHDR_DONE
            if requeue:
                self.winmm.waveInAddBuffer(self.hwave, ctypes.byref(header), ctypes.sizeof(header))

    def stop(self) -> bytes:
        if not self.open:
            raise MicError("empty")
        self.winmm.waveInStop(self.hwave)
        self.winmm.waveInReset(self.hwave)
        self.drain(False)
        pcm = b"".join(self.chunks)
        self.chunks = []
        self.close()
        if len(pcm) < 1600:
            raise MicError("short")
        output = io.BytesIO()
        with wave.open(output, "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(16000)
            handle.writeframes(pcm)
        return output.getvalue()

    def close(self) -> None:
        if not self.open:
            self.slots = []
            return
        for header, _raw in self.slots:
            self.winmm.waveInUnprepareHeader(self.hwave, ctypes.byref(header), ctypes.sizeof(header))
        self.winmm.waveInClose(self.hwave)
        self.hwave = wintypes.HANDLE()
        self.slots = []
        self.open = False


def format_size(size: int) -> str:
    if size < 1024:
        return f"{size} B"
    if size < 1024 * 1024:
        return f"{size / 1024:.0f} KB"
    return f"{size / (1024 * 1024):.1f} MB"


def shown(english: bool, text: str) -> str:
    if not english or not text:
        return text
    phrases = {
        "Грешна парола или няма съобщение": "Wrong password or no message",
        "Няма съобщение или файлът е повреден": "No message, or the file is damaged",
        "Носителят е твърде къс": "The carrier is too short",
        "Нужен е по-дълъг 16-bit PCM WAV": "A longer 16-bit PCM WAV is required",
        "JPEG е със загуби — не ползвай като носител, конвертирай в PNG": "JPEG is lossy — do not use it as a carrier, convert it to PNG",
        "PNG трябва да е RGB, 8 бита на канал": "PNG must be RGB, 8 bits per channel",
        "Нужен е PNG с 8 бита на канал, не формат със загуби": "A PNG with 8 bits per channel is required, not a lossy format",
        "Избери WAV или PNG файл": "Choose a WAV or PNG file",
    }
    if text in phrases:
        return phrases[text]
    if text.startswith("Твърде голямо:"):
        return "Too big: " + text.split(":", 1)[1].replace("нужни", "need").replace("бита", "bits").replace("налични", "available")
    return text


def show_about(root: tk.Misc) -> None:
    window = tk.Toplevel(root)
    window.title("About")
    window.configure(bg=BG)
    window.resizable(False, False)
    window.transient(root)
    frame = tk.Frame(window, bg=BG, padx=18, pady=16)
    frame.pack(fill="both", expand=True)
    tk.Label(frame, text="About", bg=BG, fg=TEXT, font=("Segoe UI", 16, "bold"), anchor="w").pack(fill="x")
    tk.Label(
        frame, text=ABOUT_EN, bg=BG, fg=TEXT, font=("Segoe UI", 11), wraplength=640, justify="left", anchor="w"
    ).pack(fill="x", pady=(16, 8))
    tk.Label(
        frame, text=ABOUT_BG, bg=BG, fg=TEXT, font=("Segoe UI", 11), wraplength=640, justify="left", anchor="w"
    ).pack(fill="x")
    tk.Frame(frame, bg=LINE, height=1).pack(fill="x", pady=14)
    tk.Label(frame, text="Revolut", bg=BG, fg=TEXT, font=("Segoe UI", 12, "bold"), anchor="w").pack(fill="x")
    note = tk.Label(frame, text="", bg=BG, fg=ACCENT, font=("Segoe UI", 9), anchor="w")

    def copy(value: str) -> None:
        root.clipboard_clear()
        root.clipboard_append(value)
        note.configure(text="Copied")

    def link(value: str) -> None:
        label = tk.Label(
            frame, text=value, bg=BG, fg=ACCENT, font=("Segoe UI", 10), cursor="hand2", anchor="w", justify="left"
        )
        label.pack(fill="x", pady=(6, 0))
        label.bind("<Button-1>", lambda _event: copy(value))

    link(REVOLUT)
    tk.Label(frame, text="BTC address", bg=BG, fg=TEXT, font=("Segoe UI", 12, "bold"), anchor="w").pack(
        fill="x", pady=(16, 0)
    )
    for address in BTC:
        link(address)
    note.pack(fill="x", pady=(14, 0))
    window.update_idletasks()
    width, height = 700, window.winfo_reqheight()
    parent_x = root.winfo_rootx() + max(0, (root.winfo_width() - width) // 2)
    parent_y = root.winfo_rooty() + max(0, (root.winfo_height() - height) // 2)
    window.geometry(f"{width}x{height}+{parent_x}+{parent_y}")
    window.grab_set()
    window.focus_set()


def dark_caption(window: tk.Misc) -> None:
    try:
        window.update_idletasks()
        hwnd = ctypes.windll.user32.GetAncestor(window.winfo_id(), 2)
        value = ctypes.c_int(1)
        ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(value), ctypes.sizeof(value))
    except Exception:
        return


def apply_theme(root: tk.Tk) -> None:
    root.configure(bg=BG)
    dark_caption(root)
    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure(
        ".",
        background=BG,
        foreground=TEXT,
        fieldbackground=FIELD,
        bordercolor=LINE,
        lightcolor=LINE,
        darkcolor=LINE,
        font=("Segoe UI", 11),
    )
    style.configure("TFrame", background=BG)
    style.configure("TLabel", background=BG, foreground=TEXT)
    style.configure("TButton", background=RAISED, foreground=TEXT, borderwidth=0, padding=(10, 7), focuscolor=ACCENT)
    style.map(
        "TButton",
        background=[("pressed", ACCENT), ("active", "#3a3324"), ("disabled", "#1a1c16")],
        foreground=[("pressed", BG), ("disabled", "#6e6a60")],
    )
    style.configure("TCheckbutton", background=BG, foreground=TEXT)
    style.map(
        "TCheckbutton",
        background=[("active", BG)],
        foreground=[("active", TEXT)],
        indicatorcolor=[("selected", ACCENT), ("!selected", RAISED)],
    )
    style.configure("TEntry", fieldbackground=FIELD, foreground=TEXT, bordercolor=LINE, lightcolor=LINE, darkcolor=LINE)
    style.configure("TNotebook", background=BG, borderwidth=0, bordercolor=BG, lightcolor=BG, darkcolor=BG)
    style.configure("TNotebook.Tab", background=RAISED, foreground=MUTED, padding=(16, 8), borderwidth=0)
    style.map(
        "TNotebook.Tab",
        background=[("selected", BG), ("active", FIELD)],
        foreground=[("selected", ACCENT), ("active", TEXT)],
    )


def dark_text(parent: tk.Misc, **options) -> tk.Text:
    options.setdefault("bg", FIELD)
    options.setdefault("fg", TEXT)
    options.setdefault("insertbackground", TEXT)
    options.setdefault("relief", "flat")
    options.setdefault("highlightthickness", 1)
    options.setdefault("highlightbackground", LINE)
    options.setdefault("highlightcolor", ACCENT)
    options.setdefault("selectbackground", ACCENT)
    options.setdefault("selectforeground", BG)
    options.setdefault("font", ("Segoe UI", 12))
    return tk.Text(parent, **options)


def carrier_kind(path: Path) -> str:
    with path.open("rb") as handle:
        raw = handle.read(8)
    if raw[:2] == b"\xff\xd8" or path.suffix.lower() in {".jpg", ".jpeg"}:
        raise StegoError(JPEG_MESSAGE)
    if raw.startswith(PNG_MAGIC):
        return "png"
    if raw[:4] == b"RIFF":
        return "wav"
    raise StegoError("Избери WAV или PNG файл")


def hide_file(cover: Path, out: Path, data: bytes, password: str) -> None:
    if carrier_kind(cover) == "png":
        embed_png(str(cover), str(out), data, password)
    else:
        embed_wav(str(cover), str(out), data, password)


def extract_file(path: Path, password: str) -> tuple[str | None, Path | None]:
    if carrier_kind(path) == "png":
        data = extract_png(str(path), password)
    else:
        data = extract_wav(str(path), password)
    try:
        return data.decode("utf-8"), None
    except UnicodeDecodeError:
        return None, save_beside(path, data)


def save_beside(source: Path, data: bytes) -> Path:
    if data.startswith(b"OggS"):
        ext = ".ogg"
    elif data.startswith(b"%PDF"):
        ext = ".pdf"
    elif data.startswith(PNG_MAGIC):
        ext = ".png"
    elif data.startswith(b"\xff\xd8"):
        ext = ".jpg"
    elif data.startswith(b"RIFF") and data[8:12] == b"WAVE":
        ext = ".wav"
    else:
        ext = ".bin"
    target = source.with_name(source.stem + "-извлечено" + ext)
    number = 2
    while target.exists():
        target = source.with_name(f"{source.stem}-извлечено-{number}{ext}")
        number += 1
    target.write_bytes(data)
    return target


class MediaWindow:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title("MediaStego")
        self.root.geometry("640x740")
        self.root.minsize(520, 640)
        apply_theme(self.root)
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.busy = False
        self.english = False
        self.cover: Path | None = None
        self.payload_path: Path | None = None
        self.stego: Path | None = None
        self.hide_idle = True
        self.extract_idle = True
        self.mode = "text"
        self.voice_bytes: bytes | None = None
        self.voice_recording = False
        self.record_started = 0.0
        self.recorder = MicRecorder()

        outer = ttk.Frame(self.root, padding=16)
        outer.pack(fill="both", expand=True)
        outer.rowconfigure(2, weight=1)
        outer.columnconfigure(0, weight=1)

        header = tk.Frame(outer, bg=BG)
        header.grid(row=0, column=0, sticky="ew", pady=(0, 10))
        title_box = tk.Frame(header, bg=BG)
        title_box.pack(side="left", anchor="w")
        tk.Label(title_box, text="MediaStego", bg=BG, fg=ACCENT, font=("Segoe UI", 18, "bold")).pack(side="left")
        mark = tk.Frame(header, bg=BG)
        mark.pack(side="right", anchor="e")
        credit = tk.Label(
            mark,
            text="by Dr. Baysinger",
            bg=BG,
            fg=MUTED,
            font=("Segoe UI", 9),
            cursor="hand2",
        )
        credit.pack(side="right")
        credit.bind("<Button-1>", lambda _event: show_about(self.root))
        self.flag = tk.Canvas(
            mark, width=28, height=18, highlightthickness=0, bd=0, cursor="hand2", bg=BG,
        )
        self.flag.pack(side="left", padx=(0, 8))
        self.flag.bind("<Button-1>", lambda _event: self.toggle_language())
        self.draw_flag()

        tabs = tk.Frame(outer, bg=BG)
        tabs.grid(row=1, column=0, sticky="ew", pady=(0, 8))
        tabs.columnconfigure(0, weight=1, uniform="tabs")
        tabs.columnconfigure(1, weight=1, uniform="tabs")
        self.page = "hide"
        self.hide_box, self.hide_tab = self._tab(tabs, 0, self.show_hide, (0, 5))
        self.extract_box, self.extract_tab = self._tab(tabs, 1, self.show_extract, (5, 0))

        self.pages = tk.Frame(outer, bg=BG)
        self.pages.grid(row=2, column=0, sticky="nsew")
        self.pages.rowconfigure(0, weight=1)
        self.pages.columnconfigure(0, weight=1)
        hide = ttk.Frame(self.pages, padding=8)
        extract = ttk.Frame(self.pages, padding=8)
        hide.grid(row=0, column=0, sticky="nsew")
        extract.grid(row=0, column=0, sticky="nsew")
        self.hide_page = hide
        self.extract_page = extract
        self._build_hide(hide)
        self._build_extract(extract)
        self.apply_language()
        self.show_hide()
        dark_caption(self.root)

    def _tab(
        self,
        parent: tk.Frame,
        column: int,
        command,
        padx: tuple[int, int],
    ) -> tuple[tk.Frame, tk.Label]:
        box = tk.Frame(
            parent,
            bg=RAISED,
            highlightthickness=2,
            highlightbackground=LINE,
            highlightcolor=LINE,
            cursor="hand2",
        )
        box.grid(row=0, column=column, sticky="nsew", padx=padx)
        label = tk.Label(
            box,
            text="",
            bg=RAISED,
            fg=MUTED,
            font=("Segoe UI", 13, "bold"),
            anchor="center",
            justify="center",
            cursor="hand2",
            pady=10,
        )
        label.pack(fill="both", expand=True)
        box.bind("<Button-1>", lambda _event: command())
        label.bind("<Button-1>", lambda _event: command())
        return box, label

    def show_hide(self) -> None:
        self.page = "hide"
        self.hide_page.tkraise()
        self.paint_tabs()

    def show_extract(self) -> None:
        self.page = "extract"
        self.extract_page.tkraise()
        self.paint_tabs()

    def paint_tabs(self) -> None:
        self._paint_tab(self.hide_box, self.hide_tab, self.page == "hide")
        self._paint_tab(self.extract_box, self.extract_tab, self.page == "extract")

    def _paint_tab(self, box: tk.Frame, label: tk.Label, selected: bool) -> None:
        edge = ACCENT if selected else LINE
        fill = BG if selected else RAISED
        ink = ACCENT if selected else MUTED
        box.configure(bg=fill, highlightbackground=edge, highlightcolor=edge)
        label.configure(bg=fill, fg=ink)

    def show_text(self) -> None:
        if self.busy:
            return
        self.mode = "text"
        self._show_mode(self.text_panel)
        self.paint_modes()

    def show_file(self) -> None:
        if self.busy:
            return
        self.mode = "file"
        self._show_mode(self.file_panel)
        self.paint_modes()

    def show_voice(self) -> None:
        if self.busy:
            return
        self.mode = "voice"
        self._show_mode(self.voice_panel)
        self.paint_modes()

    def _show_mode(self, panel: tk.Frame) -> None:
        for child in (self.text_panel, self.file_panel, self.voice_panel):
            if child is panel:
                child.grid(row=0, column=0, sticky="nsew")
            else:
                child.grid_remove()

    def paint_modes(self) -> None:
        self._paint_tab(self.text_box, self.text_tab, self.mode == "text")
        self._paint_tab(self.file_box, self.file_tab, self.mode == "file")
        self._paint_tab(self.voice_box, self.voice_tab, self.mode == "voice")

    def toggle_record(self) -> None:
        if self.busy:
            return
        if self.voice_recording:
            self.stop_voice()
        else:
            self.start_voice()

    def start_voice(self) -> None:
        try:
            self.recorder.start()
        except MicError as exc:
            self.voice_bytes = None
            self.voice_recording = False
            self.voice_status.configure(
                text=self.tx(
                    "Няма микрофон." if str(exc) == "mic" else "Записът не тръгна.",
                    "No microphone." if str(exc) == "mic" else "The recording did not start.",
                )
            )
            self.paint_voice()
            return
        self.voice_bytes = None
        self.voice_recording = True
        self.record_started = time.monotonic()
        self.paint_voice()
        self._tick_voice()

    def stop_voice(self) -> None:
        self.voice_recording = False
        try:
            self.voice_bytes = self.recorder.stop()
        except MicError:
            self.voice_bytes = None
            self.voice_status.configure(
                text=self.tx("Записът е твърде кратък.", "The recording is too short.")
            )
        self.paint_voice()

    def clear_voice(self) -> None:
        if self.busy or self.voice_recording:
            return
        self.voice_bytes = None
        self.recorder.close()
        self.voice_status.configure(text="")
        self.paint_voice()

    def _tick_voice(self) -> None:
        if not self.voice_recording or not self.root.winfo_exists():
            return
        self.recorder.drain(True)
        self.paint_voice()
        self.root.after(200, self._tick_voice)

    def paint_voice(self) -> None:
        self.record_button.configure(
            text=self.tx("Спри", "Stop") if self.voice_recording else self.tx("Запиши глас", "Record voice")
        )
        if self.voice_recording:
            seconds = int(time.monotonic() - self.record_started)
            clock = f"{seconds // 60}:{seconds % 60:02d}"
            self.voice_status.configure(text=self.tx(f"Запис… {clock}", f"Recording… {clock}"))
        elif self.voice_bytes:
            self.voice_status.configure(
                text=self.tx(
                    f"глас.wav · {format_size(len(self.voice_bytes))}",
                    f"voice.wav · {format_size(len(self.voice_bytes))}",
                )
            )
        elif not self.voice_status.cget("text"):
            self.voice_status.configure(text="")
        self.clear_voice_button.configure(state="normal" if self.voice_bytes and not self.voice_recording else "disabled")

    def close(self) -> None:
        self.voice_recording = False
        self.recorder.close()
        self.root.destroy()

    def _build_hide(self, frame: ttk.Frame) -> None:
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(5, weight=1)
        self.hide_carrier_label = ttk.Label(frame, text="1. Носител", font=("Segoe UI", 12, "bold"))
        self.hide_carrier_label.grid(row=0, column=0, sticky="w")
        self.cover_button = ttk.Button(frame, text="Избери WAV или PNG", command=self.choose_cover)
        self.cover_button.grid(row=1, column=0, sticky="ew", pady=(8, 4))
        self.cover_label = ttk.Label(frame, text="Още няма избран носител", wraplength=500)
        self.cover_label.grid(row=2, column=0, sticky="w")

        self.hide_what_label = ttk.Label(frame, text="2. Какво да се скрие", font=("Segoe UI", 12, "bold"))
        self.hide_what_label.grid(row=3, column=0, sticky="w", pady=(16, 0))
        modes = tk.Frame(frame, bg=BG)
        modes.grid(row=4, column=0, sticky="ew", pady=(8, 8))
        for column in (0, 2, 4):
            modes.columnconfigure(column, weight=1, uniform="modes")
        for column in (1, 3):
            modes.columnconfigure(column, minsize=8)
        self.text_box, self.text_tab = self._tab(modes, 0, self.show_text, (0, 0))
        self.file_box, self.file_tab = self._tab(modes, 2, self.show_file, (0, 0))
        self.voice_box, self.voice_tab = self._tab(modes, 4, self.show_voice, (0, 0))

        self.mode_body = tk.Frame(frame, bg=BG)
        self.mode_body.grid(row=5, column=0, sticky="nsew")
        self.mode_body.columnconfigure(0, weight=1)
        self.mode_body.rowconfigure(0, weight=1)
        self.text_panel = tk.Frame(self.mode_body, bg=BG)
        self.secret = dark_text(self.text_panel, height=5, wrap="word")
        self.secret.pack(fill="both", expand=True)
        self.file_panel = tk.Frame(self.mode_body, bg=BG)
        self.file_panel.columnconfigure(0, weight=1)
        self.payload_button = ttk.Button(self.file_panel, text="Избери файл", command=self.choose_payload)
        self.payload_button.grid(row=0, column=0, sticky="ew")
        self.clear_button = ttk.Button(self.file_panel, text="Махни файла", command=self.clear_payload)
        self.clear_button.grid(row=1, column=0, sticky="ew", pady=(8, 0))
        self.payload_label = ttk.Label(self.file_panel, text="", wraplength=500)
        self.payload_label.grid(row=2, column=0, sticky="w", pady=(8, 0))
        self.voice_panel = tk.Frame(self.mode_body, bg=BG)
        self.voice_panel.columnconfigure(0, weight=1)
        self.voice_note = ttk.Label(self.voice_panel, text="", wraplength=500)
        self.voice_note.grid(row=0, column=0, sticky="w")
        self.record_button = ttk.Button(self.voice_panel, text="Запиши глас", command=self.toggle_record)
        self.record_button.grid(row=1, column=0, sticky="ew", pady=(8, 0))
        self.voice_status = ttk.Label(self.voice_panel, text="", wraplength=500)
        self.voice_status.grid(row=2, column=0, sticky="w", pady=(8, 0))
        self.clear_voice_button = ttk.Button(self.voice_panel, text="Изтрий записа", command=self.clear_voice)
        self.clear_voice_button.grid(row=3, column=0, sticky="ew", pady=(8, 0))
        self.show_text()

        self.hide_password_label = ttk.Label(frame, text="3. Парола", font=("Segoe UI", 12, "bold"))
        self.hide_password_label.grid(row=7, column=0, sticky="w", pady=(16, 0))
        self.hide_password = tk.StringVar()
        self.hide_password_box = ttk.Entry(frame, textvariable=self.hide_password, show="*")
        self.hide_password_box.grid(row=8, column=0, sticky="ew", pady=(8, 4))
        self.hide_show = tk.BooleanVar(value=False)
        self.hide_show_check = ttk.Checkbutton(frame, text="Покажи паролата", variable=self.hide_show, command=self.toggle_hide_password)
        self.hide_show_check.grid(row=9, column=0, sticky="w")

        self.hide_button = ttk.Button(frame, text="4. Скрий и запиши", command=self.start_hide)
        self.hide_button.grid(row=10, column=0, sticky="ew", pady=(16, 8))
        self.hide_result = dark_text(frame, height=4, wrap="word", font=("Segoe UI", 11))
        self.hide_result.grid(row=11, column=0, sticky="ew")
        self.write(self.hide_result, "Резултатът се записва като нов файл. Оригиналът остава.")

    def _build_extract(self, frame: ttk.Frame) -> None:
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(7, weight=1)
        self.extract_file_label = ttk.Label(frame, text="1. Файл със скрито съдържание", font=("Segoe UI", 12, "bold"))
        self.extract_file_label.grid(row=0, column=0, sticky="w")
        self.stego_button = ttk.Button(frame, text="Избери WAV или PNG", command=self.choose_stego)
        self.stego_button.grid(row=1, column=0, sticky="ew", pady=(8, 4))
        self.stego_label = ttk.Label(frame, text="Още няма избран файл", wraplength=500)
        self.stego_label.grid(row=2, column=0, sticky="w")

        self.extract_password_label = ttk.Label(frame, text="2. Парола", font=("Segoe UI", 12, "bold"))
        self.extract_password_label.grid(row=3, column=0, sticky="w", pady=(16, 0))
        self.extract_password = tk.StringVar()
        self.extract_password_box = ttk.Entry(frame, textvariable=self.extract_password, show="*")
        self.extract_password_box.grid(row=4, column=0, sticky="ew", pady=(8, 4))
        self.extract_show = tk.BooleanVar(value=False)
        self.extract_show_check = ttk.Checkbutton(
            frame,
            text="Покажи паролата",
            variable=self.extract_show,
            command=self.toggle_extract_password,
        )
        self.extract_show_check.grid(row=5, column=0, sticky="w")

        self.extract_button = ttk.Button(frame, text="3. Извлечи", command=self.start_extract)
        self.extract_button.grid(row=6, column=0, sticky="ew", pady=(16, 8))
        self.extract_result = dark_text(frame, height=8, wrap="word")
        self.extract_result.grid(row=7, column=0, sticky="nsew")
        self.write(self.extract_result, "Тук ще се появи скритият текст.")

    def tx(self, bg: str, en: str) -> str:
        return en if self.english else bg

    def toggle_language(self) -> None:
        self.english = not self.english
        self.apply_language()

    def draw_flag(self) -> None:
        canvas = self.flag
        canvas.delete("all")
        width, height = 28, 18
        if not self.english:
            canvas.create_rectangle(0, 0, width, height / 3, fill="#ffffff", width=0)
            canvas.create_rectangle(0, height / 3, width, height * 2 / 3, fill="#00966E", width=0)
            canvas.create_rectangle(0, height * 2 / 3, width, height, fill="#D62612", width=0)
            return
        canvas.create_rectangle(0, 0, width, height, fill="#012169", width=0)
        canvas.create_line(0, 0, width, height, fill="#ffffff", width=5)
        canvas.create_line(width, 0, 0, height, fill="#ffffff", width=5)
        canvas.create_line(0, 2, width, height + 2, fill="#C8102E", width=2)
        canvas.create_line(width, 2, 0, height + 2, fill="#C8102E", width=2)
        canvas.create_rectangle(width / 2 - 3, 0, width / 2 + 3, height, fill="#ffffff", width=0)
        canvas.create_rectangle(0, height / 2 - 2.5, width, height / 2 + 2.5, fill="#ffffff", width=0)
        canvas.create_rectangle(width / 2 - 1.6, 0, width / 2 + 1.6, height, fill="#C8102E", width=0)
        canvas.create_rectangle(0, height / 2 - 1.2, width, height / 2 + 1.2, fill="#C8102E", width=0)

    def apply_language(self) -> None:
        self.draw_flag()
        self.hide_tab.configure(text=self.tx("Скрий", "Hide"))
        self.extract_tab.configure(text=self.tx("Извлечи", "Extract"))
        self.paint_tabs()
        self.text_tab.configure(text=self.tx("Текст", "Text"))
        self.file_tab.configure(text=self.tx("Файл", "File"))
        self.voice_tab.configure(text=self.tx("Глас", "Voice"))
        self.paint_modes()
        self.voice_note.configure(
            text=self.tx(
                "WAV, 16 kHz, моно. Кратък запис се побира по-лесно в носителя.",
                "WAV, 16 kHz, mono. A short recording fits in the carrier more easily.",
            )
        )
        self.clear_voice_button.configure(text=self.tx("Изтрий записа", "Delete the recording"))
        self.paint_voice()
        self.hide_carrier_label.configure(text=self.tx("1. Носител", "1. Carrier"))
        self.cover_button.configure(text=self.tx("Избери WAV или PNG", "Choose WAV or PNG"))
        if self.cover is None:
            self.cover_label.configure(text=self.tx("Още няма избран носител", "No carrier chosen yet"))
        self.hide_what_label.configure(text=self.tx("2. Какво да се скрие", "2. What to hide"))
        self.payload_button.configure(text=self.tx("Избери файл", "Choose a file"))
        self.clear_button.configure(text=self.tx("Махни файла", "Remove the file"))
        if self.payload_path is None:
            self.payload_label.configure(text=self.tx("Още няма избран файл.", "No file chosen yet."))
        else:
            self.payload_label.configure(text=self.tx(f"Ще се скрие файлът: {self.payload_path.name}", f"The file to hide: {self.payload_path.name}"))
        self.hide_password_label.configure(text=self.tx("3. Парола", "3. Password"))
        self.hide_show_check.configure(text=self.tx("Покажи паролата", "Show the password"))
        self.hide_button.configure(text=self.tx("4. Скрий и запиши", "4. Hide and save"))
        if self.hide_idle:
            self.write(self.hide_result, self.tx("Резултатът се записва като нов файл. Оригиналът остава.", "The result is saved as a new file. The original stays."))
        self.extract_file_label.configure(text=self.tx("1. Файл със скрито съдържание", "1. File with hidden content"))
        self.stego_button.configure(text=self.tx("Избери WAV или PNG", "Choose WAV or PNG"))
        if self.stego is None:
            self.stego_label.configure(text=self.tx("Още няма избран файл", "No file chosen yet"))
        self.extract_password_label.configure(text=self.tx("2. Парола", "2. Password"))
        self.extract_show_check.configure(text=self.tx("Покажи паролата", "Show the password"))
        self.extract_button.configure(text=self.tx("3. Извлечи", "3. Extract"))
        if self.extract_idle:
            self.write(self.extract_result, self.tx("Тук ще се появи скритият текст.", "The hidden text will appear here."))

    def choose_cover(self) -> None:
        if self.busy:
            return
        selected = filedialog.askopenfilename(title=self.tx("Избери носител", "Choose a carrier"), filetypes=self.file_types())
        if not selected:
            return
        self.cover = Path(selected)
        self.cover_label.configure(text=str(self.cover))

    def choose_payload(self) -> None:
        if self.busy:
            return
        selected = filedialog.askopenfilename(title=self.tx("Файл, който да се скрие", "File to hide"))
        if not selected:
            return
        self.payload_path = Path(selected)
        self.payload_label.configure(text=self.tx(f"Ще се скрие файлът: {self.payload_path.name}", f"The file to hide: {self.payload_path.name}"))

    def clear_payload(self) -> None:
        if self.busy:
            return
        self.payload_path = None
        self.payload_label.configure(text=self.tx("Още няма избран файл.", "No file chosen yet."))

    def choose_stego(self) -> None:
        if self.busy:
            return
        selected = filedialog.askopenfilename(title=self.tx("Избери WAV или PNG", "Choose WAV or PNG"), filetypes=self.file_types())
        if not selected:
            return
        self.stego = Path(selected)
        self.stego_label.configure(text=str(self.stego))

    def toggle_hide_password(self) -> None:
        self.hide_password_box.configure(show="" if self.hide_show.get() else "*")

    def toggle_extract_password(self) -> None:
        self.extract_password_box.configure(show="" if self.extract_show.get() else "*")

    def file_types(self) -> list[tuple[str, str]]:
        return [
            (self.tx("WAV или PNG", "WAV or PNG"), "*.wav *.png"),
            (self.tx("Всички файлове", "All files"), "*.*"),
        ]

    def write(self, box: tk.Text, text: str) -> None:
        box.configure(state="normal", bg=FIELD, fg=TEXT, insertbackground=TEXT)
        box.delete("1.0", "end")
        box.insert("1.0", text)
        box.configure(state="disabled", bg=FIELD, fg=TEXT)

    def set_busy(self, busy: bool) -> None:
        self.busy = busy
        state = "disabled" if busy else "normal"
        self.hide_button.configure(state=state)
        self.extract_button.configure(state=state)
        self.record_button.configure(state=state)
        self.clear_voice_button.configure(
            state="normal" if not busy and self.voice_bytes and not self.voice_recording else "disabled"
        )

    def start_hide(self) -> None:
        if self.busy:
            return
        if self.cover is None:
            self.hide_idle = False
            self.write(self.hide_result, self.tx("Първо избери WAV или PNG.", "Choose a WAV or PNG first."))
            return
        password = self.hide_password.get()
        if not password:
            self.hide_idle = False
            self.write(self.hide_result, self.tx("Напиши паролата.", "Type the password."))
            return
        if self.voice_recording:
            self.hide_idle = False
            self.write(self.hide_result, self.tx("Първо спри записа.", "Stop the recording first."))
            return
        payload_path = None
        data: bytes | None = None
        if self.mode == "text":
            text = self.secret.get("1.0", "end-1c")
            if not text:
                self.hide_idle = False
                self.write(self.hide_result, self.tx("Напиши текста.", "Type the text."))
                return
            data = text.encode("utf-8")
        elif self.mode == "file":
            if self.payload_path is None:
                self.hide_idle = False
                self.write(self.hide_result, self.tx("Избери файл.", "Choose a file."))
                return
            payload_path = self.payload_path
        else:
            if not self.voice_bytes:
                self.hide_idle = False
                self.write(self.hide_result, self.tx("Няма записан глас.", "No voice recorded."))
                return
            data = self.voice_bytes
        try:
            kind = carrier_kind(self.cover)
        except StegoError as exc:
            self.hide_idle = False
            self.write(self.hide_result, shown(self.english, str(exc)))
            return
        except OSError as exc:
            self.hide_idle = False
            self.write(self.hide_result, self.tx(f"Файлът не може да се прочете: {exc.strerror or exc}", f"The file cannot be read: {exc.strerror or exc}"))
            return
        ext = ".png" if kind == "png" else ".wav"
        selected = filedialog.asksaveasfilename(
            title=self.tx("Къде да се запише", "Where to save"),
            initialdir=str(self.cover.parent),
            initialfile=self.cover.stem + "-stego" + ext,
            defaultextension=ext,
            filetypes=[("PNG", "*.png")] if kind == "png" else [("WAV", "*.wav")],
        )
        if not selected:
            return
        out = Path(selected)
        if out.resolve() == self.cover.resolve():
            self.hide_idle = False
            self.write(self.hide_result, self.tx("Избери друго име, за да не се затрие оригиналът.", "Choose another name so the original is not overwritten."))
            return
        self.hide_idle = False
        self.set_busy(True)
        self.write(self.hide_result, self.tx("Чакай малко…", "Please wait…"))
        threading.Thread(
            target=self.work_hide,
            args=(self.cover, out, data, payload_path, password),
            daemon=True,
        ).start()

    def work_hide(self, cover: Path, out: Path, data: bytes | None, payload_path: Path | None, password: str) -> None:
        try:
            payload = payload_path.read_bytes() if payload_path is not None else data
            assert payload is not None
            hide_file(cover, out, payload, password)
        except StegoError as exc:
            message = shown(self.english, str(exc))
        except OSError as exc:
            message = self.tx(f"Файлът не може да се запише: {exc.strerror or exc}", f"The file cannot be saved: {exc.strerror or exc}")
        else:
            message = self.tx(
                f"Готово. Записах го тук:\n{out}\n\nПрати го като файл, не като гласово или снимка.",
                f"Done. Saved here:\n{out}\n\nSend it as a file, not as a voice note or a photo.",
            )
        self.root.after(0, lambda message=message: self.finish_hide(message))

    def finish_hide(self, text: str) -> None:
        self.set_busy(False)
        self.write(self.hide_result, text)

    def start_extract(self) -> None:
        if self.busy:
            return
        if self.stego is None:
            self.extract_idle = False
            self.write(self.extract_result, self.tx("Първо избери WAV или PNG.", "Choose a WAV or PNG first."))
            return
        password = self.extract_password.get()
        if not password:
            self.extract_idle = False
            self.write(self.extract_result, self.tx("Напиши паролата.", "Type the password."))
            return
        self.extract_idle = False
        self.set_busy(True)
        self.write(self.extract_result, self.tx("Чакай малко…", "Please wait…"))
        path = self.stego
        threading.Thread(target=self.work_extract, args=(path, password), daemon=True).start()

    def work_extract(self, path: Path, password: str) -> None:
        try:
            text, saved = extract_file(path, password)
        except StegoError as exc:
            message = shown(self.english, str(exc))
        except OSError as exc:
            message = self.tx(f"Файлът не може да се прочете: {exc.strerror or exc}", f"The file cannot be read: {exc.strerror or exc}")
        else:
            message = text if text is not None else self.tx(f"Това не е текст. Записах го тук:\n{saved}", f"This is not text. Saved here:\n{saved}")
        self.root.after(0, lambda message=message: self.finish_extract(message))

    def finish_extract(self, text: str) -> None:
        self.set_busy(False)
        self.write(self.extract_result, text)

    def run(self) -> None:
        self.root.mainloop()


if __name__ == "__main__":
    MediaWindow().run()
