package de.offline.ki

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Verwaltet Modelldateien im privaten App-Speicher.
 * Sprachmodelle: .gguf. Bildmodelle (Stable Diffusion): .safetensors, .gguf oder .ckpt.
 */
class ModelStore(
    private val context: Context,
    folder: String = "models",
    private val extensions: List<String> = listOf(".gguf"),
) {
    val dir: File = File(context.filesDir, folder).apply { mkdirs() }

    private fun accepted(name: String) = extensions.any { name.endsWith(it, ignoreCase = true) }

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && accepted(f.name) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun file(name: String) = File(dir, name)

    /** Kopiert eine vom Nutzer gewählte Datei in den App-Speicher. */
    fun import(uri: Uri, onProgress: (Float) -> Unit): File {
        var name = "modell.gguf"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
            }
        }
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!accepted(name)) {
            throw IOException("Falscher Dateityp: $name\nErlaubt: ${extensions.joinToString(", ")}")
        }
        if (size > 0 && size > dir.usableSpace - 200L * 1024 * 1024) {
            throw IOException("Nicht genug freier Speicher (benötigt ca. ${size / 1_000_000} MB)")
        }

        val target = File(dir, name)
        val tmp = File(dir, "$name.part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4) throw IOException("Datei ist leer")
            if (name.endsWith(".gguf", ignoreCase = true) && String(magic, Charsets.US_ASCII) != "GGUF") {
                throw IOException("Datei ist kein gültiges GGUF-Modell")
            }
            tmp.outputStream().buffered(1 shl 20).use { out ->
                out.write(magic)
                val buf = ByteArray(1 shl 20)
                var copied = 4L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (size > 0 && copied - lastReport > 16L * 1024 * 1024) {
                        lastReport = copied
                        onProgress(copied.toFloat() / size)
                    }
                }
            }
        } ?: throw IOException("Datei konnte nicht geöffnet werden")

        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Datei konnte nicht gespeichert werden")
        }
        onProgress(1f)
        return target
    }

    fun cleanupPartial() {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }
}
