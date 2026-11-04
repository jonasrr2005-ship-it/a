# Standort-Tracker (Android)

Android-App, die den eigenen Standort aufzeichnet und den zurückgelegten Weg
**live als Strich auf einer Karte** einzeichnet. Dazu können Pins mit Datum,
Uhrzeit, Adresse und den Firmen direkt an der Stelle gesetzt werden.

## Karte umschaltbar

Über den Knopf **„🗺 Karte: …“** wechselt man jederzeit zwischen **OpenStreetMap**
(kein Schlüssel nötig) und **Google Maps** (braucht einen API-Schlüssel, siehe unten).
Die Wahl wird gespeichert.

## Funktionen

- Live-Karte mit roter Linie für den Weg und Positionsmarker
- Aufzeichnung läuft als Vordergrund-Dienst weiter – auch bei ausgeschaltetem Bildschirm
- Benachrichtigung mit Punktanzahl, Strecke und „Stoppen“-Knopf
- Weg wird gespeichert (`track.csv`) und bleibt nach Neustart erhalten
- **Pin setzen:** speichert Datum, Uhrzeit, Koordinaten, Genauigkeit, Adresse und alle
  Firmen im Umkreis von 50 m (bzw. „Keine Firmen in der Nähe“). Pins bleiben gespeichert
  (`pins.json`), bis sie einzeln oder alle gelöscht werden.
  - Adresse: Android-Geocoder, ersatzweise OpenStreetMap Nominatim
  - Firmen: OpenStreetMap-Daten über die Overpass-API (kostenlos, ohne Schlüssel)
  - Ohne Internet wird der Pin trotzdem gespeichert; Adresse/Firmen lassen sich später
    mit „Erneut suchen“ nachladen.

## Google-Maps-API-Schlüssel

1. In der [Google Cloud Console](https://console.cloud.google.com/) ein Projekt anlegen
   und die **Maps SDK for Android** aktivieren.
2. Unter „APIs & Dienste → Anmeldedaten“ einen API-Schlüssel erstellen.
3. Schlüssel eintragen:
   - lokal: in `local.properties` die Zeile `MAPS_API_KEY=dein_schlüssel`
   - GitHub Actions: Repository-Secret `MAPS_API_KEY`

## Bauen

```bash
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions baut bei jedem Push die APK (Artifact `standort-tracker-apk`).
