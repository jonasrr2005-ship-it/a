# Standort-Tracker (Android)

Android-App, die den eigenen Standort aufzeichnet und den zurückgelegten Weg
**live als Strich auf einer Karte** einzeichnet. Dazu können Pins mit Datum,
Uhrzeit, Adresse und dem, was abgeliefert wurde, gesetzt werden.

## Karte umschaltbar

Über den Knopf **„🗺 Karte: …“** wechselt man jederzeit zwischen **OpenStreetMap**
(kein Schlüssel nötig) und **Google Maps** (braucht einen API-Schlüssel, siehe unten).
Die Wahl wird gespeichert.

## Funktionen

- Live-Karte mit roter Linie für die Strecke und Positionsmarker
- Aufzeichnung läuft als Vordergrund-Dienst weiter – auch bei ausgeschaltetem Bildschirm
- **Mehrere Strecken:** jede Aufnahme (Start → Stopp) ist eine eigene Strecke. Die Liste
  „Strecken“ zeigt Wochentag, Datum, Start-/Endzeit, Länge und Anzahl der Lieferungen;
  jede Strecke lässt sich mit ihren Pins auf der Karte anzeigen.
- **Pin setzen (Lieferung):** nur während einer Aufzeichnung; der Pin gehört fest zu
  dieser Strecke. Gespeichert werden Datum mit Wochentag, Uhrzeit, Koordinaten,
  Genauigkeit und die vollständige Adresse; die App fragt sofort „Was hast du abgeliefert?“.
- **Löschen:** nur ganze Strecken samt allen ihren Pins, nach einer Sicherheitsabfrage.
  Einzelne Pins (z. B. falsch gesetzte) lassen sich ebenfalls mit Sicherheitsabfrage löschen.
  - Adresse: Android-Geocoder, ersatzweise OpenStreetMap Nominatim
  - Ohne Internet wird der Pin trotzdem gespeichert, die Adresse wird später nachgeladen.

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
