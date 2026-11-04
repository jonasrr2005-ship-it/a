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

---

# Zugreise (Android) – Modul `zugreise/`

Zweite, eigenständige App im selben Repository. Man trägt ein, **welche Züge man
wann nimmt** (auch mehrere hintereinander mit Umstiegen), und die App sucht den
**kompletten Laufweg mit allen Zwischenhalten** aus dem Internet und zeichnet ihn
auf eine OpenStreetMap-Karte.

## Bedienung

1. **„+ Zug hinzufügen“** → Einstiegsbahnhof eintippen (Vorschläge erscheinen),
   **Datum** und ungefähre **Uhrzeit** wählen, optional die **Zugnummer**
   (z. B. `ICE 578`, `RE 4`, `S 8`).
2. **„Abfahrten suchen“** → Liste der Abfahrten; den eigenen Zug antippen.
3. Die App lädt den Laufweg und fragt **„Wo steigst du aus?“** → Halt antippen.
4. Für einen Umstieg einfach den nächsten Zug hinzufügen – Bahnhof, Datum und
   Uhrzeit sind dann schon mit der letzten Ankunft vorausgefüllt.

## Funktionen

- Karte mit jedem Zug in eigener Farbe, Einstieg/Ausstieg als große, Zwischenhalte als
  kleine Punkte; Antippen zeigt Name, An-/Abfahrt, Gleis und Verspätung.
- Liste der Züge mit Datum, Abfahrt/Ankunft, Gleis, Fahrzeit, km, Anzahl Zwischenhalte
  und Umstiegszeit. Antippen → alle Halte; lange drücken → löschen.
- Mehrere **Reisen** (z. B. „Urlaub Italien“) anlegen, umbenennen, löschen.
- Alles wird auf dem Gerät gespeichert; einmal geladene Strecken gehen auch offline.

## Datenquelle

Fahrplan, Halte und Streckenverlauf kommen von [Transitous](https://transitous.org)
(freier MOTIS-Fahrplandienst, enthält u. a. DB, ÖBB, SBB und viele Verbünde in Europa).
Kein API-Schlüssel nötig.

## Bauen

```bash
./gradlew :zugreise:assembleDebug   # zugreise/build/outputs/apk/debug/zugreise-debug.apk
```

GitHub Actions baut bei jedem Push auch diese APK (Artifact `zugreise-apk`).
