package com.whispertflite.asr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TranscriptTest {

    @Test
    public void stripsWholeNonSpeechMarkers() {
        // The exact case that made the voice provider return garbage and close.
        assertEquals("", Transcript.clean("[BLANK_AUDIO]"));
        assertEquals("", Transcript.clean("[MUSIC]"));
        assertEquals("", Transcript.clean("  [ Music ] "));
        assertEquals("", Transcript.clean("(applause)"));
    }

    @Test
    public void keepsSpeechAndDropsInlineMarkers() {
        assertEquals("привет мир", Transcript.clean("привет [MUSIC] мир"));
        assertEquals("hello world", Transcript.clean("hello world [BLANK_AUDIO]"));
    }

    @Test
    public void leavesPlainTextUntouched() {
        assertEquals("just a sentence", Transcript.clean("just a sentence"));
        assertEquals("", Transcript.clean(""));
        assertEquals("", Transcript.clean(null));
    }
}
