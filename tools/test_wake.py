"""Checks a Vosk model still recognises the wake words with the app's grammar."""
import json, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
m = Model(sys.argv[1])
g = json.dumps(["nova", "hey nova", "ok nova", "nova talk with me", "stop", "nova stop", "[unk]"])
ok = True
for f, want in (("nova1.wav", True), ("nova2.wav", True), ("other.wav", False)):
    r = KaldiRecognizer(m, 16000, g)
    w = wave.open(f, "rb")
    while True:
        d = w.readframes(1600)
        if not d:
            break
        r.AcceptWaveform(d)
    t = json.loads(r.FinalResult())["text"]
    got = "nova" in t
    print(f, repr(t), "OK" if got == want else "WRONG")
    ok &= got == want
sys.exit(0 if ok else 1)
