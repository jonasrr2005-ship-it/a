# Standort-Tracker (Android)

Android-App, die den eigenen Standort aufzeichnet und den zurückgelegten Weg
**live als Strich auf einer Karte** einzeichnet. Dazu können Pins mit Datum,
Uhrzeit, Adresse und den Firmen direkt an der Stelle gesetzt werden.

## Zwei Versionen

| Version | Karte | API-Schlüssel | App-Name |
|---|---|---|---|
| `osm` | OpenStreetMap (osmdroid) | nicht nötig | Tracker (OSM) |
| `google` | Google Maps | nötig (siehe unten) | Tracker (Google) |

Beide Versionen haben verschiedene Paketnamen und können gleichzeitig installiert sein.

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

## Google-Maps-API-Schlüssel (nur Version `google`)

1. In der [Google Cloud Console](https://console.cloud.google.com/) ein Projekt anlegen
   und die **Maps SDK for Android** aktivieren.
2. Unter „APIs & Dienste → Anmeldedaten“ einen API-Schlüssel erstellen.
3. Schlüssel eintragen:
   - lokal: in `local.properties` die Zeile `MAPS_API_KEY=dein_schlüssel`
   - GitHub Actions: Repository-Secret `MAPS_API_KEY`

## Bauen

```bash
./gradlew assembleOsmDebug      # app/build/outputs/apk/osm/debug/app-osm-debug.apk
./gradlew assembleGoogleDebug   # app/build/outputs/apk/google/debug/app-google-debug.apk
```

GitHub Actions baut bei jedem Push beide APKs (Artifacts `tracker-osm-apk` und
`tracker-google-apk`).
