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

# Palmera City – Open-World-Spiel (Android)

Zweite App in diesem Repo (Modul `game`): ein eigenes 3D-Open-World-Actionspiel im
Stil der großen Gangster-Sandbox-Spiele, optimiert fürs Querformat auf aktuellen
Smartphones (getestet für Galaxy-S25-Auflösung). Alle Inhalte – Stadt, Figuren,
Fahrzeuge, Musik – sind selbst erstellt bzw. werden prozedural erzeugt.

## Inhalt

- **Stadt** mit 81 Blöcken: Hochhaus-Innenstadt, Büroviertel, pastellfarbene Wohnviertel,
  Parks, Plaza mit Brunnen, Parkplätze, Krankenhaus, Polizeirevier, Strand mit Palmen und Riesenrad, Meer
- **Tag-/Nachtzyklus** (1 Spielstunde = 30 s) mit Schatten, beleuchteten Fenstern, Straßenlaternen, Sternenhimmel
- **Zu Fuß**: laufen, rennen, springen, Faustkampf, Pistole, MP, Schrotflinte mit automatischer Zielerfassung
- **Fahrzeuge**: Kompakt, Limousine, Sportwagen, Pick-up, Lieferwagen, Taxi, Bus, Streifenwagen –
  jedes Fahrzeug kann geklaut werden (Fahrer wird rausgezerrt), Driften mit Handbremse, Schaden, Rauch, Explosionen
- **Lebendige Stadt**: Verkehr fährt auf Fahrspuren und biegt ab, Passanten laufen auf Gehwegen und flüchten bei Gefahr
- **Fahndungssystem** mit 1–5 Sternen: Streifenwagen verfolgen und rammen, Polizisten nehmen fest oder schießen,
  ab 3 Sternen kommt ein Hubschrauber (nachts mit Suchscheinwerfer); Entkommen außer Sichtweite oder in der Lackiererei
- **Missionen** (unbegrenzt wiederholbar): Straßenrennen gegen 2 KI-Gegner, Lieferauftrag mit Zeitlimit,
  Kopfgeld auf bewachte Zielperson, Taxi-Job mit 3 Fahrgästen
- **Waffenladen**, **Lackiererei**, Geld, Gesundheit/Schutzweste, 20 versteckte Muscheln
- **Radar** (dreht mit der Kamera) mit GPS-Route, große **Karte** mit Wegpunkten
- **Synthwave-Radio** „Neon FM“, Motor-, Reifen-, Sirenen- und Schussgeräusche – alles per WebAudio synthetisiert
- Speichert automatisch (Geld, Waffen, Fortschritt), Grafikstufen Niedrig/Mittel/Hoch, Vibration bei Treffern

## Steuerung

| Zu Fuß | Im Auto |
| --- | --- |
| Joystick links: gehen (ganz raus = rennen) | Joystick: lenken (hoch = Gas) |
| Rechts wischen: Kamera | ▲ Gas · ▼ Bremse/Rückwärts · HB Handbremse |
| 🔫 schießen · ⤒ springen · 🚗 einsteigen | 🔫 aus dem Fenster schießen · ⇱ aussteigen · 📯 Hupe |
| Waffensymbol antippen: Waffe wechseln | 📻 Radio an/aus |

Radar antippen öffnet die Karte, ❚❚ bzw. die Zurück-Taste pausiert.

## Bauen & installieren

```bash
./gradlew :game:assembleDebug
# APK: game/build/outputs/apk/debug/game-debug.apk
```

GitHub Actions baut bei jedem Push beide APKs; die Spiel-APK liegt im Workflow-Lauf unter
„Artifacts“ als `palmera-city-apk`. Auf dem Handy die ZIP entpacken, die APK öffnen und
„Installation aus unbekannten Quellen“ erlauben.

## Technik

- Kotlin-Hülle (`MainActivity`) mit Vollbild-WebView, Display-Cutout-Unterstützung, Vibration über JS-Bridge
- Spiel in JavaScript mit [three.js](https://threejs.org) (MIT, in `assets/web/lib`), WebGL 2
- Spieldateien werden über `WebViewAssetLoader` unter `https://appassets.androidplatform.net` geladen
- Stadtgeometrie wird zu wenigen großen Meshes zusammengefasst (wenige Draw-Calls), Palmen/Laternen als Instanzen
- Dynamische Auflösung: fällt die Bildrate unter 40 fps, wird die Renderauflösung automatisch gesenkt
- Zum Testen am PC: `game/src/main/assets/web` mit einem lokalen Webserver ausliefern
  (WASD, Maus = schießen, Q/E Kamera, F einsteigen, Leertaste springen/Handbremse, Tab Waffe, M Karte)
