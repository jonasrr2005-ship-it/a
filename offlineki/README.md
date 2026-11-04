# Offline KI (Android)

Ein KI-Gesprächspartner, der **komplett auf dem Handy läuft – ohne Internet**.
Optimiert für das **Samsung Galaxy S25** (Snapdragon 8 Elite, 12 GB RAM).

- Redet locker und natürlich wie ein Mensch: kurze Antworten, eigene Meinung, Humor,
  Gegenfragen. Name, Charakter und „Kreativität“ sind in den Einstellungen änderbar.
- Rechnet lokal mit [llama.cpp](https://github.com/ggml-org/llama.cpp) auf den CPU-Kernen
  des Snapdragon (ARM dotprod/i8mm). Antwort erscheint Wort für Wort.
- **Keine Internet-Berechtigung** im Manifest (wird sogar aktiv entfernt) – Android lässt
  die App gar nicht ins Netz. Gespräche werden nur lokal gespeichert.
- Optional **Sprechen**: Mikrofon-Knopf für Spracheingabe, Antworten werden vorgelesen.
  Ist „Antworten laut vorlesen“ an, entsteht ein Gesprächsmodus: nach dem Vorlesen hört die
  App automatisch wieder zu (Mikrofon-Knopf beendet ihn).

## Bilder malen 🎨

Die KI kann auch **Bilder erzeugen – ebenfalls komplett offline** (mit
[stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)).

- Einfach im Chat schreiben oder sagen: „Mal mir eine Katze im Weltall“, „Erstell ein Bild
  von einem Leuchtturm im Sturm“ – oder Text eintippen und den **Bild-Knopf** drücken.
- Die Text-KI übersetzt den Wunsch automatisch in einen englischen Bild-Prompt (Bildmodelle
  verstehen fast nur Englisch) und berücksichtigt dabei das Gespräch („mal das mal“).
- Bild antippen → in der Galerie speichern (Ordner „Offline KI“), neue Variante malen oder
  den Prompt ansehen.
- Dafür einmalig ein **Bildmodell** importieren (Menü → „Bildmodelle verwalten“), eine
  einzelne Stable-Diffusion-Datei (`.safetensors` oder `.gguf`):

  | Modell | Schritte | Dauer auf dem S25 (grob) |
  | --- | --- | --- |
  | SD-Turbo (Suche: „sd-turbo gguf“) | 4 | schnell, ca. 10–30 s |
  | SD 1.5 mit „LCM“ im Namen (z. B. DreamShaper 8 LCM) | 6 | ca. 20–40 s |
  | normales Stable Diffusion 1.5 | 20 | 1–2 min, beste Details |

  Steht `turbo`, `lcm`, `lightning` oder `hyper` im Dateinamen, stellt die App automatisch
  wenige Schritte ein. Bilder sind 512×512 Pixel. Die Zeiten sind Schätzungen.

## Einrichtung

1. APK installieren (GitHub → Actions → „Offline KI APK“ → Artifact `offline-ki-apk`).
2. Einmalig ein Sprachmodell im **GGUF**-Format herunterladen (z. B. im Handy-Browser von
   huggingface.co). Empfehlungen für das S25:

   | Modell | Größe (Q4_K_M) | Hinweis |
   | --- | --- | --- |
   | Gemma 3 4B Instruct | ~2,5 GB | sehr gutes Deutsch, **empfohlen** |
   | Qwen 3 4B Instruct | ~2,5 GB | schlau, gutes Deutsch |
   | Llama 3.2 3B Instruct | ~2,0 GB | schnell |
   | Gemma 3 12B Instruct | ~7 GB | beste Qualität, aber langsam und RAM-knapp |

   Auf Hugging Face nach z. B. `gemma-3-4b-it GGUF` suchen und die Datei mit `Q4_K_M` im
   Namen laden.
3. In der App „Modelldatei (.gguf) auswählen“ – sie wird in die App kopiert, danach kann
   das Original im Download-Ordner gelöscht werden.
4. Ab jetzt Flugmodus an und losquatschen.

**Sprache offline:** Für Spracheingabe/-ausgabe ohne Netz müssen die deutschen
Offline-Sprachdaten installiert sein (Einstellungen → Allgemeine Verwaltung → Sprache →
Text-zu-Sprache bzw. Spracherkennung). Diese Dienste gehören zu Samsung/Google, nicht zu
dieser App.

## Technik

- `src/main/cpp/llm.cpp` – JNI-Kern: Modell laden, Chat-Vorlage des Modells anwenden,
  KV-Cache zwischen den Nachrichten wiederverwenden (nur Neues wird gerechnet), Sampling
  (top-k/top-p/min-p, Wiederholungsstrafe), Text-Streaming.
- `../imagegen/` – eigenes Modul für die Bild-KI (stable-diffusion.cpp, Version
  `master-975-95b3090`). Sie bringt ihre eigene ggml-Version mit, daher eine getrennte `.so`
  mit versteckten Symbolen.
- llama.cpp wird beim Bauen in fester Version (`b12000`) per CMake geladen, nur arm64.
- Zu lange Gespräche: die ältesten Nachrichten fallen automatisch aus dem „Gedächtnis“.

```bash
./gradlew :offlineki:assembleDebug   # offlineki/build/outputs/apk/debug/offlineki-debug.apk
```
