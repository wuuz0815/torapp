// =============================================================================
// Datei:    AusloeserActivity.kt
// Zweck:    Unsichtbare Aktivität für das Widget: öffnet das Tor ohne die
//           Hauptoberfläche und meldet das Ergebnis per Kurzmeldung (Toast)
// Autor:    Stefan Weinzetl
// =============================================================================
package at.weinzetl.torapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class AusloeserActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val steuerung = Gemeinsam.steuerung(this)
        if (steuerung == null || Gemeinsam.fehlendeBerechtigungen(this, fuerSuche = false).isNotEmpty()) {
            Toast.makeText(this, "Bitte die Tor-App einmal öffnen und einrichten.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        Toast.makeText(this, "Öffne Tor …", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread({
            val (ok, meldung) = steuerung.torOeffnen()
            runOnUiThread {
                Toast.makeText(app, meldung, Toast.LENGTH_LONG).show()
                if (ok) Gemeinsam.vibrieren(app)
                if (meldung == TorSteuerung.PASSWORT_FEHLT || meldung == TorSteuerung.PASSWORT_FALSCH) {
                    startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                finish()
            }
        }, "widget-tor-oeffnen").apply { isDaemon = true }.start()
    }
}
