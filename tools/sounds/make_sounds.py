#!/usr/bin/env python3
"""Synthesize Medwi's built-in reminder sounds into app/src/main/res/raw.

The tones are generated from scratch (sine partials + envelopes), so they are our own
work under the project's license. Only the standard library is used.

Usage: python tools/sounds/make_sounds.py
"""
import math
import struct
import wave
from pathlib import Path

RATE = 22050
OUT = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res" / "raw"


def note(freq, dur, partials=((1, 1.0),), attack=0.005, decay=4.0, vol=0.6):
    """One struck note: a sum of partials with a fast attack and exponential decay."""
    n = int(RATE * dur)
    out = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / attack) * math.exp(-decay * t)
        s = sum(a * math.sin(2 * math.pi * freq * m * t) for m, a in partials)
        out.append(vol * env * s)
    return out


def mix(total, events):
    """Place (start_seconds, samples) events on a timeline of [total] seconds."""
    buf = [0.0] * int(RATE * total)
    for start, samples in events:
        o = int(RATE * start)
        for i, s in enumerate(samples):
            if o + i < len(buf):
                buf[o + i] += s
    return buf


def write(name, samples):
    peak = max(abs(s) for s in samples) or 1.0
    gain = 0.85 / peak
    # Short fade-out so the file never ends on a click.
    fade = int(RATE * 0.03)
    for i in range(fade):
        samples[-1 - i] *= i / fade
    OUT.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT / f"{name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s * gain)) * 32767)) for s in samples))
    print(f"{name}.wav  {len(samples) / RATE:.2f}s")


BELL = ((1, 1.0), (2.76, 0.35), (5.4, 0.15))
MARIMBA = ((1, 1.0), (4, 0.25), (10, 0.05))
SOFT = ((1, 1.0), (2, 0.15))


def main():
    # Chime: rising major arpeggio (C6 E6 G6).
    write("medwi_chime", mix(1.6, [
        (0.00, note(1046.5, 1.2, BELL, decay=3.5)),
        (0.15, note(1318.5, 1.2, BELL, decay=3.5)),
        (0.30, note(1568.0, 1.3, BELL, decay=3.0)),
    ]))
    # Bell: two bright strikes.
    write("medwi_bell", mix(1.8, [
        (0.00, note(880.0, 1.6, BELL, decay=2.5)),
        (0.45, note(880.0, 1.3, BELL, decay=2.5)),
    ]))
    # Soft: gentle low two-note "ding-dong".
    write("medwi_soft", mix(1.8, [
        (0.00, note(659.3, 1.2, SOFT, attack=0.03, decay=2.5, vol=0.5)),
        (0.50, note(523.3, 1.3, SOFT, attack=0.03, decay=2.5, vol=0.5)),
    ]))
    # Pulse: three short urgent beeps.
    write("medwi_pulse", mix(1.0, [
        (t, note(1760.0, 0.16, ((1, 1.0), (3, 0.2)), attack=0.004, decay=12.0)) for t in (0.0, 0.25, 0.5)
    ]))
    # Marimba: little four-note phrase.
    write("medwi_marimba", mix(1.4, [
        (0.00, note(784.0, 0.6, MARIMBA, decay=7.0)),
        (0.14, note(987.8, 0.6, MARIMBA, decay=7.0)),
        (0.28, note(1174.7, 0.6, MARIMBA, decay=7.0)),
        (0.42, note(1568.0, 0.9, MARIMBA, decay=5.0)),
    ]))


if __name__ == "__main__":
    main()
