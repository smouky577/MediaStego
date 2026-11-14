"""Прозорец за скриване и извличане. Пуска се с двоен клик върху MediaStego."""

from __future__ import annotations

import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, ttk

from PIL import Image, ImageDraw, ImageTk

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
INK = "#14160F"
AMBER = "#E4A04A"
MUTED = "#C9C4B4"


def paint_flag(kind: str) -> Image.Image:
    width, height = 36, 22
    if kind == "bg":
        image = Image.new("RGB", (width, height), (255, 255, 255))
        draw = ImageDraw.Draw(image)
        stripe = height // 3
        draw.rectangle((0, stripe, width, stripe * 2), fill=(0, 150, 110))
        draw.rectangle((0, stripe * 2, width, height), fill=(214, 38, 18))
        return image
    image = Image.new("RGB", (width, height), (1, 33, 105))
    draw = ImageDraw.Draw(image)
    white = (255, 255, 255)
    red = (200, 16, 46)
    draw.line((0, 0, width, height), fill=white, width=8)
    draw.line((0, height, width, 0), fill=white, width=8)
    draw.line((0, 0, width, height), fill=red, width=3)
    draw.line((0, height, width, 0), fill=red, width=3)
    draw.rectangle((width // 2 - 6, 0, width // 2 + 6, height), fill=white)
    draw.rectangle((0, height // 2 - 5, width, height // 2 + 5), fill=white)
    draw.rectangle((width // 2 - 3, 0, width // 2 + 3, height), fill=red)
    draw.rectangle((0, height // 2 - 2, width, height // 2 + 2), fill=red)
    return image


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
        self.english = False
        self.cover: Path | None = None
        self.payload_path: Path | None = None
        self.stego: Path | None = None
        self.hide_idle = True
        self.extract_idle = True

        outer = ttk.Frame(self.root, padding=16)
        outer.pack(fill="both", expand=True)
        outer.rowconfigure(1, weight=1)
        outer.columnconfigure(0, weight=1)

        header = tk.Frame(outer, bg=INK)
        header.grid(row=0, column=0, sticky="ew", pady=(0, 10))
        header.columnconfigure(1, weight=1)
        tk.Label(header, text="MediaStego", bg=INK, fg=AMBER, font=("Segoe UI", 18, "bold")).grid(row=0, column=0, sticky="sw")
        tk.Frame(header, bg=INK).grid(row=0, column=1, sticky="ew")
        self.flag_btn = tk.Label(header, bg=INK, cursor="hand2", bd=0, highlightthickness=0, anchor="s")
        self.flag_btn.grid(row=0, column=2, sticky="se", padx=(0, 10), pady=(0, 9))
        self.flag_btn.bind("<Button-1>", lambda _event: self.toggle_language())
        credit = tk.Label(header, text="by Dr. Baysinger", bg=INK, fg=MUTED, font=("Segoe UI", 9), cursor="hand2", anchor="s")
        credit.grid(row=0, column=3, sticky="se", pady=(0, 3))
        credit.bind("<Button-1>", lambda _event: show_about(self.root))

        self.book = ttk.Notebook(outer)
        self.book.grid(row=1, column=0, sticky="nsew")
        hide = ttk.Frame(self.book, padding=8)
        extract = ttk.Frame(self.book, padding=8)
        self.book.add(hide, text="Скрий")
        self.book.add(extract, text="Извлечи")
        self._build_hide(hide)
        self._build_extract(extract)
        self.apply_language()

    def _build_hide(self, frame: ttk.Frame) -> None:
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(6, weight=1)
        self.hide_carrier_label = ttk.Label(frame, text="1. Носител", font=("Segoe UI", 12, "bold"))
        self.hide_carrier_label.grid(row=0, column=0, sticky="w")
        self.cover_button = ttk.Button(frame, text="Избери WAV или PNG", command=self.choose_cover)
        self.cover_button.grid(row=1, column=0, sticky="ew", pady=(8, 4))
        self.cover_label = ttk.Label(frame, text="Още няма избран носител", wraplength=500)
        self.cover_label.grid(row=2, column=0, sticky="w")

        self.hide_what_label = ttk.Label(frame, text="2. Какво да се скрие", font=("Segoe UI", 12, "bold"))
        self.hide_what_label.grid(row=3, column=0, sticky="w", pady=(16, 0))
        self.secret = tk.Text(frame, height=5, wrap="word", font=("Segoe UI", 12))
        self.secret.grid(row=4, column=0, sticky="nsew", pady=(8, 4))
        row = ttk.Frame(frame)
        row.grid(row=5, column=0, sticky="ew")
        row.columnconfigure(0, weight=1)
        self.payload_button = ttk.Button(row, text="Или избери файл", command=self.choose_payload)
        self.payload_button.grid(row=0, column=0, sticky="ew")
        self.clear_button = ttk.Button(row, text="Махни файла", command=self.clear_payload)
        self.clear_button.grid(row=0, column=1, padx=(8, 0))
        self.payload_label = ttk.Label(frame, text="Ако избереш файл, той се скрива вместо текста.", wraplength=500)
        self.payload_label.grid(row=6, column=0, sticky="w", pady=(4, 0))

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
        self.hide_result = tk.Text(frame, height=4, wrap="word", font=("Segoe UI", 11))
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
        self.extract_result = tk.Text(frame, height=8, wrap="word", font=("Segoe UI", 12))
        self.extract_result.grid(row=7, column=0, sticky="nsew")
        self.write(self.extract_result, "Тук ще се появи скритият текст.")

    def tx(self, bg: str, en: str) -> str:
        return en if self.english else bg

    def toggle_language(self) -> None:
        self.english = not self.english
        self.apply_language()

    def apply_language(self) -> None:
        kind = "en" if self.english else "bg"
        self._flag_image = ImageTk.PhotoImage(paint_flag(kind))
        self.flag_btn.configure(image=self._flag_image)
        self.book.tab(0, text=self.tx("Скрий", "Hide"))
        self.book.tab(1, text=self.tx("Извлечи", "Extract"))
        self.hide_carrier_label.configure(text=self.tx("1. Носител", "1. Carrier"))
        self.cover_button.configure(text=self.tx("Избери WAV или PNG", "Choose WAV or PNG"))
        if self.cover is None:
            self.cover_label.configure(text=self.tx("Още няма избран носител", "No carrier chosen yet"))
        self.hide_what_label.configure(text=self.tx("2. Какво да се скрие", "2. What to hide"))
        self.payload_button.configure(text=self.tx("Или избери файл", "Or choose a file"))
        self.clear_button.configure(text=self.tx("Махни файла", "Remove the file"))
        if self.payload_path is None:
            self.payload_label.configure(text=self.tx("Ако избереш файл, той се скрива вместо текста.", "If you choose a file, that file is hidden instead of the text."))
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
        selected = filedialog.askopenfilename(title=self.tx("Избери носител", "Choose a carrier"), filetypes=FILETYPES)
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
        self.payload_label.configure(text=self.tx("Ако избереш файл, той се скрива вместо текста.", "If you choose a file, that file is hidden instead of the text."))

    def choose_stego(self) -> None:
        if self.busy:
            return
        selected = filedialog.askopenfilename(title=self.tx("Избери WAV или PNG", "Choose WAV or PNG"), filetypes=FILETYPES)
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
            self.hide_idle = False
            self.write(self.hide_result, self.tx("Първо избери WAV или PNG.", "Choose a WAV or PNG first."))
            return
        password = self.hide_password.get()
        if not password:
            self.hide_idle = False
            self.write(self.hide_result, self.tx("Напиши паролата.", "Type the password."))
            return
        payload_path = self.payload_path
        text = self.secret.get("1.0", "end-1c")
        if payload_path is None and not text:
            self.hide_idle = False
            self.write(self.hide_result, self.tx("Напиши текст или избери файл.", "Type text or choose a file."))
            return
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
        data = None if payload_path is not None else text.encode("utf-8")
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
