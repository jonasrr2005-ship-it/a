package de.zugreise.app

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Texterkennung auf dem Gerät (Google ML Kit, funktioniert offline). */
object ImageText {

    suspend fun read(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            // Zeilen nach ihrer Lage im Bild zu Bildschirmzeilen zusammensetzen, damit z. B.
            // "10:24" (linke Spalte) und "München Hbf" (rechte Spalte) in einer Zeile landen.
            data class Piece(val top: Int, val bottom: Int, val left: Int, val text: String)
            val pieces = result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    Piece(box.top, box.bottom, box.left, line.text)
                }
            }.sortedBy { it.top }
            val rows = mutableListOf<MutableList<Piece>>()
            for (p in pieces) {
                val row = rows.lastOrNull()
                val center = (p.top + p.bottom) / 2
                if (row != null && row.any { center in it.top..it.bottom }) row += p else rows += mutableListOf(p)
            }
            return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString(" ") { it.text } }
                .ifBlank { result.text }
        } finally {
            recognizer.close()
        }
    }
}
