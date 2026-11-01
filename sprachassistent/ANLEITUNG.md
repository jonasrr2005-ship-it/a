# Sprachassistent – Schritt 1

Für das Sony Xperia tipo (Android 4.0.4) als „smarter Lautsprecher“ mit Claude.

**Was die App in Schritt 1 kann**

- Lauscht offline auf ein Aktivierungswort (z. B. „Computer“) – mit PocketSphinx, ohne Internet.
- Danach nimmt sie deine Frage auf, wandelt sie in Text um, schickt sie an Claude und liest die Antwort auf Deutsch vor.
- Nutzt das Mikrofon und den Lautsprecher des Bluetooth-Lautsprechers und verbindet sich neu, wenn die Verbindung abbricht.
- Läuft dauerhaft im Hintergrund (Symbol oben in der Statusleiste) und startet nach dem Einschalten des Handys von selbst.
- Zeigt alle Fehler auf dem Bildschirm an (rote Zeile und Protokoll).
- Hat ein Textfeld, damit du Claude auch ohne Sprache testen kannst.

---

## Teil A – Handy vorbereiten (5 Minuten)

1. **Datum und Uhrzeit prüfen** (Einstellungen → Datum & Uhrzeit). Wenn die Uhrzeit falsch ist, scheitert die sichere Internetverbindung.
2. **Deutsche Sprachausgabe prüfen:** Einstellungen → Sprache & Eingabe → Text-in-Sprache-Ausgabe → „Beispiel anhören“. Die Stimme muss deutsch sprechen. Falls nicht: bei „Sprache“ Deutsch einstellen.
3. **Bluetooth-Lautsprecher koppeln:** Einstellungen → Bluetooth → Lautsprecher auswählen. Er muss dort als „Verbunden“ erscheinen. Wichtig ist, dass er auch für **Anrufe/Telefon** verbunden ist (das Mikrofon läuft über das Telefon-Profil).
4. **WLAN im Standby an lassen:** Einstellungen → WLAN → Menütaste → Erweitert → „WLAN im Standby aktiviert lassen“ → **Immer**.
5. Falls es einen **Stromsparmodus** gibt (z. B. „STAMINA“ oder „Energiesparmodus“): für diese App ausschalten.

## Teil B – Projekt holen und in Android Studio öffnen

1. Auf GitHub das Repository öffnen. Oben links auf den Knopf mit dem Zweig-Namen klicken und den Zweig **`ccr-1836c7ed-qigzvb`** auswählen.
2. Grüner Knopf **„Code“** → **„Download ZIP“**. Die ZIP-Datei auf dem PC entpacken (Rechtsklick → „Alle extrahieren“).
3. Android Studio starten → **File → Open** (bzw. „Open“ im Startfenster).
4. Im entpackten Ordner den Unterordner **`sprachassistent`** auswählen (nicht den äußeren Ordner!) → **OK**.
5. Wenn gefragt wird „Trust project?“ → **Trust Project**.
6. Unten rechts läuft jetzt „Gradle sync“. Das dauert beim ersten Mal einige Minuten (Android Studio lädt Werkzeuge aus dem Internet). Warten, bis unten nichts mehr läuft.
   - Bietet Android Studio an, das „Android Gradle Plugin“ zu aktualisieren (Upgrade Assistant): **nicht** aktualisieren, Fenster schließen. Die Version ist absichtlich gewählt.
   - Fehler „Gradle JDK“ / „Java version“: **File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** auf „jbr-17“ oder „jbr-21“ (Embedded JDK) stellen, dann oben auf den Elefanten-Knopf „Sync Project with Gradle Files“ klicken.
   - Fehler „SDK Platform 35 not found“ / „License not accepted“: auf den blauen Link im Fehlertext klicken („Install missing platform…“ bzw. „Accept licenses“) und bestätigen.

## Teil C – APK bauen

1. Menü **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
   (In neueren Versionen heißt es **Build → Generate App Bundles or APKs → Generate APKs**.)
