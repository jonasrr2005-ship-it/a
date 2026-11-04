# Standort-Tracker (Android)

Android-App, die den eigenen Standort aufzeichnet und den zurückgelegten Weg
**live als Strich auf einer Google-Maps-Karte** einzeichnet.

## Funktionen

- Live-Karte mit roter Linie für den gelaufenen/gefahrenen Weg und Positionsmarker
- Aufzeichnung läuft als Vordergrund-Dienst weiter – auch bei ausgeschaltetem Bildschirm
- Benachrichtigung mit Punktanzahl, Strecke und „Stoppen“-Knopf
- Anzeige der Gesamtstrecke
- Weg wird gespeichert (`track.csv` im App-Speicher) und bleibt nach Neustart erhalten
- Karte folgt der Position; nach manuellem Verschieben wieder mit „Zentrieren“

## Google-Maps-API-Schlüssel

Google Maps braucht einen (für Android-Apps kostenlosen) API-Schlüssel, sonst bleibt
die Karte grau:

1. In der [Google Cloud Console](https://console.cloud.google.com/) ein Projekt anlegen
   und die **Maps SDK for Android** aktivieren (Rechnungskonto erforderlich,
   Kartenaufrufe im Android-SDK kosten nichts).
2. Unter „APIs & Dienste → Anmeldedaten“ einen API-Schlüssel erstellen.
3. Schlüssel eintragen:
   - lokal: in `local.properties` die Zeile `MAPS_API_KEY=dein_schlüssel`
   - GitHub Actions: im Repository unter *Settings → Secrets and variables → Actions*
     ein Secret `MAPS_API_KEY` anlegen.

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
- Google Maps SDK (`play-services-maps`)
- `LocationManager` (GPS + Netzwerk), kein Google-Play-Services nötig
- `LocationService`: Vordergrund-Dienst (`foregroundServiceType="location"`)
- `TrackRepository`: hält die Punkte als `StateFlow`, die `MainActivity` zeichnet sie live
