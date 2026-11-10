# MangaScribe — an Android manga & manhwa translator

Read manga, manhwa, manhua and comics in your own language. MangaScribe recognises the text
inside speech bubbles **on the device**, translates it, and draws the translation **back into
the page** — the balloon keeps its shape, its tilt and its place, so the page still reads like
a page instead of a wall of text in a separate box.

This is an original app built for this repository. It shares its *category* with existing manga
translators (Mangra, Manga Translator and friends) — same problem, same pipeline order — but
none of their code, names, artwork or copy. The OCR, grouping, typesetting and UI here are
written from scratch, and the recognition runs locally rather than on a server.

---

## What it does

| | |
|---|---|
| **Overlay translation** | Bubbles are found, the original lettering is masked with the bubble's own paper colour, the translation is typeset inside the same box, at the same angle. |
| **Tap to compare** | Tap any bubble to flip it back to the original text. Long-press-free, one hand. |
| **Vertical Japanese** | Column-aware OCR post-processing, furigana stripping, and right-to-left vertical typesetting when the source bubble is a column. |
| **23 languages** | Japanese, Korean, Chinese (both scripts), Arabic, Persian, **Kurdish (Sorani)**, Turkish, Russian, Ukrainian, Hindi, Bengali, Tamil, Thai, Vietnamese, Indonesian and the big European languages. RTL targets are typeset RTL. |
| **Reader** | Vertical scroll for webtoons, page height from each page's own aspect ratio, pinch-zoom, per-page progress, favourites. |
| **Whole chapters** | Translate a whole book with a progress pill; a page that fails keeps its original art and can be retried alone. |
| **Edit mode** | Retype a translation, hide a balloon, nudge or enlarge it, then save. Page-local, never rewrites the chapter. |
| **Scan a screen** | Share sheet or screenshot import: any app, game or web page picture goes through the same pipeline. |
| **Archives** | `.cbz` / `.zip` open directly. `.cbr` is detected and refused with an explanation instead of crashing. |
| **Export** | Render the translated page to PNG and share it, or copy the text out. |
| **Privacy** | Images never leave the phone. Only the recognised **text** goes to the translator you pick. Keys are Keystore-encrypted and never backed up. |
| **Keys that stay put** | A key typed once survives restarts, updates, reboots and Android killing the app. Encrypted with a device-bound Keystore key, written synchronously, and exportable to a new phone on purpose. |

## Kurdish (Sorani)

کوردیی سۆرانی is a first-class target, not an entry in a list — and it is the hardest pair in
the app, so the app is straight about it:

- **Recognition.** ML Kit has no Kurdish model. Sorani uses Arabic script, so the Arabic model
  reads it; the letters it cannot know (پ ژ گ چ ڤ ۆ ێ ڕ ڵ ە) come through, and the three it
  misreads — ك for ک, ي for ی, ھ for ه — are repaired after OCR (`OcrCleanup.normaliseKurdish`).
- **Detection.** A sentence is checked for Kurdish-only letters *before* the Arabic test, so a
  Kurdish page is never silently treated as Arabic.
- **Translation.** The **model endpoint** is the reliable route: the prompt names Central
  Kurdish (Sorani), forbids Arabic ك/ي and forbids Kurmanji in Latin letters, because a model
  told only "Kurdish" answers in Kurmanji. MyMemory is tried with `ckb`, `ku-Arab` and `ku`,
  in that order. **LibreTranslate has no Kurdish at all** — the settings screen says so
  instead of producing a failed page.
- **Typesetting.** Right-aligned lines, wrap points the Arabic shaper allows (a joined word is
  never cut in half), kashida before any further font shrinking, and RTL isolation around
  Latin words so a character name does not jump to the wrong end of the bubble.
- **Kurmanji is separate.** `ku` (Latin script) is its own entry: engines translate it more
  readily, it is never typeset RTL, and the Sorani-only rules above do not touch it.
- **Kurdish source too.** A Kurdish page can be read as well as written: Sorani is in the
  *read from* list, detected on its own letters, and its OCR letters are repaired first.
- **One tap.** The library has a کوردیی سۆرانی chip; Settings has a Kurdish panel that says
  what is missing when no engine can reach it yet.

Without a model key, the honest alternative is to target **Turkish** (MyMemory and
LibreTranslate both handle it well) and read from there.

## Engines

Four translation back-ends, picked in Settings, with automatic fallback in that order:

