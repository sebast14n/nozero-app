package com.example.logger

/**
 * Stare live partajata intre RecordingService / AudioSegmentRecorder si ecranul de monitorizare.
 * Doar campuri @Volatile (citite din UI la ~20Hz, scrise din firul de captura) — fara alocari,
 * cost neglijabil. Ecranul de monitor consuma baterie DOAR cat e deschis; inregistrarea in sine
 * nu e afectata de existenta acestei stari.
 */
object LiveState {
    @Volatile var active = false          // serviciul de inregistrare ruleaza
    @Volatile var recordingNow = false    // segment audio activ ACUM (false = pauza de program)
    @Volatile var level = 0f              // nivel semnal instant, 0..1 (peak)
    @Volatile var lastAudioMs = 0L        // cand am primit ultima data esantioane (staleness check)
    @Volatile var micLabel = "—"          // microfon curent (etichetat)
    @Volatile var mode = ""               // "Transect" / "Senzor fix"
    @Volatile var scheduled = false       // urmeaza program nocturn
    @Volatile var sampleRate = 48000
    @Volatile var segmentCount = 0        // cate segmente s-au pornit in sesiune
    @Volatile var sessionStartMs = 0L
    @Volatile var statusHint = ""         // ex "In afara ferestrei — astept apusul"

    fun reset() {
        active = false; recordingNow = false; level = 0f; lastAudioMs = 0L
        micLabel = "—"; mode = ""; scheduled = false; segmentCount = 0
        sessionStartMs = 0L; statusHint = ""
    }
}
