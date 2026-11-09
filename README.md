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
| **22 languages** | Japanese, Korean, Chinese (both scripts), Arabic, Persian, **Kurdish (Sorani)**, Turkish, Russian, Ukrainian, Hindi, Bengali, Tamil, Thai, Vietnamese, Indonesian and the big European languages. RTL targets are typeset RTL. |
| **Reader** | Vertical scroll for webtoons, page height from each page's own aspect ratio, pinch-zoom, per-page progress, favourites. |
| **Whole chapters** | Translate a whole book with a progress pill; a page that fails keeps its original art and can be retried alone. |
| **Edit mode** | Retype a translation, hide a balloon, nudge or enlarge it, then save. Page-local, never rewrites the chapter. |
| **Scan a screen** | Share sheet or screenshot import: any app, game or web page picture goes through the same pipeline. |
| **Archives** | `.cbz` / `.zip` open directly. `.cbr` is detected and refused with an explanation instead of crashing. |
| **Export** | Render the translated page to PNG and share it, or copy the text out. |
| **Privacy** | Images never leave the phone. Only the recognised **text** goes to the translator you pick. Keys are excluded from cloud backups. |

## Engines

Three translation back-ends, picked in Settings, with automatic fallback:

1. **MyMemory** — free, no account, rate-limited. The default so a fresh install works.
2. **LibreTranslate** — point it at the public instance or your own server; bulk, two-way, free.
3. **OpenAI-compatible model** — OpenAI, Groq, OpenRouter, or a local llama.cpp server. The
   prompt returns one numbered line per balloon and the parser **refuses** a reply whose line
   count does not match, so a dropped line can never shift every bubble on the page by one.

Recognition is always ML Kit on-device (Japanese, Chinese, Korean, Devanagari and Latin models,
run together when a page mixes scripts).

## Build

Requirements: JDK 17, Android SDK 35, and a device or emulator on API 26+.

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # pure-logic tests (no device needed)
./gradlew :app:installDebug           # install on a connected phone
```

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
