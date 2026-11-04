package de.offline.ki

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import de.offline.ki.databinding.ActivityMainBinding
import de.offline.ki.databinding.DialogSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var models: ModelStore
    private lateinit var imageModels: ModelStore
    private val imageEngine get() = imageEngineInstance
    @Volatile private var stopRequested = false
    private lateinit var store: ChatStore
    private lateinit var voice: Voice
    private val engine get() = engineInstance

    private lateinit var messages: MutableList<Message>
    private lateinit var adapter: MessageAdapter

    private var generating: Job? = null
    private var importing = false
    private var listening = false
    /** Gesprächsmodus: nach dem Vorlesen automatisch wieder zuhören. */
    private var conversationMode = false
    private var micTint: android.content.res.ColorStateList? = null

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    private val pickImageModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importImageModel(uri)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else toast("Ohne Mikrofon-Berechtigung keine Spracheingabe")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        settings = Settings(this)
        models = ModelStore(this).also { it.cleanupPartial() }
        store = ChatStore(this)
        imageModels = ModelStore(this, "bildmodelle", listOf(".safetensors", ".gguf", ".ckpt"))
            .also { it.cleanupPartial() }
        voice = Voice(this)
        voice.onFinishedSpeaking = {
            if (conversationMode && generating == null && !isFinishing) startListening()
        }

        messages = store.load()
        adapter = MessageAdapter(messages) { showImageOptions(it) }
        b.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.messages.adapter = adapter

        b.toolbar.title = settings.aiName
        b.toolbar.inflateMenu(R.menu.main)
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_new_chat -> newChat()
                R.id.action_settings -> showSettings()
                R.id.action_models -> showModels()
                R.id.action_image_models -> showImageModels()
                R.id.action_about -> showAbout()
            }
            true
        }

        b.sendButton.setOnClickListener {
            if (generating != null) stopGenerating() else send(b.input.text.toString())
        }
        b.micButton.setOnClickListener {
            if (listening || conversationMode) {
                conversationMode = false
                stopListening()
                voice.stopSpeaking()
            } else {
                voice.stopSpeaking()
                conversationMode = settings.speak
                startListening()
            }
        }
        b.imageButton.setOnClickListener {
            val t = b.input.text.toString().trim()
            if (t.isEmpty()) {
                toast("Schreib zuerst, was ich malen soll – z. B. „eine Katze im Weltall“")
            } else {
                paintImage(t)
            }
        }
        b.pickModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }

        loadActiveModel()
    }

    override fun onDestroy() {
        if (generating != null) { engine.stop(); imageEngine.cancel() }
        voice.release()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        conversationMode = false
        stopListening()
    }

    // ---------------------------------------------------------------- Modell

    private fun loadActiveModel() {
        val name = settings.activeModel?.takeIf { models.file(it).exists() } ?: models.list().firstOrNull()?.name
        if (name == null) {
            showSetup()
            return
        }
        settings.activeModel = name
        b.setupPanel.visibility = View.GONE
        setInputEnabled(false)
        b.toolbar.subtitle = "Lade Modell …"
        lifecycleScope.launch {
            val err = engine.load(models.file(name).absolutePath, settings.contextSize)
            if (err != null) {
                b.toolbar.subtitle = "Fehler beim Laden"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Modell konnte nicht geladen werden")
                    .setMessage("$err\n\nDatei: $name")
                    .setPositiveButton("Anderes Modell") { _, _ -> showModels() }
                    .setNegativeButton("OK", null)
                    .show()
            } else {
                b.toolbar.subtitle = "offline · " + engine.info()
                setInputEnabled(true)
            }
        }
    }

    private fun showSetup() {
        b.setupPanel.visibility = View.VISIBLE
        b.toolbar.subtitle = "Kein Modell"
        setInputEnabled(false)
        b.setupText.text = """
            Willkommen! Diese KI läuft komplett auf deinem Handy – ohne Internet.

            Dafür brauchst du einmalig eine Modelldatei im GGUF-Format (ca. 2–5 GB), z. B.:
            • Gemma 3 4B Instruct (Q4_K_M) – sehr gutes Deutsch, empfohlen
            • Qwen 3 4B oder Llama 3.2 3B Instruct (Q4_K_M)

            Lade die .gguf-Datei am PC oder im Handy-Browser (z. B. von huggingface.co) herunter und wähle sie hier aus. Sie wird in die App kopiert; danach kannst du das Original löschen.
        """.trimIndent()
    }

    private fun importModel(uri: android.net.Uri) {
        if (importing) return
        importing = true
        b.setupPanel.visibility = View.VISIBLE
        b.pickModel.isEnabled = false
        b.importProgress.visibility = View.VISIBLE
        b.importProgress.isIndeterminate = false
        b.importProgress.progress = 0
        b.setupText.text = "Modell wird in die App kopiert …\nDas kann bei mehreren GB eine Minute dauern."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    models.import(uri) { p -> runOnUiThread { b.importProgress.progress = (p * 100).toInt() } }
                }
            }
            importing = false
            b.pickModel.isEnabled = true
            b.importProgress.visibility = View.GONE
            result.onSuccess { file ->
                settings.activeModel = file.name
                toast("Modell importiert: ${file.name}")
                loadActiveModel()
            }.onFailure { e ->
                models.cleanupPartial()
                showSetup()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Import fehlgeschlagen")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showModels() {
        if (generating != null) return
        val list = models.list()
        val labels = list.map {
            val mark = if (it.name == settings.activeModel) "✓ " else ""
            "$mark${it.name}  (${"%.1f".format(it.length() / 1e9)} GB)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (list.isEmpty()) "Keine Modelle" else "Modelle")
            .setItems(labels) { _, i -> modelOptions(list[i]) }
            .setPositiveButton("Neues importieren") { _, _ -> pickModel.launch(arrayOf("*/*")) }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun modelOptions(file: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setPositiveButton("Verwenden") { _, _ ->
                settings.activeModel = file.name
                loadActiveModel()
            }
            .setNegativeButton("Löschen") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("„${file.name}“ wirklich löschen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        lifecycleScope.launch {
                            if (engine.loadedModel == file.absolutePath) engine.unload()
                            file.delete()
                            if (settings.activeModel == file.name) settings.activeModel = null
                            loadActiveModel()
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNeutralButton("Abbrechen", null)
            .show()
    }

    // ---------------------------------------------------------------- Chat

    private fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || generating != null || engine.loadedModel == null) return
        if (ImageEngine.isImageRequest(t)) {
            paintImage(t)
            return
        }
        stopRequested = false
        b.input.setText("")
        messages += Message("user", t)
        val answer = Message("assistant", "")
        messages += answer
        adapter.notifyItemRangeInserted(messages.size - 2, 2)
        scrollToEnd()

        val history = messages.dropLast(1).map { it.copy() }
        val speak = settings.speak
        if (speak) voice.beginAnswer()
        b.sendButton.setIconResource(R.drawable.ic_stop)
        b.toolbar.subtitle = "${settings.aiName} schreibt …"

        generating = lifecycleScope.launch {
            val err = engine.reply(settings, history) { partial ->
                runOnUiThread {
                    answer.text = partial
                    adapter.notifyItemChanged(messages.lastIndexOf(answer))
                    scrollToEnd()
                    if (speak) voice.feed(ChatEngine.cleanReply(partial))
                }
            }
            answer.text = ChatEngine.cleanReply(answer.text)
            if (answer.text.isEmpty()) {
                val i = messages.lastIndexOf(answer)
                messages.removeAt(i)
                adapter.notifyItemRemoved(i)
            } else {
                adapter.notifyItemChanged(messages.lastIndexOf(answer))
            }
            store.save(messages)
            generating = null
            b.sendButton.setIconResource(R.drawable.ic_send)
            b.toolbar.subtitle = "offline · " + engine.info()
            if (err != null) toast(err)
            if (speak && answer.text.isNotEmpty()) voice.finish(answer.text)
            else if (conversationMode) startListening()
        }
    }

    private fun stopGenerating() {
        conversationMode = false
        stopRequested = true
        engine.stop()
        imageEngine.cancel()
        voice.stopSpeaking()
    }

    private fun newChat() {
        stopGenerating()
        lifecycleScope.launch {
            generating?.join()
            engine.reset()
            messages.mapNotNull { it.image }.forEach { File(it).delete() }
            messages.clear()
            adapter.notifyDataSetChanged()
            store.save(messages)
        }
    }

    private fun scrollToEnd() {
        if (messages.isNotEmpty()) b.messages.scrollToPosition(messages.size - 1)
    }

    private fun setInputEnabled(on: Boolean) {
        b.input.isEnabled = on
        b.sendButton.isEnabled = on
        b.micButton.isEnabled = on
        b.imageButton.isEnabled = on
    }

    // ---------------------------------------------------------------- Bilder

    private fun paintImage(request: String) {
        val wish = ImageEngine.stripCommand(request)
        if (wish.isEmpty() || generating != null || engine.loadedModel == null) return
        val modelFile = settings.activeImageModel?.let { imageModels.file(it) }?.takeIf { it.exists() }
            ?: imageModels.list().firstOrNull()
        if (modelFile == null) {
            showNoImageModel()
            return
        }
        settings.activeImageModel = modelFile.name
        stopRequested = false
        b.input.setText("")

        messages += Message("user", request.trim())
        val answer = Message("assistant", "Moment, ich überleg mir das Bild …")
        messages += answer
        adapter.notifyItemRangeInserted(messages.size - 2, 2)
        scrollToEnd()
        val history = messages.dropLast(2).map { it.copy() }

        b.sendButton.setIconResource(R.drawable.ic_stop)
        b.toolbar.subtitle = "${settings.aiName} malt …"

        fun show(text: String) {
            answer.text = text
            val i = messages.lastIndexOf(answer)
            if (i >= 0) adapter.notifyItemChanged(i)
            scrollToEnd()
        }

        generating = lifecycleScope.launch {
            // 1. Wunsch mit der Text-KI in einen englischen Bild-Prompt übersetzen
            val prompt = engine.imagePrompt(wish, history)?.takeUnless { stopRequested } ?: wish

            // 2. Bildmodell laden (nur beim ersten Mal)
            var bitmap: Bitmap? = null
            var error: String? = null
            if (!stopRequested) {
                if (imageEngine.loadedModel != modelFile.absolutePath) show("Ich hol meine Pinsel raus … (Bildmodell lädt)")
                error = imageEngine.load(modelFile)
            }

            // 3. Malen
            if (error == null && !stopRequested) {
                val preset = imageEngine.presetFor(modelFile)
                show("Ich male … 0 %")
                val started = System.currentTimeMillis()
                bitmap = imageEngine.generate(prompt, preset) { step, steps ->
                    if (steps > 0) runOnUiThread { show("Ich male … ${(step * 100 / steps).coerceIn(0, 100)} %") }
                }
                android.util.Log.i("OfflineKI", "Bild in ${(System.currentTimeMillis() - started) / 1000} s")
            }

            val bmp = bitmap
            if (bmp != null) {
                val file = withContext(Dispatchers.IO) {
                    File(filesDir, "bilder").mkdirs()
                    File(filesDir, "bilder/bild_${System.currentTimeMillis()}.png").also { f ->
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                answer.image = file.absolutePath
                answer.imagePrompt = prompt
                show(listOf(
                    "Tadaa – hier ist dein Bild! 🎨",
                    "So stell ich mir das vor. Gefällt's dir?",
                    "Fertig! Was sagst du?",
                    "Hier, frisch gemalt 😊",
                ).random())
            } else {
                show(
                    when {
                        stopRequested -> "Okay, ich hör auf zu malen."
                        error != null -> "Mein Bildmodell will gerade nicht: $error"
                        else -> "Hm, das Bild ist leider nichts geworden. Versuch's nochmal?"
                    }
                )
            }
            store.save(messages)
            generating = null
            b.sendButton.setIconResource(R.drawable.ic_send)
            b.toolbar.subtitle = "offline · " + engine.info()
            if (settings.speak) {
                voice.beginAnswer()
                voice.finish(answer.text)
            } else if (conversationMode) {
                startListening()
            }
        }
    }

    private fun showImageOptions(m: Message) {
        val path = m.image ?: return
        val items = arrayOf("In Galerie speichern", "Nochmal malen (neue Variante)", "Bildbeschreibung anzeigen")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveToGallery(File(path))
                    1 -> {
                        val wish = m.imagePrompt ?: return@setItems
                        paintImage("/bild $wish")
                    }
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Prompt fürs Bildmodell")
                        .setMessage(m.imagePrompt ?: "–")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    private fun saveToGallery(file: File) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Offline KI")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            toast(if (ok) "In der Galerie gespeichert (Ordner „Offline KI“)" else "Speichern fehlgeschlagen")
        }
    }

    private fun showNoImageModel() {
        AlertDialog.Builder(this)
            .setTitle("Zum Malen brauche ich ein Bildmodell")
            .setMessage(
                "Lade einmalig ein Stable-Diffusion-Modell herunter (eine einzelne Datei, " +
                    ".safetensors oder .gguf) und wähle es hier aus.\n\n" +
                    "Gut fürs Handy (schnell, nur wenige Schritte):\n" +
                    "• SD-Turbo – auf huggingface.co nach „sd-turbo gguf“ oder „sd_turbo.safetensors“ suchen\n" +
                    "• Ein Stable-Diffusion-1.5-Modell mit „LCM“ im Namen (z. B. DreamShaper 8 LCM)\n\n" +
                    "Bessere Qualität, aber langsamer: normales Stable Diffusion 1.5 (ca. 20 Schritte).\n\n" +
                    "Steht „turbo“ oder „lcm“ im Dateinamen, stelle ich die schnellen Einstellungen automatisch ein."
            )
            .setPositiveButton("Bildmodell auswählen") { _, _ -> pickImageModel.launch(arrayOf("*/*")) }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun importImageModel(uri: android.net.Uri) {
        if (importing) return
        importing = true
        b.toolbar.subtitle = "Bildmodell wird kopiert … 0 %"
        setInputEnabled(false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    imageModels.import(uri) { p ->
                        runOnUiThread { b.toolbar.subtitle = "Bildmodell wird kopiert … ${(p * 100).toInt()} %" }
                    }
                }
            }
            importing = false
            setInputEnabled(engine.loadedModel != null)
            b.toolbar.subtitle = if (engine.loadedModel != null) "offline · " + engine.info() else "Kein Modell"
            result.onSuccess { file ->
                settings.activeImageModel = file.name
                toast("Bildmodell importiert: ${file.name}")
            }.onFailure { e ->
                imageModels.cleanupPartial()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Import fehlgeschlagen")
                    .setMessage(e.message ?: e.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showImageModels() {
        if (generating != null) return
        val list = imageModels.list()
        if (list.isEmpty()) {
            showNoImageModel()
            return
        }
        val labels = list.map {
            val mark = if (it.name == settings.activeImageModel) "✓ " else ""
            "$mark${it.name}  (${"%.1f".format(it.length() / 1e9)} GB)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bildmodelle")
            .setItems(labels) { _, i ->
                val file = list[i]
                AlertDialog.Builder(this)
                    .setTitle(file.name)
                    .setPositiveButton("Verwenden") { _, _ -> settings.activeImageModel = file.name }
                    .setNegativeButton("Löschen") { _, _ ->
                        AlertDialog.Builder(this)
                            .setMessage("„${file.name}“ wirklich löschen?")
                            .setPositiveButton("Löschen") { _, _ ->
                                lifecycleScope.launch {
                                    if (imageEngine.loadedModel == file.absolutePath) imageEngine.unload()
                                    file.delete()
                                    if (settings.activeImageModel == file.name) settings.activeImageModel = null
                                }
                            }
                            .setNegativeButton("Abbrechen", null)
                            .show()
                    }
                    .setNeutralButton("Abbrechen", null)
                    .show()
            }
            .setPositiveButton("Neues importieren") { _, _ -> pickImageModel.launch(arrayOf("*/*")) }
            .setNegativeButton("Schließen", null)
            .show()
    }

    // ---------------------------------------------------------------- Sprache

    private fun startListening() {
        if (engine.loadedModel == null || generating != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!voice.canListen) {
            toast("Keine Spracherkennung auf dem Gerät verfügbar")
            conversationMode = false
            return
        }
        listening = true
        if (micTint == null) micTint = b.micButton.iconTint
        b.micButton.setIconTintResource(android.R.color.holo_red_dark)
        b.input.hint = "Ich höre zu …"
        voice.listen(
            onPartial = { b.input.setText(it) },
            onResult = { text ->
                listeningEnded()
                if (!text.isNullOrBlank()) send(text) else conversationMode = false
            },
            onError = { msg ->
                listeningEnded()
                conversationMode = false
                toast(msg)
            },
        )
    }

    private fun stopListening() {
        if (listening) voice.stopListening()
        listeningEnded()
    }

    private fun listeningEnded() {
        listening = false
        if (micTint != null) b.micButton.iconTint = micTint
        b.input.hint = "Schreib etwas …"
    }

    // ---------------------------------------------------------------- Dialoge

    private fun showSettings() {
        val d = DialogSettingsBinding.inflate(layoutInflater)
        d.aiName.setText(settings.aiName)
        d.userName.setText(settings.userName)
        d.persona.setText(settings.persona)
        d.temperature.value = settings.temperature.coerceIn(0.1f, 1.5f).let { (it * 20).toInt() / 20f }
        d.contextSize.value = settings.contextSize.coerceIn(2048, 16384).let { (it / 1024 * 1024).toFloat() }
        d.speak.isChecked = settings.speak
        fun labels() {
            d.tempLabel.text = "Kreativität: %.2f (höher = lebendiger, niedriger = sachlicher)".format(d.temperature.value)
            d.ctxLabel.text = "Gedächtnis: ${d.contextSize.value.toInt()} Tokens (mehr = braucht mehr RAM)"
        }
        labels()
        d.temperature.addOnChangeListener { _, _, _ -> labels() }
        d.contextSize.addOnChangeListener { _, _, _ -> labels() }

        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setView(d.root)
            .setPositiveButton("Speichern") { _, _ ->
                settings.aiName = d.aiName.text.toString().trim().ifEmpty { "Mia" }
                settings.userName = d.userName.text.toString().trim()
                settings.persona = d.persona.text.toString().trim().ifEmpty { Settings.DEFAULT_PERSONA }
                settings.temperature = d.temperature.value
                settings.speak = d.speak.isChecked
                b.toolbar.title = settings.aiName
                val ctx = d.contextSize.value.toInt()
                if (ctx != settings.contextSize) {
                    settings.contextSize = ctx
                    if (generating == null) loadActiveModel()
                }
            }
            .setNeutralButton("Standard") { _, _ ->
                settings.persona = Settings.DEFAULT_PERSONA
                settings.temperature = 0.8f
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Offline KI")
            .setMessage(
                "Die KI rechnet vollständig auf diesem Handy (llama.cpp auf dem Snapdragon-Prozessor). " +
                    "Bilder malt sie mit stable-diffusion.cpp – ebenfalls lokal.\n\n" +
                    "Die App hat keine Internet-Berechtigung – Android lässt sie gar nicht ins Netz. " +
                    "Gespräche bleiben nur auf dem Gerät.\n\n" +
                    "Sprachmodell: ${settings.activeModel ?: "keins"}\n" +
                    "Bildmodell: ${settings.activeImageModel ?: "keins"}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        /** Bleibt über Bildschirmdrehungen erhalten, damit das Modell nicht neu lädt. */
        private val engineInstance by lazy { ChatEngine() }
        private val imageEngineInstance by lazy { ImageEngine() }
    }
}
