// =============================================================================
// Datei:    TorWidget.kt
// Zweck:    Startbildschirm-Widget: ein Tipp öffnet das Tor
// Autor:    Stefan Weinzetl
// =============================================================================
package at.weinzetl.torapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class TorWidget : AppWidgetProvider() {

    override fun onUpdate(kontext: Context, manager: AppWidgetManager, ids: IntArray) {
        val absicht = Intent(kontext, AusloeserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val ausloeser = PendingIntent.getActivity(
            kontext, 0, absicht,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (id in ids) {
            val ansicht = RemoteViews(kontext.packageName, R.layout.widget_tor)
            ansicht.setOnClickPendingIntent(R.id.widget_wurzel, ausloeser)
            manager.updateAppWidget(id, ansicht)
        }
    }
}
