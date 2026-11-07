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
Audiodatei auf dem Handy hast**.

## Zwei Arten, Kacheln zu bekommen

**Selbst eintippen** (Standard): Der Song läuft einmal durch, und du tippst irgendwo auf den
Bildschirm, wann immer eine Kachel kommen soll. Finger länger halten ergibt eine lange
Halte-Kachel, mehrere Finger gleichzeitig eine Doppel-Kachel. Mit „Letzte löschen“ lässt sich
ein Fehltipp zurücknehmen. Danach startet das Spiel: Die Kacheln tauchen genau zu deinen
Zeitpunkten **an zufälligen Stellen auf dem Bildschirm** auf – bei jedem Spielen neu ausgelost.
Ein Rahmen schrumpft auf die Kachel zu; wenn er sie erreicht, wird getippt. Lange Kacheln
(„HALTEN“) bleiben gedrückt, bis sie sich gefüllt haben. Die Aufnahme wird
gespeichert und kann jederzeit neu eingetippt werden.

**Automatisch**: Die App erkennt die Beats des Songs (Spectral-Flux-Onset-Erkennung mit FFT)
und setzt die Kacheln selbst.

## Funktionen

- Song aus dem Handy-Speicher wählen (MP3, M4A/AAC, OGG, Opus, FLAC, WAV …)
  oder aus Dateimanager/Musik-App per „Teilen“ / „Öffnen mit“ an die App schicken
- Drei Schwierigkeitsgrade (Leicht / Normal / Schwer): Fallgeschwindigkeit, im
  Automatik-Modus auch Kacheldichte und Doppel-Kacheln
- Halte-Kacheln, Multitouch
- Bewertung Perfekt / Super / OK / Verpasst, Combo-Multiplikator bis ×4
- Ergebnis mit Note (S–D), Sternen, Genauigkeit und Rekord pro Song, Modus & Level
- Liste der zuletzt gespielten Songs (lange drücken zum Entfernen), Pause mit Countdown
- Kein Internet nötig

## YouTube / Spotify

Die App spielt **lokale Audiodateien**. Direkt aus YouTube oder Spotify kann sie
nicht abspielen: Spotify-Streams sind DRM-verschlüsselt, und das Herunterladen von
YouTube-Audio verstößt gegen deren Nutzungsbedingungen.

## Bauen

```bash
./gradlew :rhythm:assembleDebug
# APK: rhythm/build/outputs/apk/debug/rhythm-debug.apk
```

GitHub Actions baut die APK bei jedem Push; Download unter „Artifacts“ als
`beat-tiles-apk`.

## Technik

- `RecordActivity`/`RecordView`: Aufnahme der Taps mit Songzeit (Verarbeitungsverzögerung über
  den Zeitstempel des Touch-Ereignisses herausgerechnet)
- `SpotView`: Spielfläche des Eintipp-Modus; verteilt die Kacheln zufällig, ohne dass sich
  gleichzeitig sichtbare überdecken, schnelle Folgen liegen nah beieinander
- `AudioDecoder` + `BeatAnalyzer` + `ChartGenerator.generate`: automatischer Modus
- `GameActivity`: `MediaPlayer` + eigene Uhr (`System.nanoTime`), die laufend an die
  Player-Position angeglichen wird; `GameView` zeichnet mit `Canvas` jedes Frame
