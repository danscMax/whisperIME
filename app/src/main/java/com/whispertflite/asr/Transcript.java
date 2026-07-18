package com.whispertflite.asr;

import java.util.regex.Pattern;

/**
 * Cleans a raw whisper transcription.
 *
 * <p>Whisper emits non-speech markers — {@code [BLANK_AUDIO]}, {@code [MUSIC]}, {@code [NOISE]},
 * {@code (wind blowing)} and the like — for silence, music or noise. They are annotations, not
 * words, and are the same in both engines. Left in, they leak into the text field: the voice
 * provider treated {@code "[BLANK_AUDIO]"} as a real result and returned it (then closed), and they
 * showed up verbatim in history. Strip them at the one place every consumer reads — the engines
 * that build {@link WhisperResult} — so an all-marker result becomes empty and is handled as
 * "nothing was said".
 */
public final class Transcript {

    // A bracketed or parenthesised annotation, e.g. [BLANK_AUDIO], [ Music ], (applause).
    // Bounded length so a real sentence that happens to contain brackets isn't swallowed whole.
    private static final Pattern NON_SPEECH =
            Pattern.compile("[\\[(][^\\])]{0,40}[\\])]");

    private Transcript() {}

    /** Remove whisper's non-speech markers and tidy whitespace; may return "". */
    public static String clean(String raw) {
        if (raw == null) return "";
        String cleaned = NON_SPEECH.matcher(raw).replaceAll(" ");
        // Collapse the gaps the removals leave, and drop stray leading punctuation.
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        return cleaned;
    }
}
