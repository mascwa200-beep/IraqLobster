# Tricorder — Type I

A working recreation of the hand-held unit Wah Ming Chang built for *Star Trek* in 1966: **sensor,
recorder and computer** in one black case. It is one HTML file. It has no dependencies, no build
step, no framework, no external images, no external fonts, no audio files and no network calls of
any kind.

Every reading it shows comes from the hardware in the device you are holding.

```
tricorder.html          the entire application — markup, CSS, SVG artwork, all JavaScript, all data
sw.js                   ~15 lines of cache-first service worker, so it runs with the network off
manifest.webmanifest    lets it install to a home screen; the icon is an inline SVG data URI
```

## Running it

```sh
python3 -m http.server 8000
# then open http://localhost:8000/tricorder.html
```

`localhost` counts as a secure context, so every sensor works there. Opening the file directly
with `file://` also works, but browsers refuse camera, microphone, geolocation and motion access
on `file://`, so those modes will honestly report `NO SENSOR`. To use it as a real field
instrument, serve it over https (GitHub Pages is enough) and install it — after the first load the
service worker keeps it working in airplane mode.

On iOS, motion and orientation need an explicit grant that only a tap can trigger: opening the
hood is that tap.

## Controls

| Control | What it does |
| --- | --- |
| The hood | Tap it to open the unit. The screen is on its inner face, as on the prop. |
| The latch | The metal bar on the top edge of the case — opens and closes the hood. |
| Eight mode keys | GEO, MET, BIO, EM, NAV, LOG, LIB, DIAG. Keyboard `1`–`8`. |
| Watch-crowns | 1 scan/hold · 2 mark record · 3 mark beam-down point · 4 screen insert · 5 hand lamp · 6 optical sensor · 7 acoustic sensor · 8 position sensor. |
| RANGE / GAIN | The two knurled knobs. Drag vertically; double-tap to centre. |
| Hand scanner | In the lower compartment — tap the door, then the scanner, to start a biological scan. |
| AUDIO / VOICE | Mute the synthesised warble; have the readout spoken aloud. |
| RECORD | Start and stop a voice entry. |
| LIBRARY / INFO | Query the local banks; read what every number means. |
| Ticker | Tap it for the full recorder, with export and erase. |

Keyboard: `h` hood, `space` scan/hold, `m` mark a record, `esc` close a sheet.

## What each reading physically is

| Mode | Real measurement |
| --- | --- |
| **GEO** | Magnetometer field strength in microtesla, plus the delta from a baseline — a genuine metal detector. Local gravity and tilt from the accelerometer, and an FFT of the accelerometer stream for vibration. |
| **MET** | Illumination from the ambient-light sensor where the browser exposes one, otherwise estimated from camera luminance. Colour temperature from the camera's red-to-blue ratio. Sound pressure from the microphone. |
| **BIO** | Photoplethysmography. With a fingertip over the lens and the lamp on, the camera's red channel rises and falls with each heartbeat; the trace on screen is that signal, and the pulse, variability and respiration are derived from it. **Estimates only — not a medical device.** |
| **EM** | A live FFT of the microphone across 20 Hz – 22 kHz. A strong 50 or 60 Hz component is flagged as an artificial power source. Infrared detection is a camera heuristic and is labelled as an estimate. |
| **NAV** | GPS position, elevation, accuracy and heading, plus range and bearing back to a marked beam-down point. GPS itself needs no network. |
| **LOG** | Every marked scan and voice entry, in IndexedDB on this device. |
| **LIB** | A small reference set compiled into the file: planetary classes, minerals, medical terms, terminology, and a warp-factor calculation. |
| **DIAG** | Which sensor clusters this particular device actually has. |

## Sensor support

| Sensor | Availability |
| --- | --- |
| Accelerometer / orientation | Most mobile browsers. iOS needs the tap-granted permission. |
| Magnetometer | Chrome / Chromium on Android only. Elsewhere: heading without field strength. |
| Ambient light | Chrome / Chromium only. Elsewhere: estimated from the camera. |
| Camera, microphone, geolocation | Everywhere, over https or localhost, with permission. |
| Battery | Chromium-based browsers. |
| Speech synthesis | Most platforms, using on-device voices. |

Where the hardware is missing, the unit says `NO SENSOR` rather than inventing a number. Readings
that are inferred rather than measured — illumination from the camera, colour temperature, pulse,
respiration, infrared — are labelled as estimates on the screen that shows them.

## Privacy

Nothing leaves the device. There is no server, no account, no analytics, no telemetry and no
outbound request — the only file fetched is the page itself. Camera, microphone and position are
requested only when a mode needs them, are used for computation in the page, and are never stored
or transmitted; the microphone is connected to an analyser node and never to the speakers or to a
recorder unless you press RECORD. Logs and voice entries stay in this browser and are erasable
from the recorder sheet.

## Notes on the recreation

The prop debuted in "The Man Trap" in 1966. *Tricorder* is a three-function recorder — sensing,
recording and computing — and its three default scan classes are geological, meteorological and
biological, which is where GEO, MET and BIO come from.

Details drawn from the original: the black case with its shoulder strap, the hood that pivots up
with the screen on its inner face, the upper compartment with counter-rotating moiré discs, the
lower compartment holding the detachable medical hand scanner, the three "hat pin" indicator
lamps, the watch-crown buttons, the gold fabric behind the vent, and the interchangeable screen
inserts — a plain blue-grey field for the science unit and a black one for the medical unit, which
is why the ink on screen changes with the insert.

The moiré discs are spirals rather than concentric rings, because concentric rings are
rotationally symmetric and spinning them would show nothing at all.

On screen the tricorder itself said almost nothing: a still, mostly blank field, because putting
an image on it meant an extra plate shot. What made its data readable was the **dialogue** — the
actor read the result aloud in a fixed grammar. That is reproduced literally here: every reading is
also phrased as a sentence in the ticker beneath the device, and VOICE will speak it.

All artwork is drawn in code — CSS, SVG and canvas — and every sound is synthesised with Web Audio
at runtime. No show assets are used.

*Star Trek* and related marks are trademarks of their owner. This is an unofficial, fan-made
instrument panel, built for fun.
