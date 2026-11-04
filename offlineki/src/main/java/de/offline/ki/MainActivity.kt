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

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var models: ModelStore
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
        voice = Voice(this)
        voice.onFinishedSpeaking = {
            if (conversationMode && generating == null && !isFinishing) startListening()
        }

        messages = store.load()
        adapter = MessageAdapter(messages)
        b.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.messages.adapter = adapter

        b.toolbar.title = settings.aiName
        b.toolbar.inflateMenu(R.menu.main)
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_new_chat -> newChat()
                R.id.action_settings -> showSettings()
                R.id.action_models -> showModels()
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
        b.pickModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }

        loadActiveModel()
    }

    override fun onDestroy() {
        if (generating != null) engine.stop()
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
        engine.stop()
        voice.stopSpeaking()
    }

    private fun newChat() {
        stopGenerating()
        lifecycleScope.launch {
            generating?.join()
            engine.reset()
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
                "Die KI rechnet vollständig auf diesem Handy (llama.cpp auf dem Snapdragon-Prozessor).\n\n" +
                    "Die App hat keine Internet-Berechtigung – Android lässt sie gar nicht ins Netz. " +
                    "Gespräche bleiben nur auf dem Gerät.\n\n" +
                    "Modell: ${settings.activeModel ?: "keins"}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        /** Bleibt über Bildschirmdrehungen erhalten, damit das Modell nicht neu lädt. */
        private val engineInstance by lazy { ChatEngine() }
    }
}
