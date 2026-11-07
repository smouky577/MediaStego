"""Прозорец за извличане. Пуска се с двоен клик върху Извлечи на работния плот."""

from __future__ import annotations

import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, ttk

ABOUT_EN = "Donate to Dr. Baysinger. He promises to spend it on hookers and beer."
ABOUT_BG = "Дарете на Dr. Baysinger. Той обещава да ги изхарчи за курви и бело."
REVOLUT = "revolut.me/georgi09qc"
BTC = (
    "bc1qr84akpfry7sjs6759wwq722fyert9apcarpst6",
    "bc1p6hp5q20u9t9upsjt7425wgqdrs0cr8wnnlf9atu24z92pzsnq85q62uc8e",
)


def show_about(root: tk.Tk) -> None:
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


from hide import extract as extract_wav
from hide_png import JPEG_MESSAGE, extract as extract_png
from stego_core import StegoError

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def extract_file(path: Path, password: str) -> tuple[str | None, Path | None]:
    raw = path.read_bytes()
    if raw[:2] == b"\xff\xd8" or path.suffix.lower() in {".jpg", ".jpeg"}:
        raise StegoError(JPEG_MESSAGE)
    if raw.startswith(PNG_MAGIC):
        data = extract_png(str(path), password)
    elif raw[:4] == b"RIFF":
        data = extract_wav(str(path), password)
    else:
        raise StegoError("Избери WAV или PNG файл")
    try:
        return data.decode("utf-8"), None
    except UnicodeDecodeError:
        saved = save_beside(path, data)
        return None, saved


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


class ExtractWindow:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title("Извлечи")
        self.root.geometry("560x460")
        self.root.minsize(480, 400)
        self.path: Path | None = None
        self.busy = False

        frame = ttk.Frame(self.root, padding=16)
        frame.pack(fill="both", expand=True)
        frame.columnconfigure(0, weight=1)

        ttk.Label(frame, text="1. Избери файла", font=("Segoe UI", 12, "bold")).grid(row=0, column=0, sticky="w")
        ttk.Button(frame, text="Избери WAV или PNG", command=self.choose).grid(row=1, column=0, sticky="ew", pady=(8, 4))
        self.file_label = ttk.Label(frame, text="Още няма избран файл", wraplength=500)
        self.file_label.grid(row=2, column=0, sticky="w")

        ttk.Label(frame, text="2. Паролата", font=("Segoe UI", 12, "bold")).grid(row=3, column=0, sticky="w", pady=(16, 0))
        self.password = tk.StringVar()
        self.password_box = ttk.Entry(frame, textvariable=self.password, show="*")
        self.password_box.grid(row=4, column=0, sticky="ew", pady=(8, 4))
        self.show_password = tk.BooleanVar(value=False)
        ttk.Checkbutton(
            frame,
            text="Покажи паролата",
            variable=self.show_password,
            command=self.toggle_password,
        ).grid(row=5, column=0, sticky="w")

        self.button = ttk.Button(frame, text="3. Извлечи", command=self.start)
        self.button.grid(row=6, column=0, sticky="ew", pady=(16, 8))

        self.result = tk.Text(frame, height=10, wrap="word", font=("Segoe UI", 12))
        self.result.grid(row=7, column=0, sticky="nsew")
        frame.rowconfigure(7, weight=1)
        self.write("Тук ще се появи скритият текст.")
        self.result.configure(state="disabled")

        credit = ttk.Label(frame, text="by Dr. Baysinger", cursor="hand2")
        credit.grid(row=8, column=0, pady=(12, 0))
        credit.bind("<Button-1>", lambda _event: show_about(self.root))

        self.password_box.focus_set()
        self.root.bind("<Return>", lambda _event: self.start())

    def choose(self) -> None:
        if self.busy:
            return
        selected = filedialog.askopenfilename(
            title="Избери WAV или PNG",
            filetypes=[("WAV или PNG", "*.wav *.png"), ("Всички файлове", "*.*")],
        )
        if not selected:
            return
        self.path = Path(selected)
        self.file_label.configure(text=str(self.path))

    def toggle_password(self) -> None:
        self.password_box.configure(show="" if self.show_password.get() else "*")

    def write(self, text: str) -> None:
        self.result.configure(state="normal")
        self.result.delete("1.0", "end")
        self.result.insert("1.0", text)
        self.result.configure(state="disabled")

    def start(self) -> None:
        if self.busy:
            return
        if self.path is None:
            self.write("Първо натисни „Избери WAV или PNG“.")
            return
        password = self.password.get()
        if not password:
            self.write("Напиши паролата.")
            return
        self.busy = True
        self.button.configure(state="disabled")
        self.write("Чакай малко…")
        path = self.path
        threading.Thread(target=self.work, args=(path, password), daemon=True).start()

    def work(self, path: Path, password: str) -> None:
        try:
            text, saved = extract_file(path, password)
        except StegoError as exc:
            message = str(exc)
            self.root.after(0, lambda message=message: self.finish(message))
            return
        except OSError as exc:
            message = f"Файлът не може да се прочете: {exc.strerror or exc}"
            self.root.after(0, lambda message=message: self.finish(message))
            return
        if text is not None:
            self.root.after(0, lambda text=text: self.finish(text))
            return
        assert saved is not None
        message = f"Това не е текст. Записах го тук:\n{saved}"
        self.root.after(0, lambda message=message: self.finish(message))

    def finish(self, text: str) -> None:
        self.busy = False
        self.button.configure(state="normal")
        self.write(text)

    def run(self) -> None:
        self.root.mainloop()


if __name__ == "__main__":
    ExtractWindow().run()
