#!/bin/bash
# Runs inside the emulator job: installs the APK, taps through the app, takes screenshots.
set -x
mkdir -p shots
shot() { sleep 1; python3 .github/scripts/ui.py dismiss; adb exec-out screencap -p > "shots/$1.png"; }
ui() { python3 .github/scripts/ui.py "$@"; }

adb install -r OrbitEQ.apk
adb shell pm grant com.ck.orbiteq android.permission.READ_MEDIA_AUDIO || true
adb shell pm grant com.ck.orbiteq android.permission.POST_NOTIFICATIONS || true
adb shell mkdir -p /sdcard/Music/OrbitTest
adb push testmusic/. /sdcard/Music/OrbitTest/
adb shell content call --uri content://media --method scan_volume --arg external_primary || true
sleep 8
adb shell settings put global window_animation_scale 0.5
adb logcat -c
# the emulator home app sometimes freezes on first boot; park it behind a blank activity
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true

adb shell am start -n com.ck.orbiteq/.MainActivity
sleep 8; shot 01_library_songs
ui tap text Albums; sleep 3; shot 02_albums_flow
ui tap id flowView 0.5 0.44; sleep 3; shot 03_album_flipped
ui tap id tsTitle 0.5 0.5 1; sleep 4; shot 04_song_playing
ui tap id panelClose; sleep 2
ui tap text Wheel; sleep 2; shot 05_wheel
ui tap text Stack; sleep 2; shot 06_stack
ui tap text Glide; sleep 2; shot 07_glide
ui tap text "Cover Flow"; sleep 1
ui tap id nav_player; sleep 3; shot 08_now_playing
adb shell input swipe 540 1600 540 500 400; sleep 2; shot 09_player_sound_mode
ui tap id nav_eq; sleep 3; shot 10_equalizer
adb shell input swipe 540 1600 540 400 400; sleep 2; shot 11_eq_amp
adb shell input swipe 540 500 540 2000 300; adb shell input swipe 540 500 540 2000 300; sleep 1
ui tap id btnSavePreset; sleep 3; shot 12_save_preset
adb shell input keyevent BACK; sleep 1
ui tap id btnAppearance; sleep 3; shot 13_appearance
ui tap text Retro; sleep 5; shot 14_retro_eq
ui tap id nav_player; sleep 3; shot 15_retro_player
ui tap id nav_library; sleep 3; shot 16_retro_library
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 4
ui tap text "Got it"; sleep 2; shot 17_landscape_flow
ui tap id flowView 0.5 0.42; sleep 3; shot 18_landscape_flipped
adb shell input keyevent BACK; sleep 1
adb shell settings put system user_rotation 0; sleep 4
ui tap id btnAppearance; sleep 3
ui tap text Midnight; sleep 5; shot 19_midnight_library
ui tap id nav_eq; sleep 3; shot 20_midnight_eq
adb shell am start -n com.ck.orbiteq/.widget.WidgetConfigActivity --ei appWidgetId 999; sleep 4; shot 21_widget_styles
adb shell input keyevent BACK; sleep 1
adb shell dumpsys media_session | grep -A3 -i "orbit" > media_session.txt || true

adb logcat -d > logcat.txt
grep -n -A 40 "FATAL EXCEPTION" logcat.txt > crashes.txt || true
grep -n "AndroidRuntime\|ANR in com.ck" logcat.txt | head -50 >> crashes.txt || true
echo "crash lines: $(wc -l < crashes.txt)"
exit 0
