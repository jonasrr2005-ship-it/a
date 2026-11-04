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
- llama.cpp wird beim Bauen in fester Version (`b12000`) per CMake geladen, nur arm64.
- Zu lange Gespräche: die ältesten Nachrichten fallen automatisch aus dem „Gedächtnis“.

```bash
./gradlew :offlineki:assembleDebug   # offlineki/build/outputs/apk/debug/offlineki-debug.apk
```
