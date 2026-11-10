# What this app took from `s1po0/Kurd-man`

`Kurd-man` is a Python manhwa translator with a Telegram bot and a small Java Android app. It
solves a different problem (batch translation in CI, delivery by bot) but it hit the same
Kurdish-specific walls first, and three of its answers were better than what MangaScribe had.
This is the honest list: what came across, what did not, and why.

## Taken

### 1. The glossary — `app/src/main/java/app/mangascribe/translate/Glossary.kt`

Kurd-man's `manhwa/glossary.json` + `Translator.translate_one`: *"your own exact phrases always
win over any translator and cost 0 requests"*.

Comic chapters repeat lines constantly — "What?!", "Run!", "No way…" — and every free engine
translates them differently on each page. A glossary makes them stable for free. MangaScribe now:

- ships a small built-in Sorani glossary of the lines nearly every chapter contains;
- matches case- and whitespace-insensitively, and also matches the **longest prefix**, so
  `Who are you?! Come out.` still uses the entry for `Who are you?!`;
- reads the same `{"phrases": {...}}` file format, so a glossary can move between the two
  projects;
- applies as an **overlay on the finished batch** (`Glossary.overlay`), which means batching and
  retries are untouched — the engine still sees the whole page.

Deliberate difference: Kurd-man fails with "all translators failed" when a line cannot be
translated. MangaScribe keeps falling through its chain (Google → MyMemory → LibreTranslate →
LLM) and only the *known* lines are overridden, because a missing glossary entry must never cost
a page.

Tests: `GlossaryTest`, `GlossaryPipelineTest`.

### 2. The "never typeset what the font cannot draw" rule —
`core/Language.kt` → `OcrCleanup.repairPresentationalForms`

Kurd-man compares each glyph against a known-unassigned code point and *refuses* to render
characters the chosen font lacks, rather than printing empty boxes. Its `FONT_CANDIDATES` also
refuses to fall back to DejaVu/Arial, which cannot shape Arabic script at all.

MangaScribe draws with the platform shaper, so it does not need a font-picking step — but it had
the same class of bug further upstream: OCR can return **Arabic Presentation Forms**
(`U+FB50–FDFF`, `U+FE70–FEFF`: the positional shapes as separate code points) or **Private Use
Area** glyphs from "cmap hack" OCR fonts. Those travel into the translator as gibberish and into
the bubble as boxes. The repair folds shapes with NFKC and drops PUA code points, before
translation and before typesetting.

Tests: `OcrRepairTest`.

### 3. The artwork-lettering filter — `OcrCleanup.isJunk` → `looksLikeArtworkLettering`

Kurd-man's `is_probable_ocr_junk` declines to typeset two-letter fragments floating on artwork
("PY", "ORBS"), with the useful refinement that a fragment inside a detected bubble is trusted
more than one outside it.

MangaScribe's OCR has no bubble confidence at the junk-filter stage, so the rule had to be
narrow enough to be safe without one: **Latin letters only, 2–5 of them, and not a single vowel
in the whole string**. "PY", "SFX", "GRR", "HMPH" go; "OK", "OMG", "AHH", "HMM", "No!" and every
Kurdish word stay. Conservative on purpose — dropping real dialogue is worse than leaving one
sound effect untranslated.

Tests: `OcrJunkTest`.

## Not taken, and why

| Kurd-man has | Why it is not here |
|---|---|
| Tesseract + tiling + cv2 bubble contours | Android uses ML Kit on-device OCR; the tiling/quality-gate logic is already implemented natively. |
| SQLite translation cache with hit counts | MangaScribe has an LRU of 600 per engine plus the page's stored blocks; a second store would duplicate it. Worth adding later as a *cross-book* cache. |
| Meta NLLB-200 offline model | 600M–2.5 GB download on a phone; the project explicitly avoids on-device neural models. |
| Telegram bot, GitHub Actions cron | MangaScribe is a phone app: no bot, no server, no polling workflow. |
| Pillow `raqm` / `arabic_reshaper` shaping | Android's `Paint` + HarfBuzz does this natively, and MangaScribe's `RtlTypeset` already handles joining-safe wrapping and kashida. |
| Python pipeline as a subprocess | No Python runtime on the device; the logic was ported, not embedded. |

### 4. The persistent cache and the in-page dedupe — `data/TranslationCache.kt`, `translate/CachedTranslator.kt`

Kurd-man's `TranslationCache` (SQLite, keyed by provider+pair+text, with a hit counter) plus
`translate_many`'s dedupe of repeated bubbles. This was the missing answer to a real failure:
`MyMemory returned no text`, because Google had rate-limited the page and every retry asked the
same servers again.

MangaScribe now:

- keeps every finished line in `translations-cache.db`, keyed by **engine + pair + text**, so
  switching engines does not return the previous engine's answer;
- trims by least-recently-used at 60 000 rows;
- sends a page's distinct lines only, mapping results back to balloon order;
- lets a re-read, a retry, or the next chapter work **with no network at all** for anything seen;
- shows the count in **Settings → Translation memory**, with a Clear button.

### 5. "Name what failed, then say what to do"

Kurd-man ends with `هەموو وەرگێڕەکان شکستیان هێنا — {err}`. MangaScribe's version of that idea
is `translate/EngineFailures.kt`: a reader-facing paragraph naming the engines tried and pointing
at the one action that helps (for Kurdish: Google Translate, or a model key). The raw MyMemory
message was accurate and misleading — MyMemory is the fallback and has no Kurdish at all.

## What is still worth stealing later

- **A user-editable glossary** in Settings (JSON import/export), so names and catchphrases can be
  fixed per series. The data model is ready: `Glossary.parse` already reads the file format.
- **Cross-book translation cache** keyed by (engine, source, text) — Kurd-man's `hits` counter is
  a reminder that repeated lines are the bulk of a long series.
- **Sentence-boundary splitting** in `BatchPlanner`: its `split_long_text` breaks on `.!?؟…`
  instead of at an arbitrary character limit.
