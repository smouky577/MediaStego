# MediaStego

Скрива и извлича **криптиран** текст или файл в най-младшия бит (LSB) на 16-bit PCM WAV или на 8-битов RGB PNG. Двете програми говорят един и същ формат: CLI на Python и приложение за Android.

Няма разпознаваеми маркери във файла. Без правилната парола съдържанието не се отличава от обикновен шум в младшия бит.

## Свали приложението

Готовият APK е в [Releases](https://github.com/smouky577/MediaStego/releases). Свали `MediaStego.apk` и го отвори на телефона. Android ще поиска разрешение за инсталиране извън магазина.

## Важно

Пращай резултата като **файл / документ**, не като гласово съобщение и не като снимка в чат.

Viber, WhatsApp и Telegram **компресират** гласовите съобщения (прекодират ги в opus/aac) и снимките (често до JPEG). Това сменя пробите или пикселите и **унищожава LSB**. Получателят трябва да получи същия WAV или PNG, байт по байт: документ, имейл, USB, облак.

- WAV: бутонът **Сподели** праща `application/octet-stream`, за да не го хване месинджърът като гласово.
- PNG: **Сподели** праща точно `image/png`, не `image/*`. В чата пак избери **документ**, не снимка. Снимка в Telegram, WhatsApp или Messenger се прекодира и скритото изчезва.

Носителят е **PCM 16-bit WAV** (моно или стерео) или **PNG с 8 бита на канал, RGB или RGBA**. При RGBA се ползват само R, G и B; alpha не се пипа и не се композира. JPEG, MP3, AAC, OGG, палитра, сиво и 16-битов PNG се отказват. След вграждане картината не се намалява и не сменя цветовия режим.

## MediaStego на лаптопа

`MediaStego.bat` отваря прозорец с два екрана, като приложението на телефона.

**Скрий** избира WAV или PNG, текст или файл и парола, после записва нов файл. Оригиналът не се презаписва.

**Извлечи** избира готовия файл и паролата. Текстът се показва в прозореца. Ако вътре е файл, записва го до оригинала.

## Python (Windows)

Иска се Python 3.11+.

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r python\requirements.txt
```

Скриване на текст или файл:

```powershell
.\.venv\Scripts\python -X utf8 python\hide.py embed cover.wav secret.wav "моя парола" -t "скрит текст"
.\.venv\Scripts\python -X utf8 python\hide.py embed cover.wav secret.wav "моя парола" -f бележка.pdf
.\.venv\Scripts\python -X utf8 python\hide_png.py embed cover.png secret.png "моя парола" -t "скрит текст"
.\.venv\Scripts\python -X utf8 python\hide_png.py embed cover.png secret.png "моя парола" -f бележка.pdf
```

Извличане. Ако байтовете са валиден UTF-8, текстът се печати. Иначе се записва файл (`extracted.bin`, освен ако не подадеш `-o`):

```powershell
.\.venv\Scripts\python -X utf8 python\hide.py extract secret.wav "моя парола"
.\.venv\Scripts\python -X utf8 python\hide_png.py extract secret.png "моя парола"
.\.venv\Scripts\python -X utf8 python\hide_png.py extract secret.png "моя парола" -o out.bin
```

`-X utf8` трябва, за да минат български букви през конзолата на Windows. За файл с `-f` кодировката на конзолата няма значение.

Грешна парола: `Грешна парола или няма съобщение`. По-голям payload от капацитета: `Твърде голямо: нужни N бита, налични M`. JPEG: `JPEG е със загуби — не ползвай като носител, конвертирай в PNG`.

`hide.py` и `hide_png.py` споделят `stego_core.py` (ключ, AES-GCM, позиции). Различава се само как се зарежда плоският масив `s[]`.

## Android

- Android Studio с JDK 17+ (вграденото JBR на Studio)
- Android SDK 37, Build-Tools 36
- minSdk 26, Jetpack Compose, без външни крипто библиотеки
- Пакетът остава `com.audiostego`, името на иконата е MediaStego

Отвори папката `android/`. Ако `gradle.properties` сочи към друго JDK, смени `org.gradle.java.home`. `local.properties` със `sdk.dir` го създава Studio.

Екран **Скрий**: WAV или PNG (по съдържание, не само по разширение), текст или файл или кратък глас (OGG/OPUS, 16 kbps, от Android 10 нагоре), парола, запис, после **Сподели**.

Екран **Извлечи**: WAV или PNG, парола. Текстът се показва; ако данните не са UTF-8, се записват като файл (`.ogg`, ако е записът от приложението).

Работата с файла е през Storage Access Framework. Четенето, PBKDF2 и битовете вървят във фонов поток, с прогрес. Гласът се записва с `MediaRecorder` и се крие като файл, не като „гласово съобщение“. PNG пикселите идват от `Bitmap` ARGB_8888 без premultiplied alpha; пишат се само R, G и B.

## Съвместимост

`python/test_compat.py` прави WAV и PNG с **фиксирани** salt и nonce, после:

1. Python скрива и извлича (моно текст, стерео двоичен файл, точно пълен капацитет, WAV с чужд `JUNK` чънк, RGB PNG).
2. Записва файловете и пуска JVM теста на същия `StegoCore`, който влиза в приложението. На JVM `Bitmap` е празен, затова тестът чете PNG през същия филтър 0–4, който валидира байтовете; на телефон пикселите минават през `Bitmap`.
3. Сравнява байтовете на двата WAV. За PNG сравнява пикселите R,G,B (контейнерът на Pillow и на Android не е един и същ) и извлича с Python това, което е записал Android кодът.

```powershell
.\.venv\Scripts\python -X utf8 python\test_compat.py
```

Първият Gradle пуск сваля зависимости и може да отнеме няколко минути. Тестът на телефона (`connectedDebugAndroidTest`) ползва същите файлове от `android/app/src/androidTest/assets`, ако има вързано устройство. След него приложението се деинсталира — инсталирай отново `android\app\build\outputs\apk\debug\app-debug.apk`, ако иконата изчезне.

## Формат

И двете страни правят следното, без отклонения. `s[0..N-1]` е плосък масив от цели числа. Пише се и се чете само LSB.

- WAV: interleaved int16 PCM, моно или стерео, в реда на файла. Друг формат се отказва.
- PNG: само R, G, B (без alpha), ред по ред, пиксел по пиксел, ред R, G, B. По 8 бита на канал.

Ключ и криптиране:

- `PBKDF2-HMAC-SHA256`, парола в UTF-8, 200000 итерации, 64 байта. Salt е 16 случайни байта. `ekey` са първите 32 (AES-GCM), `pkey` — последните 32 (позициите).
- `AES-256-GCM`, nonce 12 байта, без AAD. `body = nonce || ciphertext || tag`.
- 160-битов хедър в LSB на `s[0..159]`, MSB first: `salt(16)` + `uint32` big-endian дължина на `body`.
- Битовeте на `body` (MSB first) отиват в LSB от индекс 160 нагоре. Позициите са частичен Fisher-Yates върху пул `M = N - 160`. Случайните числа са `uint32` big-endian от `AES-256-CTR(pkey, IV = 16 нулеви байта)`.
- Ако `body` не се побира, операцията спира с грешка. Няма magic bytes.

## English

MediaStego hides and extracts **encrypted** text or a file in the least significant bit (LSB) of a 16-bit PCM WAV or an 8-bit RGB PNG. The Python tools and the Android app use the same format.

The file has no marker. Without the password the payload is indistinguishable from ordinary noise in the lowest bit. The password is required. It selects the bit positions and decrypts the message.

### Download

The ready APK is on [Releases](https://github.com/smouky577/MediaStego/releases). Download `MediaStego.apk` and open it on the phone. Android will ask for permission to install apps from outside the store.

### Important

Send the result as a **file / document**, never as a voice message or a chat photo.

Viber, WhatsApp, and Telegram **recompress** voice messages (to opus/aac) and photos (often to JPEG). That changes the samples or pixels and **destroys the LSB**. The recipient must receive the same WAV or PNG, byte for byte: a document, email, USB, or cloud storage.

- WAV: **Share** sends `application/octet-stream`, so a messenger does not treat it as a voice note.
- PNG: **Share** sends exactly `image/png`, not `image/*`. In a chat, still choose Document, not Photo. A photo in Telegram, WhatsApp, or Messenger is recompressed and the hidden data disappears.

The carrier is a **16-bit PCM WAV** (mono or stereo) or an **8-bit-per-channel PNG, RGB or RGBA**. RGBA uses only R, G, and B. Alpha is left untouched and is not composited. JPEG, MP3, AAC, OGG, palette, grayscale, and 16-bit PNG are rejected. After embedding, the picture is not downscaled and its color mode is not changed.

### MediaStego on the laptop

`MediaStego.bat` opens a window with the same two screens as the phone app.

**Скрий** picks a WAV or PNG, then text or a file and a password, and saves a new file. The original is not overwritten.

**Извлечи** picks that file and the password. Text appears in the window. A non-text payload is saved next to the original.

### Python (Windows)

Python 3.11 or newer is required.

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r python\requirements.txt
```

Hide text or a file:

```powershell
.\.venv\Scripts\python -X utf8 python\hide.py embed cover.wav secret.wav "my password" -t "hidden text"
.\.venv\Scripts\python -X utf8 python\hide.py embed cover.wav secret.wav "my password" -f note.pdf
.\.venv\Scripts\python -X utf8 python\hide_png.py embed cover.png secret.png "my password" -t "hidden text"
.\.venv\Scripts\python -X utf8 python\hide_png.py embed cover.png secret.png "my password" -f note.pdf
```

Extract. If the bytes are valid UTF-8, the text is printed. Otherwise it is written to a file (`extracted.bin`, unless you pass `-o`):

```powershell
.\.venv\Scripts\python -X utf8 python\hide.py extract secret.wav "my password"
.\.venv\Scripts\python -X utf8 python\hide_png.py extract secret.png "my password"
.\.venv\Scripts\python -X utf8 python\hide_png.py extract secret.png "my password" -o out.bin
```

`-X utf8` is needed so Bulgarian letters pass through the Windows console. It does not matter for a file passed with `-f`.

Wrong password: `Грешна парола или няма съобщение`. Payload larger than the carrier: `Твърде голямо: нужни N бита, налични M`. JPEG: `JPEG е със загуби — не ползвай като носител, конвертирай в PNG`.

`hide.py` and `hide_png.py` share `stego_core.py` (key, AES-GCM, positions). Only the way the flat array `s[]` is loaded differs.

### Android

- Android Studio with JDK 17+ (Studio's bundled JBR)
- Android SDK 37, Build-Tools 36
- minSdk 26, Jetpack Compose, no third-party crypto libraries
- The package stays `com.audiostego`. The launcher name is MediaStego

Open the `android/` folder. If `gradle.properties` points at another JDK, change `org.gradle.java.home`. Studio creates `local.properties` with `sdk.dir`.

**Скрий**: a WAV or PNG (detected by content, not only by extension), text or a file or a short voice note (OGG/OPUS, 16 kbps, Android 10 and newer), a password, save, then **Сподели**.

**Извлечи**: a WAV or PNG and the password. Text is shown. If the data is not UTF-8, it is saved as a file (`.ogg` when it is a recording made by the app).

Files go through the Storage Access Framework. Reading, PBKDF2, and the bits run on a background thread, with progress. Voice is recorded with `MediaRecorder` and hidden as a file, not as a voice message. PNG pixels come from a `Bitmap` in ARGB_8888 with no premultiplied alpha. Only R, G, and B are written.

### Compatibility

`python/test_compat.py` builds WAV and PNG files with a **fixed** salt and nonce, then:

1. Python hides and extracts (mono text, stereo binary, a carrier filled to capacity, a WAV with a foreign `JUNK` chunk, and an RGB PNG).
2. It saves the files and runs the JVM test of the same `StegoCore` that ships in the app. On the JVM, `Bitmap` is a stub, so the test reads PNG through the same filters 0–4 that check the bytes. On a phone, pixels go through `Bitmap`.
3. It compares the two WAV files byte for byte. For PNG it compares the R, G, and B pixels (the Pillow container and the Android container are not the same file) and extracts with Python what the Android code wrote.

```powershell
.\.venv\Scripts\python -X utf8 python\test_compat.py
```

The first Gradle run downloads dependencies and can take several minutes. The phone test (`connectedDebugAndroidTest`) uses the same files from `android/app/src/androidTest/assets` when a device is connected. That test uninstalls the app. Install `android\app\build\outputs\apk\debug\app-debug.apk` again if the icon disappears.

### Format

Both sides do the following, with no deviations. `s[0..N-1]` is a flat array of integers. Only the LSB is read and written.

- WAV: interleaved int16 PCM, mono or stereo, in file order. Any other format is rejected.
- PNG: only R, G, B (no alpha), row by row, pixel by pixel, in order R, G, B. 8 bits per channel.

Key and encryption:

- `PBKDF2-HMAC-SHA256`, password as UTF-8, 200000 iterations, 64 bytes. The salt is 16 random bytes. `ekey` is the first 32 (AES-GCM). `pkey` is the last 32 (positions).
- `AES-256-GCM`, a 12-byte nonce, no AAD. `body = nonce || ciphertext || tag`.
- A 160-bit header in the LSB of `s[0..159]`, MSB first: `salt(16)` plus a big-endian `uint32` length of `body`.
- The bits of `body` (MSB first) go into the LSB from index 160 upward. Positions are a partial Fisher-Yates shuffle over the pool `M = N - 160`. The random numbers are big-endian `uint32` values from `AES-256-CTR(pkey, IV = 16 zero bytes)`.
- If `body` does not fit, the operation stops with an error. There are no magic bytes.
