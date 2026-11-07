# Standort-Tracker (Android)

Android-App, die den eigenen Standort aufzeichnet und den zurückgelegten Weg
**live als Strich auf einer Karte** (OpenStreetMap) einzeichnet.

## Funktionen

- Live-Karte mit roter Linie für den gelaufenen/gefahrenen Weg und Positionsmarker
- Aufzeichnung läuft als Vordergrund-Dienst weiter – auch bei ausgeschaltetem Bildschirm
- Benachrichtigung mit Punktanzahl, Strecke und „Stoppen“-Knopf
- Anzeige der Gesamtstrecke
- Weg wird gespeichert (`track.csv` im App-Speicher) und bleibt nach Neustart erhalten
- Karte folgt der Position; nach manuellem Verschieben wieder mit „Zentrieren“
- Kein API-Schlüssel nötig (OpenStreetMap via osmdroid)

## Bauen

Mit Android Studio öffnen und starten, oder auf der Kommandozeile
(Android SDK erforderlich):

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Bei jedem Push baut außerdem GitHub Actions die APK; sie ist im Workflow-Lauf
unter „Artifacts“ als `standort-tracker-apk` herunterladbar.

## Technik

- Kotlin, minSdk 26, targetSdk 35
- `LocationManager` (GPS + Netzwerk), kein Google-Play-Services nötig
- `LocationService`: Vordergrund-Dienst (`foregroundServiceType="location"`)
- `TrackRepository`: hält die Punkte als `StateFlow`, die `MainActivity` zeichnet sie live

---

# Beat Tiles – Rhythmus-Spiel (Android, Modul `rhythm`)

Ein Tipp-Spiel im Stil von „Magic Tiles“: Kacheln fallen in vier Spuren herunter
und müssen im Takt der Musik angetippt werden – mit **jedem Song, den du als
Audiodatei auf dem Handy hast**. Die Kacheln werden automatisch aus dem
Rhythmus des Songs erzeugt.

## Funktionen

- Song aus dem Handy-Speicher wählen (MP3, M4A/AAC, OGG, Opus, FLAC, WAV …)
  oder aus Dateimanager/Musik-App per „Teilen“ / „Öffnen mit“ an die App schicken
- **Instrument wählen**, dem die Kacheln folgen: Rhythmus (ganzer Song), Klavier, Gesang,
  Gitarre, Bass oder Schlagzeug
- **KI-Trennung der Instrumente direkt auf dem Handy** (HT-Demucs, 6 Spuren): Gesang und
  alle anderen Instrumente werden herausgefiltert, die Kacheln entstehen nur aus z. B. dem Klavier.
  Optional hört man im Spiel auch **nur das gewählte Instrument**
- Bei Melodie-Instrumenten folgen die Kacheln der Tonhöhe: höherer Ton → weiter rechts
- Automatische Noten-/Beat-Erkennung (Spectral-Flux-Onset-Erkennung mit FFT), Ergebnis wird
  gespeichert – jeder Song wird nur einmal analysiert bzw. getrennt
- Drei Schwierigkeitsgrade (Leicht / Normal / Schwer) mit unterschiedlicher
  Kacheldichte und Fallgeschwindigkeit; auf „Schwer“ auch Doppel-Kacheln
- Halte-Kacheln bei langen Tönen, Multitouch
- Bewertung Perfekt / Super / OK / Verpasst, Combo-Multiplikator bis ×4
- Ergebnis mit Note (S–D), Sternen, Genauigkeit und Rekord pro Song & Level
- Liste der zuletzt gespielten Songs (lange drücken zum Entfernen), Pause mit Countdown

## KI-Trennung

Beim ersten Mal lädt die App das KI-Modell
[HT-Demucs 6s als ONNX](https://huggingface.co/StemSplitio/htdemucs-6s-onnx) herunter
(über 100 MB, MIT-Lizenz; Original von Meta Research, ONNX-Export von
[StemSplit/demucs-onnx](https://github.com/StemSplit/demucs-onnx)). Danach läuft alles offline mit
ONNX Runtime auf dem Handy. Die Trennung dauert je nach Gerät einige Minuten pro Song und braucht
viel Arbeitsspeicher (aktuelles Mittelklasse-Handy oder besser empfohlen; maximal 12 Minuten pro
Song). Alle sechs Spuren entstehen in einem Durchgang, danach kann man ohne Wartezeit zwischen den
Instrumenten wechseln. Gespeichert wird als Audiodatei nur das gewählte Instrument (die
letzten fünf Spuren werden behalten). Das Modell lässt sich durch langes Drücken auf die
Modell-Zeile wieder löschen.

## YouTube / Spotify

Die App spielt **lokale Audiodateien**. Direkt aus YouTube oder Spotify kann sie
nicht abspielen: Spotify-Streams sind DRM-verschlüsselt, und das Herunterladen von
YouTube-Audio verstößt gegen deren Nutzungsbedingungen. Funktioniert dagegen mit
allem, was du als Datei hast – gekaufte Songs (z. B. Bandcamp, Amazon, iTunes-Downloads
ohne DRM), eigene Aufnahmen, CDs, freie Musik (z. B. YouTube Audio Library, Free Music
Archive, Jamendo).

## Bauen

```bash
./gradlew :rhythm:assembleDebug
# APK: rhythm/build/outputs/apk/debug/rhythm-debug.apk
```

GitHub Actions baut die APK bei jedem Push; Download unter „Artifacts“ als
`beat-tiles-apk`.

## Technik

- `AudioDecoder`: dekodiert mit `MediaExtractor`/`MediaCodec` zu Mono-PCM (~11 kHz)
- `BeatAnalyzer`: FFT (512 Punkte), logarithmischer Spectral Flux, adaptive Schwelle, Peak-Picking
- `StemSeparator`: lädt das Modell, trennt in 7,8-s-Abschnitten mit ¼ Überlappung und
  Dreiecks-Überblendung (wie Demucs), gibt fertige Samples sofort weiter (geringer Speicherbedarf)
- `SongPreparer`: steuert Download, Trennung und Analyse im Hintergrund
- `ChartGenerator`: wählt die stärksten Onsets mit Mindestabstand je Schwierigkeit,
  verteilt sie auf Spuren, erzeugt Halte- und Doppel-Kacheln
- `GameActivity`: `MediaPlayer` + eigene Uhr (`System.nanoTime`), die laufend an die
  Player-Position angeglichen wird; `GameView` zeichnet mit `Canvas` jedes Frame
