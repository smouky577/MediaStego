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
