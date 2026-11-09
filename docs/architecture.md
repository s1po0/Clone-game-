# Architecture

The app is one module and one activity. The point of the split is testability: everything that
decides *what* the page says or *where* the letters go is plain Kotlin with no Android types,
so it runs in a JVM unit test; everything that touches pixels or the network is a thin edge.

```
LibraryViewModel ──► LibraryRepository ──► MlKitOcrEngine  (device)
   (Compose)              │                 TranslationPipeline ──► Http (OkHttp)
                          ├─ Room: BookDao, PageDao rows (balloons as JSON)
                          ├─ SettingsStore (DataStore; keys kept out of backups)
                          └─ PageRenderer (bake to PNG) / overlay in Compose
```

## The page pipeline

1. **Decode** ([PageImages]): sample to ~1400 px wide for OCR, ~1600 px for reading. A 3000 px
   webtoon strip decoded at full size is an instant OOM on a mid-range phone.
2. **Recognise** ([MlKitOcrEngine]): pages under 1100 px are upscaled first — the single
   largest accuracy win on screenshots. Japanese pages run the Japanese model plus Latin for
   sfx; the Latin pass is skipped when the script model already found plenty.
3. **Clean** ([OcrCleanup]): furigana stripping (kana runs wedged between kanji groups),
   de-hyphenation of words split across lines, noise glyph trimming, junk dropping (page
   numbers, punctuation-only detections).
4. **Group** ([BlockMerger]): ML Kit returns one block per *line*; a balloon with three lines is
   three blocks. Blocks join by a union-find over proximity + matching tilt, then sort into
   reading order — columns left-to-right for Japanese, right-to-left for Arabic, top-to-bottom
   for Latin.
5. **Translate** ([TranslationPipeline]): batched, numbered, one line per balloon. A model reply
   with the wrong line count is rejected rather than mis-mapped.
6. **Typeset** ([TextFitter] / [PageRenderer]): greedy wrap with CJK per-character breaks, then
   font size steps down until the block fits; the mask uses the bubble's sampled paper colour
   and a rounded shape so dark panels do not get white rectangles.

## Decisions worth knowing

- **Balloons live in one JSON column per page**, not a table per balloon. A page read is one
  row; edits save the whole page; there is no N+1 and no migration pain as the model grows.
- **The page's `status` is the state machine**: `none → running → done|failed`. A failed page
  keeps its original artwork and shows a retry, so one bad page never blocks a chapter.
- **Retranslate does not re-run OCR.** The source text is already stored; only the translation
  step repeats, which makes "try another language" a two-second action.
- **Vertical typesetting is a flag, not a separate renderer**: the fitted text is drawn column
  by column when the source bubble was a column and the target expects it.
- **Keys are DataStore entries excluded from cloud backup** (`data_extraction_rules.xml`).
