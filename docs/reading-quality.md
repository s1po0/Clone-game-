# Reading quality — the fixes behind apk-34

Written from a real page (`Shadow Slave` ch. 2, English → Kurdish Sorani). Six concrete bugs,
each with the code that now prevents it. Everything here is covered by unit tests, so a
regression shows up in CI rather than on a phone.

## What the page showed

| Symptom | Cause | Fix |
|---|---|---|
| Kurdish text but wrong letters (`دەين` for `دەڵێن`, `ﻻنگيکم` for `لایەنێکم`) | Arabic-form letters inside Kurdish words: final `ي`, `ﻻ` ligature, `ه` for `ە` | `OcrCleanup.normaliseKurdishSpelling` + NFKC folding of presentation forms |
| Long page unreadable, only a slice visible | every page row was a fixed **420 dp** box with the aspect ratio clamped to 3.2, so a 5:1 webtoon strip lost ~40 % of its height | `ReadingLayout.scrollHeightDp` — a strip is as tall as its width implies |
| Lines overlapped / too tight | line height was a constant `sizePx * 1.16`, which ignores Kurdish dots above and below | real `Paint.fontMetrics` (ascent + descent + leading) |
| English left inside a Kurdish bubble (`what the actual fuck is this?!`) | the engine echoed the source, and the renderer drew it with the Kurdish font | `TranslatedTextCleanup.drawable` — Latin residue falls back to the original, never into a bubble |
| Some Kurdish letters drawn as empty boxes | `sans-serif-medium` relies on the phone's Arabic fallback, which varies by manufacturer | **Noto Naskh Arabic** bundled in `res/font` (covers پ ژ گ چ ڤ ۆ ێ ڕ ڵ ە — verified against the font's cmap) |
| Multi-select import shuffled the pages | the picker returns documents in tap order | `importUris` sorts by display name before numbering |

## Details worth knowing

### Strips vs pages

`ReadingLayout.isStrip(aspect >= 1.9)` decides the default; the reader offers
**fit width / one page / zoomed** in its menu, and the choice is stored in settings
(`readingMode`), so the next chapter opens the same way. A single manga panel still opens one
per screen — that is what it was designed for.

### Not drawing garbage over art

`TranslatedTextCleanup` has two rules:

1. **Latin residue** — if ≥60 % of the letters in the "translation" are Latin and the target is
   RTL, the engine did not translate. The original text is kept (tap-to-reveal), nothing is
   painted.
2. **Shouted capitals** — `ALLCAPS` runs are lowered for RTL targets, because the script has no
   case and the capitals simply look wrong.

Both are unit-tested (`TranslatedTextCleanupTest`), including the exact strings from the
screenshot.

### OcrJunk vs. art lettering

Short, vowel-less Latin caps ("PY", "SFX", "GRR") are dropped before translation — they are
sound effects and logos drawn *inside* the art. The rule is deliberately narrow (2–5 Latin
letters, no vowel at all) so `OK`, `OMG`, `AHH` and every Kurdish word survive.

### The glossary

`What?!` → `چی؟!`, `Run!` → `ڕابە!` and 32 more, applied as an overlay on the engine's output.
Stable per chapter, zero requests. (`app/src/main/java/app/mangascribe/translate/Glossary.kt`.)

## Round two (apk-37) — from the second screenshot

| Symptom | Cause | Fix |
|---|---|---|
| Page could not be **scrolled** at all | the page's drag handler consumed every touch and then discarded the pan because `scale` was 1 — the reader got no scroll | pan is only installed when zoomed (`panEnabled = scale > 1.02f`) |
| `WORTHLESS.` in a Kurdish bubble | the engine echoed the source and the pipeline accepted it | `TranslationPipeline` treats Latin residue as an engine failure and tries the next engine; the renderer refuses to paint what is left |
| Names spelled differently on every page (`شادو سلاڤە` / `شادۆ سلایڤ`) | every request transliterates freely | a **name table** in the glossary (`Shadow Slave`, `Aspect`, `Temple Slave`, `rank`, `dungeon`…), in English **and** in Japanese/Korean, so the Kurdish spelling is stable |
| Flat, dictionary-ish Kurdish from the model | the prompt only asked for numbering | `ChatPrompt.STYLE`: whole-line translation, no English left, consistent names, labels stay labels, register matches the scene |

## Still open

- **Vertical Japanese text**: detected and drawn top-to-bottom, but a rotated bubble on a
  slanted panel is still typeset horizontally.
- **Two-column layouts**: reading order is top-to-bottom; a page with side-by-side columns can
  interleave.
- **Bubble masks**: off by default now (the white box looked like a pasted overlay). A real
  inpaint — fill the bubble with its sampled paper colour and keep the outline — is the next
  visual improvement, and `PageRenderer.samplePaperColor` already exists for it.
