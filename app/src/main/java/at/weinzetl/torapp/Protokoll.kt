// =============================================================================
// Datei:    Protokoll.kt
// Zweck:    Protokollierung mit rotierender Datei (5 MB, 5 Sicherungen),
//           Konsolenausgabe und Speicherpuffer für die Anzeige in der App
// Autor:    Stefan Weinzetl
// Format:   "%(asctime)s [%(levelname)s] %(message)s"
// Hinweis:  Reines Kotlin ohne Android-Bezug (lokal testbar)
// =============================================================================
package at.weinzetl.torapp

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Stufe { DEBUG, INFO, WARNING, ERROR }

class Protokoll(
    private val verzeichnis: File?,
    @Volatile var debugModus: Boolean = false,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val sicherungen: Int = 5,
    private val konsole: (Stufe, String) -> Unit = { _, zeile -> println(zeile) },
) {
    private val puffer = ArrayDeque<String>()
    private val zeitFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)

    /** Wird nach jeder neuen Zeile mit dem gesamten Puffertext aufgerufen. */
    @Volatile
    var beobachter: ((String) -> Unit)? = null

    fun debug(nachricht: String) = log(Stufe.DEBUG, nachricht)
    fun info(nachricht: String) = log(Stufe.INFO, nachricht)
    fun warnung(nachricht: String) = log(Stufe.WARNING, nachricht)
    fun fehler(nachricht: String) = log(Stufe.ERROR, nachricht)

    fun log(stufe: Stufe, nachricht: String) {
        if (stufe == Stufe.DEBUG && !debugModus) return
        val text: String
        synchronized(this) {
            val zeile = "${zeitFormat.format(Date())} [${stufe.name}] $nachricht"
            puffer.addLast(zeile)
            while (puffer.size > 300) puffer.removeFirst()
            konsole(stufe, zeile)
            dateiSchreiben(zeile)
            text = puffer.joinToString("\n")
        }
        beobachter?.invoke(text)
    }

    @Synchronized
    fun text(): String = puffer.joinToString("\n")

    private fun dateiSchreiben(zeile: String) {
        val ordner = verzeichnis ?: return
        try {
            if (!ordner.exists()) ordner.mkdirs()
            val datei = File(ordner, "torapp.log")
            if (datei.exists() && datei.length() + zeile.length + 1 > maxBytes) rotieren(ordner)
            datei.appendText(zeile + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            konsole(Stufe.WARNING, "Protokolldatei nicht schreibbar: ${e.message}")
        }
    }

    private fun rotieren(ordner: File) {
        File(ordner, "torapp.log.$sicherungen").delete()
        for (i in sicherungen - 1 downTo 1) {
            val quelle = File(ordner, "torapp.log.$i")
            if (quelle.exists()) quelle.renameTo(File(ordner, "torapp.log.${i + 1}"))
        }
        File(ordner, "torapp.log").renameTo(File(ordner, "torapp.log.1"))
    }
}
