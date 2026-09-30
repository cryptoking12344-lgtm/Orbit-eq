# Orbit EQ

An Android equalizer with a system-wide EQ and an 8D / 3D-surround music player.

## What's inside

**Equalizer tab (works on Spotify, YouTube, etc.)**
- Draggable 10-band EQ curve (31 Hz – 16 kHz, ±12 dB) with 11 genre presets
- Bass boost, 3D Surround (Atmos-style), loudness, preamp
- Reverb: Room, Studio, Club, Hall, Arena
- Live spectrum visualizer (asks for microphone permission, which Android requires to read the audio output — nothing is recorded)

**8D Player tab (songs on your phone)**
- 8D audio: the song circles around your head, with adjustable speed and strength
- Uses the same EQ, bass, surround and reverb settings, run by the app's own audio engine
- Background playback with notification controls; pauses when headphones unplug

**Headphones tab**
- Starting-point tunings for earbuds, TWS, over-ear, open-back, neckband, gaming headsets
- Save your own profiles
- Link a profile to your headphones so it loads automatically when they connect

**Quick-settings tile** — pull down the notification shade → edit tiles → add "Orbit EQ".

## Good to know
- Real Dolby Atmos is licensed and can't be added to third-party apps. The "3D Surround" here is our own virtualizer that gives a similar wide, out-of-head sound.
- 8D only works in the built-in player. Android doesn't let non-root apps reshape other apps' audio that deeply.
- Some phones (a few Samsung/Xiaomi builds) block system-wide effects. The app shows a message if that happens; the 8D Player always works.

## Build the APK with GitHub Actions (from your phone, using Termux)

1. Create an empty repo on github.com (e.g. `orbit-eq`). Don't add a README.
2. Create a token: GitHub → Settings → Developer settings → Personal access tokens → Fine-grained → give it **Contents: Read and write** and **Workflows: Read and write** for that repo.
3. In Termux:

```bash
pkg install git unzip -y
termux-setup-storage
cd ~
unzip /sdcard/Download/OrbitEQ.zip
cd OrbitEQ
git init -b main
git add .
git commit -m "Orbit EQ first version"
git remote add origin https://github.com/YOUR_USERNAME/orbit-eq.git
git push -u origin main
```
When it asks for a password, paste the token.

4. Open the repo → **Actions** tab → wait ~5 minutes for "Build APK" to turn green.
5. Open the repo → **Releases** → download `OrbitEQ.apk` → install it (allow "install unknown apps" for your browser).

Every time you push changes, a new APK appears in Releases.

## Tech
Kotlin, minSdk 28 (Android 9+), targetSdk 34. System-wide effects use `DynamicsProcessing`, `Virtualizer` and `PresetReverb` on the global session plus any session music apps announce. The player decodes with `MediaCodec` and runs a custom float DSP chain (biquad EQ → bass shelf → 8D panner with ITD and head shadow → mid/side surround with early reflections → Freeverb-style reverb → limiter) into `AudioTrack`.
