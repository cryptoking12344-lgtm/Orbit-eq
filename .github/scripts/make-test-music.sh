#!/bin/bash
# Makes a few short tagged songs with album covers for the emulator test.
set -e
mkdir -p testmusic
i=0
for spec in "Neon Nights|Aurora Lane|ff7c3aed" "Neon Nights|Aurora Lane|ff7c3aed" "Desert Echo|The Dunes|ff0e7490" "Desert Echo|The Dunes|ff0e7490" "Midnight Drive|Static Hearts|ffbe123c" "Ocean Halls|Blue Coast|ff15803d" "Paper Planes|Kite Club|ffb45309"; do
  IFS='|' read -r album artist color <<< "$spec"
  i=$((i+1))
  ffmpeg -loglevel error -y -f lavfi -i "color=c=0x${color:2}:s=300x300:d=1" -frames:v 1 cover$i.png
  ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=$((220+i*55)):duration=40" -i cover$i.png \
    -map 0:a -map 1:v -c:a libmp3lame -b:a 128k -c:v png -disposition:v attached_pic -id3v2_version 3 \
    -metadata title="Track $i" -metadata artist="$artist" -metadata album="$album" -metadata track="$i" -metadata genre="Electronic" \
    "testmusic/track$i.mp3"
done
ls -la testmusic