2. Warten, bis unten rechts „APK(s) generated successfully“ erscheint, dann auf **„locate“** klicken.
3. Die fertige Datei heißt **`app-debug.apk`** und liegt in
   `sprachassistent\app\build\outputs\apk\debug\`.

**Alternative ohne Android Studio:** Bei jedem Hochladen baut GitHub die APK automatisch. Dafür auf GitHub den Reiter **Actions** öffnen, dann **„Sprachassistent - APK bauen“** und den obersten grünen Lauf wählen. Unten unter **Artifacts** liegt **`sprachassistent-apk`**. Die heruntergeladene ZIP-Datei entpacken, darin ist `app-debug.apk`.

## Teil D – APK auf dem Handy installieren

1. Am Handy: **Einstellungen → Sicherheit → „Unbekannte Herkunft“** (oder „Unbekannte Quellen“) **anhaken** und mit OK bestätigen.
2. Handy per USB-Kabel an den PC anschließen. Auf dem Handy oben die Statusleiste herunterziehen und den USB-Modus auf „Medienübertragung (MTP)“ oder „Massenspeicher“ stellen.
3. Am PC im Windows-Explorer das Handy öffnen und `app-debug.apk` in den Ordner **Download** kopieren.
4. Am Handy einen Dateimanager öffnen (z. B. „Dateien“, „File Commander“ oder „Downloads“), zu **Download** gehen und `app-debug.apk` antippen → **Installieren**.
   - Ohne Dateimanager: die APK per E-Mail an dich selbst schicken und den Anhang am Handy öffnen.
5. Bei einer neuen Version einfach wieder so installieren („Ersetzen“/„Aktualisieren“). Deine Einstellungen bleiben erhalten.

## Teil E – Erster Test: Läuft das Lauschen flüssig?

1. App **„Sprachassistent“** öffnen.
2. Der Haken **„Testmodus: nur Aktivierungswort“** ist am Anfang gesetzt. So bleiben. Für diesen Test ist **kein** API-Schlüssel nötig.
3. Optional: In den **Einstellungen** unter „Aktivierungswort“ ein eigenes Wort eintragen. Voreingestellt ist **„computer“**. Am besten funktionieren Wörter mit 3–4 Silben, z. B. „computer“, „jarvis“, „alexandra“ oder „hallo jarvis“.
4. **Start** drücken. Beim ersten Start braucht das Laden einige Sekunden.
5. Oben steht jetzt z. B. `Lausche auf "computer" (Bluetooth-Mikrofon) - TESTMODUS`. Darunter steht alle 5 Sekunden:
   - **Rechenlast Worterkennung: xx %** – unter 100 % heißt, das Handy kommt hinterher. Unter 60 % ist gut.
   - **Mikrofon-Pegel: xx %** – sollte steigen, wenn du sprichst. Bleibt der Wert bei 0, kommt kein Ton an.
6. Sag das Aktivierungswort. Bei einem Treffer piept es, und im Protokoll steht „TEST: Aktivierungswort erkannt“.
7. Zum Schluss den Bildschirm ausschalten und es noch einmal probieren. Die App muss auch so reagieren.

**Bitte teile mir mit:**
- Rechenlast und Pegel (einmal mit Bluetooth-Mikrofon, einmal ohne),
- wie oft das Wort erkannt wird, und ob die App auch reagiert, wenn du das Wort **nicht** gesagt hast,
- alles, was rot oder als „FEHLER“ im Protokoll steht (gern als Foto).

**Feineinstellung:** Einstellungen → „Empfindlichkeit“. Wird das Wort schlecht erkannt, stelle „empfindlicher“ ein. Löst die App ohne Grund aus, stelle „unempfindlicher“ ein. Im Protokoll steht außerdem, wie die App das Wort „ausspricht“ (Lautschrift).

## Teil F – Mit Claude testen

1. Einen API-Schlüssel erstellen auf https://console.anthropic.com → „API Keys“ (Guthaben nötig).
2. In der App: **Einstellungen → Claude-API-Schlüssel** eintragen. Der Schlüssel beginnt mit `sk-ant-`.
3. **Erst das Textfeld testen:** eine Frage tippen, z. B. „Wie hoch ist die Zugspitze?“, und auf **Senden** drücken. Die Antwort erscheint unter „Antwort:“ und wird vorgelesen.
4. Danach den Haken **Testmodus** entfernen. Jetzt: Aktivierungswort sagen → Piepton → Frage stellen → Claude antwortet.

**Spracherkennung der Frage:**
- Voreingestellt ist die **Google-Spracheingabe**, die im Handy eingebaut ist. Sie ist kostenlos, braucht aber Internet. Ob Google das alte Android 4.0 noch bedient, muss dein Test zeigen.
- Klappt das nicht (Fehler „Server-Fehler“ oder „nicht vorhanden“): Einstellungen → Spracherkennung → **OpenAI Whisper** wählen und einen OpenAI-Schlüssel eintragen (https://platform.openai.com, kostet etwa 0,6 Cent pro Minute Sprache). Claude selbst kann keine Tonaufnahmen annehmen, deshalb ist dafür ein eigener Dienst nötig.

## Einstellungen kurz erklärt

| Einstellung | Bedeutung |
|---|---|
| Modell | Leer = `claude-opus-5-5`. Für schnellere und günstigere Antworten kannst du z. B. `claude-haiku-5-5` eintragen. |
| Denkaufwand | „Niedrig“ antwortet am schnellsten (empfohlen für Sprache). |
| Aussprache von Hand | Nur nötig, wenn das Wort nie erkannt wird. Leer lassen = automatisch. |
| Bluetooth-Mikrofon | Nutzt das Mikrofon des Lautsprechers (Telefonqualität). Aus = Handy-Mikrofon. |
| Antwort in Musikqualität | Die Antwort klingt besser, das Umschalten dauert aber etwa 2 Sekunden. Manche Lautsprecher machen dabei Geräusche. |
| Autostart | Startet die App nach dem Einschalten des Handys, wenn sie vorher lief. |

## Häufige Fehler

| Meldung | Was tun |
|---|---|
| „Sichere Verbindung (TLS) fehlgeschlagen“ | Datum/Uhrzeit am Handy prüfen. Sonst die Meldung an mich schicken. |
| „Keine Internetverbindung“ | WLAN prüfen, Teil A Punkt 4. |
| „Claude-API-Schlüssel ungültig (401)“ | Schlüssel neu eintragen, ohne Leerzeichen. |
| „Kein Guthaben“ / 429 | In der Anthropic-Konsole Guthaben aufladen. |
| „Keine deutsche Stimme installiert“ | Teil A Punkt 2. |
| „Mikrofon konnte nicht geöffnet werden“ | Andere Apps schließen, die das Mikrofon benutzen. Sonst Handy neu starten. |
| „Kein Bluetooth-Lautsprecher gekoppelt“ | Teil A Punkt 3. Bis dahin nutzt die App das Handy-Mikrofon. |
| „Dieses Handy erlaubt kein Bluetooth-Mikrofon außerhalb von Telefonaten“ | Dann geht das Bluetooth-Mikrofon nicht. Dann eventuell mit Handy-Mikrofon arbeiten. |

---

## Technik (für später)

- Java, minSdk 15 (Android 4.0.4), targetSdk 22, nur `armeabi-v7a`
- Android Gradle Plugin 8.7.3, Gradle 8.14.3, compileSdk 35
- Keine AndroidX-Bibliotheken. Externe Bausteine: nur PocketSphinx (`app/libs`, API 14+) und Conscrypt 2.5.2 (API 9+)
- TLS: Conscrypt-Provider, TLS 1.2/1.3 erzwungen, Stammzertifikate in `assets/roots.pem` plus Systemzertifikate
- Claude: `POST /v1/messages` mit `anthropic-version: 2026-10-21`, `output_config.effort`, Gesprächsverlauf 5 Minuten / max. 10 Fragen
- Worterkennung: englisches PTM-Modell (klein und schnell). Deutsche Wörter werden per Regeln in englische Lautschrift übertragen.
