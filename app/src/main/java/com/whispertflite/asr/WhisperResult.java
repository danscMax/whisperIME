package com.whispertflite.asr;

public class WhisperResult {
    private final String result;
    private final String language;
    private final Whisper.Action task;
    // Distinguishes a genuine empty transcription (silence) from a failed native run: an empty result
    // with error==true means the engine crashed/aborted, so callers can surface it instead of treating
    // it as "nothing was said" (C5).
    private final boolean error;

    public WhisperResult(String result, String language, Whisper.Action task){
        this(result, language, task, false);
    }

    public WhisperResult(String result, String language, Whisper.Action task, boolean error){
        this.result = result;
        this.language = language;
        this.task = task;
        this.error = error;
    }

    /** An empty result flagged as an engine failure (not silence). */
    public static WhisperResult error(Whisper.Action task){
        return new WhisperResult("", "", task, true);
    }

    public String getResult() {
        return result;
    }

    public String getLanguage() {
        return language;
    }

    public Whisper.Action getTask() {
        return task;
    }

    public boolean isError() {
        return error;
    }
}