1. **Google Translate** — **the default.** Free, no account, every language the app offers and
   the only free engine with Kurdish Sorani. It calls the public `translate_a/single` endpoint
   Google's own page uses, one line per request, at most four at a time, with a 429/5xx retry and
   an in-memory cache so re-reading a page costs nothing. Only the recognised **text** is sent.
2. **MyMemory** — free, no key, weaker quality; kept as a fallback for when Google refuses.
3. **LibreTranslate** — point it at the public instance or your own server; bulk, two-way, free.
4. **OpenAI-compatible model** — OpenAI, Groq, OpenRouter, or a local llama.cpp server. Best for
   tone, names and jokes in long dialogue. The prompt returns one numbered line per balloon and
   the parser **refuses** a reply whose line count does not match, so a dropped line can never
   shift every bubble on the page by one.

If the first engine fails, the next one that supports the language pair is tried, and the page
says which engine produced its text. A failed page keeps its original art and can be retried on
its own.

Recognition is always ML Kit on-device (Japanese, Chinese, Korean, Devanagari and Latin models,
run together when a page mixes scripts).

## Download the APK

Every push to this branch builds a debug APK on GitHub Actions and publishes it as a release:

**https://github.com/s1po0/Clone-game-/releases/latest**

| File | Use it when |
|---|---|
| `app-arm64-v8a-debug.apk` | almost every phone made since 2016 — **start here** (~33 MB) |
| `app-armeabi-v7a-debug.apk` | older 32-bit devices (~29 MB) |
| `app-x86_64-debug.apk` | emulators and x86 tablets (~34 MB) |
| `app-universal-debug.apk` | unsure, or sideloading onto several phones (~62 MB) |

Install: download on the phone, tap the file, allow *Install unknown apps* for the browser or
file manager if Android asks, then install. Android 8.0 (API 26) or newer.

### Updates install over the old build

Every build is signed with **one key** and carries an increasing `versionCode` (the workflow run
number), and the application id never changes. So installing a new APK **replaces** the old one
and keeps your library, translations and saved keys. Do not uninstall first.

Which key that is:

| Situation | Signer | Consequence |
|---|---|---|
| the four `MANGASCRIBE_UPLOAD_*` secrets exist | **the owner's upload key** (not in this repository) | these APKs and the Play build are the same app — permanent updates |
| they do not exist (a fork, a local build) | `build-support/mangascribe-release.p12` | updates work, but only against builds from this project key |

Builds before `apk-19` were debug-signed with a fresh key each time and used the id
`app.mangascribe.debug`, so they could never update each other — Android saw a different app.
If you have one of those installed, back the library up in **Settings → Backup and moving to a
new build**, install the new build, then restore. Then uninstall the old one.

### Making the key yours

The four secrets below turn this repository into *your* app: the release APKs and the `.aab`
are then signed with your key, and only a build made with that key can ever update it.

