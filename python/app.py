"""Прозорец за скриване и извличане. Пуска се с двоен клик върху MediaStego."""

from __future__ import annotations

import threading
import tkinter as tk
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
FILETYPES = [("WAV или PNG", "*.wav *.png"), ("Всички файлове", "*.*")]


def show_about(root: tk.Misc) -> None:
    window = tk.Toplevel(root)
    window.title("About")
    window.resizable(False, False)
    window.transient(root)
    frame = ttk.Frame(window, padding=16)
    frame.pack(fill="both", expand=True)
    ttk.Label(frame, text="About", font=("Segoe UI", 14, "bold")).pack(anchor="w")
    ttk.Label(frame, text=ABOUT_EN, wraplength=520, justify="left").pack(anchor="w", pady=(12, 6))
    ttk.Label(frame, text=ABOUT_BG, wraplength=520, justify="left").pack(anchor="w")
    note = ttk.Label(frame, text="")

    def copy(value: str) -> None:
        root.clipboard_clear()
        root.clipboard_append(value)
        note.configure(text="Copied")

    def link(title: str, value: str) -> None:
        ttk.Label(frame, text=title, font=("Segoe UI", 11, "bold")).pack(anchor="w", pady=(14, 0))
        label = ttk.Label(frame, text=value, cursor="hand2", wraplength=520)
        label.pack(anchor="w", pady=(4, 0))
        label.bind("<Button-1>", lambda _event: copy(value))

    link("Revolut", REVOLUT)
    ttk.Label(frame, text="BTC address", font=("Segoe UI", 11, "bold")).pack(anchor="w", pady=(14, 0))
    for address in BTC:
        label = ttk.Label(frame, text=address, cursor="hand2")
        label.pack(anchor="w", pady=(4, 0))
        label.bind("<Button-1>", lambda _event, address=address: copy(address))
    note.pack(anchor="w", pady=(12, 0))
    window.update_idletasks()
    width, height = 560, window.winfo_reqheight()
    parent_x = root.winfo_rootx() + max(0, (root.winfo_width() - width) // 2)
    parent_y = root.winfo_rooty() + max(0, (root.winfo_height() - height) // 2)
    window.geometry(f"{width}x{height}+{parent_x}+{parent_y}")
    window.grab_set()
    window.focus_set()


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
        self.root.geometry("560x640")
        self.root.minsize(480, 560)
        self.busy = False
        self.cover: Path | None = None
        self.payload_path: Path | None = None
        self.stego: Path | None = None

        outer = ttk.Frame(self.root, padding=16)
        outer.pack(fill="both", expand=True)
        outer.rowconfigure(0, weight=1)
        outer.columnconfigure(0, weight=1)

        book = ttk.Notebook(outer)
        book.grid(row=0, column=0, sticky="nsew")
        hide = ttk.Frame(book, padding=8)
        extract = ttk.Frame(book, padding=8)
        book.add(hide, text="Скрий")
        book.add(extract, text="Извлечи")
        self._build_hide(hide)
        self._build_extract(extract)

        credit = ttk.Label(outer, text="by Dr. Baysinger", cursor="hand2")
        credit.grid(row=1, column=0, pady=(12, 0))
        credit.bind("<Button-1>", lambda _event: show_about(self.root))

    def _build_hide(self, frame: ttk.Frame) -> None:
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(6, weight=1)
        ttk.Label(frame, text="1. Носител", font=("Segoe UI", 12, "bold")).grid(row=0, column=0, sticky="w")
        ttk.Button(frame, text="Избери WAV или PNG", command=self.choose_cover).grid(row=1, column=0, sticky="ew", pady=(8, 4))
        self.cover_label = ttk.Label(frame, text="Още няма избран носител", wraplength=500)
        self.cover_label.grid(row=2, column=0, sticky="w")

        ttk.Label(frame, text="2. Какво да се скрие", font=("Segoe UI", 12, "bold")).grid(row=3, column=0, sticky="w", pady=(16, 0))
        self.secret = tk.Text(frame, height=5, wrap="word", font=("Segoe UI", 12))
        self.secret.grid(row=4, column=0, sticky="nsew", pady=(8, 4))
        row = ttk.Frame(frame)
        row.grid(row=5, column=0, sticky="ew")
        row.columnconfigure(0, weight=1)
        ttk.Button(row, text="Или избери файл", command=self.choose_payload).grid(row=0, column=0, sticky="ew")
        ttk.Button(row, text="Махни файла", command=self.clear_payload).grid(row=0, column=1, padx=(8, 0))
        self.payload_label = ttk.Label(frame, text="Ако избереш файл, той се скрива вместо текста.", wraplength=500)
        self.payload_label.grid(row=6, column=0, sticky="w", pady=(4, 0))

        ttk.Label(frame, text="3. Парола", font=("Segoe UI", 12, "bold")).grid(row=7, column=0, sticky="w", pady=(16, 0))
        self.hide_password = tk.StringVar()
        self.hide_password_box = ttk.Entry(frame, textvariable=self.hide_password, show="*")
        self.hide_password_box.grid(row=8, column=0, sticky="ew", pady=(8, 4))
        self.hide_show = tk.BooleanVar(value=False)
        ttk.Checkbutton(frame, text="Покажи паролата", variable=self.hide_show, command=self.toggle_hide_password).grid(row=9, column=0, sticky="w")

        self.hide_button = ttk.Button(frame, text="4. Скрий и запиши", command=self.start_hide)
        self.hide_button.grid(row=10, column=0, sticky="ew", pady=(16, 8))
        self.hide_result = tk.Text(frame, height=4, wrap="word", font=("Segoe UI", 11))
        self.hide_result.grid(row=11, column=0, sticky="ew")
        self.write(self.hide_result, "Резултатът се записва като нов файл. Оригиналът остава.")

    def _build_extract(self, frame: ttk.Frame) -> None:
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(7, weight=1)
        ttk.Label(frame, text="1. Файл със скрито съдържание", font=("Segoe UI", 12, "bold")).grid(row=0, column=0, sticky="w")
        ttk.Button(frame, text="Избери WAV или PNG", command=self.choose_stego).grid(row=1, column=0, sticky="ew", pady=(8, 4))
        self.stego_label = ttk.Label(frame, text="Още няма избран файл", wraplength=500)
        self.stego_label.grid(row=2, column=0, sticky="w")

        ttk.Label(frame, text="2. Парола", font=("Segoe UI", 12, "bold")).grid(row=3, column=0, sticky="w", pady=(16, 0))
        self.extract_password = tk.StringVar()
        self.extract_password_box = ttk.Entry(frame, textvariable=self.extract_password, show="*")
        self.extract_password_box.grid(row=4, column=0, sticky="ew", pady=(8, 4))
        self.extract_show = tk.BooleanVar(value=False)
        ttk.Checkbutton(
            frame,
            text="Покажи паролата",
            variable=self.extract_show,
            command=self.toggle_extract_password,
        ).grid(row=5, column=0, sticky="w")

        self.extract_button = ttk.Button(frame, text="3. Извлечи", command=self.start_extract)
        self.extract_button.grid(row=6, column=0, sticky="ew", pady=(16, 8))
        self.extract_result = tk.Text(frame, height=8, wrap="word", font=("Segoe UI", 12))
        self.extract_result.grid(row=7, column=0, sticky="nsew")
        self.write(self.extract_result, "Тук ще се появи скритият текст.")

    def choose_cover(self) -> None:
        if self.busy:
            return
        selected = filedialog.askopenfilename(title="Избери носител", filetypes=FILETYPES)
        if not selected:
            return
        self.cover = Path(selected)
        self.cover_label.configure(text=str(self.cover))

    def choose_payload(self) -> None:
        if self.busy:
            return
        selected = filedialog.askopenfilename(title="Файл, който да се скрие")
        if not selected:
            return
        self.payload_path = Path(selected)
        self.payload_label.configure(text=f"Ще се скрие файлът: {self.payload_path.name}")

    def clear_payload(self) -> None:
        if self.busy:
            return
        self.payload_path = None
        self.payload_label.configure(text="Ако избереш файл, той се скрива вместо текста.")

    def choose_stego(self) -> None:
        if self.busy:
            return
        selected = filedialog.askopenfilename(title="Избери WAV или PNG", filetypes=FILETYPES)
        if not selected:
            return
        self.stego = Path(selected)
        self.stego_label.configure(text=str(self.stego))

    def toggle_hide_password(self) -> None:
        self.hide_password_box.configure(show="" if self.hide_show.get() else "*")

    def toggle_extract_password(self) -> None:
        self.extract_password_box.configure(show="" if self.extract_show.get() else "*")

    def write(self, box: tk.Text, text: str) -> None:
        box.configure(state="normal")
        box.delete("1.0", "end")
        box.insert("1.0", text)
        box.configure(state="disabled")

    def set_busy(self, busy: bool) -> None:
        self.busy = busy
        state = "disabled" if busy else "normal"
        self.hide_button.configure(state=state)
        self.extract_button.configure(state=state)

    def start_hide(self) -> None:
        if self.busy:
            return
        if self.cover is None:
            self.write(self.hide_result, "Първо избери WAV или PNG.")
            return
        password = self.hide_password.get()
        if not password:
            self.write(self.hide_result, "Напиши паролата.")
            return
        payload_path = self.payload_path
        text = self.secret.get("1.0", "end-1c")
        if payload_path is None and not text:
            self.write(self.hide_result, "Напиши текст или избери файл.")
            return
        try:
            kind = carrier_kind(self.cover)
        except StegoError as exc:
            self.write(self.hide_result, str(exc))
            return
        except OSError as exc:
            self.write(self.hide_result, f"Файлът не може да се прочете: {exc.strerror or exc}")
            return
        ext = ".png" if kind == "png" else ".wav"
        selected = filedialog.asksaveasfilename(
            title="Къде да се запише",
            initialdir=str(self.cover.parent),
            initialfile=self.cover.stem + "-stego" + ext,
            defaultextension=ext,
            filetypes=[("PNG", "*.png")] if kind == "png" else [("WAV", "*.wav")],
        )
        if not selected:
            return
        out = Path(selected)
        if out.resolve() == self.cover.resolve():
            self.write(self.hide_result, "Избери друго име, за да не се затрие оригиналът.")
            return
        data = None if payload_path is not None else text.encode("utf-8")
        self.set_busy(True)
        self.write(self.hide_result, "Чакай малко…")
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
            message = str(exc)
        except OSError as exc:
            message = f"Файлът не може да се запише: {exc.strerror or exc}"
        else:
            message = f"Готово. Записах го тук:\n{out}\n\nПрати го като файл, не като гласово или снимка."
        self.root.after(0, lambda message=message: self.finish_hide(message))

    def finish_hide(self, text: str) -> None:
        self.set_busy(False)
        self.write(self.hide_result, text)

    def start_extract(self) -> None:
        if self.busy:
            return
        if self.stego is None:
            self.write(self.extract_result, "Първо избери WAV или PNG.")
            return
        password = self.extract_password.get()
        if not password:
            self.write(self.extract_result, "Напиши паролата.")
            return
        self.set_busy(True)
        self.write(self.extract_result, "Чакай малко…")
        path = self.stego
        threading.Thread(target=self.work_extract, args=(path, password), daemon=True).start()

    def work_extract(self, path: Path, password: str) -> None:
        try:
            text, saved = extract_file(path, password)
        except StegoError as exc:
            message = str(exc)
        except OSError as exc:
            message = f"Файлът не може да се прочете: {exc.strerror or exc}"
        else:
            message = text if text is not None else f"Това не е текст. Записах го тук:\n{saved}"
        self.root.after(0, lambda message=message: self.finish_extract(message))

    def finish_extract(self, text: str) -> None:
        self.set_busy(False)
        self.write(self.extract_result, text)

    def run(self) -> None:
        self.root.mainloop()


if __name__ == "__main__":
    MediaWindow().run()
