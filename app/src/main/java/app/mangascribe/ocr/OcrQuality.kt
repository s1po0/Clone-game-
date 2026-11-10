package app.mangascribe.ocr

import app.mangascribe.core.Languages
import app.mangascribe.core.Script

/**
 * Which reading of a page is better — asked without a human in the loop.
 *
 * The heavy OCR pass produces a second candidate; something has to choose between them, and
 * "more blocks" is the wrong answer (a noisy read finds more junk). The score weighs what a
 * reader needs:
 *
 *  - **letters that belong to the target script**: on a Kurdish page, Arabic-script characters
 *    are the reading, Latin letters are usually sfx or a misread;
 *  - **length**: weighted by a square root so several short bubbles outweigh one long misread;
 *  - **plausible punctuation**: a bubble that ends in a full stop or a question mark was read to
 *    the end;
 *  - **a penalty for junk**: two-letter fragments and lettering read off the artwork.
 *
 * Pure arithmetic, so the choice is reproducible and testable — the OCR engine itself needs a
 * device, this does not.
 */
object OcrQuality {

    fun score(result: OcrResult, languageCode: String): Double =
        scoreBlocks(result.blocks, languageCode)

    fun scoreBlocks(blocks: List<app.mangascribe.core.TextBlock>, languageCode: String): Double {
        if (blocks.isEmpty()) return 0.0
        val script = Languages.of(languageCode).script
        var total = 0.0
        for (block in blocks) {
            val text = block.text
            if (text.isBlank()) continue
            val letters = text.count { it.isLetter() }
            if (letters == 0) continue
            val inScript = when (script) {
                Script.KURDISH, Script.ARABIC -> text.count { it in '\u0600'..'\u06FF' }
                Script.JAPANESE -> text.count { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' }
                Script.KOREAN -> text.count { it in '\uAC00'..'\uD7AF' }
                Script.CHINESE -> text.count { it in '\u4E00'..'\u9FFF' }
                else -> text.count { it in 'A'..'Z' || it in 'a'..'z' }
            }
            val scriptShare = inScript.toDouble() / letters
            // ML Kit's per-block confidence is not surfaced by this TextBlock, so length and
            // script share carry the score; a short fragment is treated as low confidence.
            val confidence = if (letters >= 6) 0.85 else 0.5
            // Length matters, but with a square root: ten short bubbles should beat one long
            // misread, and they do not if length is counted linearly.
            val weight = Math.sqrt(letters.toDouble()) * (0.4 + 0.6 * scriptShare) * (0.5 + 0.5 * confidence)
            val punctuation = if (text.trimEnd().lastOrNull() in ".!?؟!،…") 1.08 else 1.0
            val junkPenalty =
                if (letters <= 2) 0.25
                else if (letters <= 4 && confidence < 0.55) 0.6
                else 1.0
            total += weight * punctuation * junkPenalty
        }
        return total
    }
}