1. Create the key once, on a machine you trust (`keytool` comes with any JDK):

   ```bash
   keytool -genkeypair -v -keystore mangascribe-upload.p12 -storetype PKCS12 \
     -alias mangascribe -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Repository → **Settings → Secrets and variables → Actions**:

   | Secret | Value |
   |---|---|
   | `MANGASCRIBE_UPLOAD_KEYSTORE_BASE64` | `base64 -w0 mangascribe-upload.p12` |
   | `MANGASCRIBE_UPLOAD_STORE_PASSWORD` | the store password you typed |
   | `MANGASCRIBE_UPLOAD_KEY_ALIAS` | `mangascribe` |
   | `MANGASCRIBE_UPLOAD_KEY_PASSWORD` | the key password you typed |

3. Push. The build prints the certificate's SHA-256 in its log — that is now your app's identity.
   Back up the `.p12` **and both passwords** in two places: losing them means no future build can
   install as an update.

Nothing about the key is committed — `*.p12`/`*.jks`/`*.keystore` are in `.gitignore`, and the key
only exists as an environment variable while the build runs. Full details, including the Play
upload steps: **[docs/play-store.md](docs/play-store.md)**.

The project key's certificate fingerprint, for checking whether an install came from here:

```
SHA-256: 5f:5f:ee:f4:59:c4:76:f0:21:89:5e:5a:77:25:28:66:ef:c3:6f:c7:9d:69:b5:65:75:ab:16:bb:76:7c:6c:f4
```

## Keys (tokens) — where they live and why they survive

If you paste a model key into **Settings → Set up a model or your own server**, this is what
happens to it:

1. **Written immediately**, synchronously, on every change: paste, background the app, return,
   and the value is still in the field and still on disk. No Save button to forget.
2. **Encrypted at rest** with an AES-256-GCM key held by the Android Keystore (hardware-backed
   on most phones). The file `secrets.bin` on its own is useless.
3. **Out of cloud backups**, deliberately: the Keystore key is device-bound, so a restored copy
   could not be decrypted anyway — the app says *"enter the key again"* instead of silently
   pretending the key is there.
4. **Not in the DataStore**: an earlier build kept the key in a plain preferences file; the app
   migrates it into the encrypted store on first launch and deletes the plaintext copy.
5. **Portable when you want it**: *Export keys* copies a bundle to the clipboard, *Import keys*
   takes it on the new phone. (The bundle is base64, not encryption — move it through a channel
   you trust, and clear the clipboard afterwards.)
6. **Never logged**: diagnostics print `model=set(48 chars, …4f2a)`, never the key.

Other apps cannot read the file (private storage), root-only tools on a rooted phone can — at
that point no app-level protection helps, which is why the app also keeps the key's scope
narrow: one endpoint, and nothing else.

## Build

Requirements: JDK 17, Android SDK 35, and a device or emulator on API 26+ — or open the folder
in Android Studio (Ladybug or newer), which downloads the SDK for you.

```bash
./gradlew :app:assembleDebug          # APKs under app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # pure-logic tests (no device needed)
./gradlew :app:installDebug           # install on a connected phone
```

CI does exactly this on every push: `.github/workflows/android-apk.yml` (JDK 17, SDK 35,
per-ABI splits, unit tests, release upload). `ci-diagnostics.txt` at the repository root is
written by that workflow when a build fails, so a compiler error or a failing test can be read
without opening the Actions log.

The pure logic — wrapping and fitting, furigana cleanup, block merging and reading order,
prompt parsing, batching, balloon JSON — has no Android dependency and is covered by unit
tests that run on a plain JVM:

```
app/src/test/java/app/mangascribe/core/TypesetTest.kt
app/src/test/java/app/mangascribe/core/OcrLogicTest.kt
app/src/test/java/app/mangascribe/core/TranslationLogicTest.kt
```

## Using it

1. **Settings → Languages**: pick what you read *from* and *to*. Japanese is the default source
   because that is where vertical columns and furigana hurt most.
2. **Library → Import pages**: choose the chapter's images, or **Open .cbz / .zip**.
   Or share images into the app from any gallery — they arrive as a one-page book.
3. In the reader, tap **Translate page** (or **Translate again** to redo it). The first page
   also gives you a built-in sample to try the pipeline with no import at all.
4. **More → Translate whole book** for a chapter, **Show original text** to compare,
   **Edit this page's text** to fix a line.
5. **Scan** tab for screenshots: Power + Volume Down, then pick the picture.

## Layout

```
app/src/main/java/app/mangascribe/
├── core/            TextBlock, languages, OCR cleanup, block merging, typesetting (pure Kotlin)
├── ocr/             ML Kit engine: model choice, upscaling, cleaning, merge pass
├── translate/       MyMemory, LibreTranslate, OpenAI-compatible, batching, prompt parsing
├── render/          Page renderer: mask, fit, rotate, vertical columns, paper-colour sampling
├── data/            Room library, settings (DataStore), page decoding, archive import
├── ui/              Compose: library grid, reader + overlay, edit sheet, scan, settings
├── vm/              One view model over the small graph
└── util/            The built-in sample page (drawn in code, not bundled art)
app/src/test/        JVM unit tests for everything that is not pixels
```

## Deliberately not here

- **Online source extensions** (Mihon/Tachiyomi-style). Scraping third-party manga sites is
  their terms-of-service problem, not a feature to clone. Import from files you already have.
- **A server-side translation proxy.** Bring your own key or your own LibreTranslate instance.
- **RAR extraction.** No licence-free RAR reader worth the binary size; convert `.cbr` → `.cbz`.
- **On-device neural translation.** It needs a 300 MB+ model per language pair. The free engines
  cover the same ground without one.

## License

MIT. The app is yours to build on.

> MangaScribe is for translating material you have the right to read and translate. Respect the
> publishers and the scanlation groups whose work you are reading.
