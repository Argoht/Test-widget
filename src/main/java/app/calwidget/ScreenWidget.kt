package app.calwidget

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Process
import android.provider.Settings
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId

/** Today's screen time, in the style of Nothing's "Hors écran" widget. */
class ScreenWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == REFRESH) render(ctx)
    }

    companion object {
        private const val REFRESH = "app.calwidget.SCREEN_REFRESH"

        fun render(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ScreenWidget::class.java))
            if (ids.isEmpty()) return

            val refresh = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, ScreenWidget::class.java).setAction(REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val rv = RemoteViews(ctx.packageName, R.layout.widget_screen)
            val serif = CalWidget.nothingSerif ?: Typeface.SERIF
            if (hasUsageAccess(ctx)) {
                rv.setImageViewBitmap(R.id.time, CalWidget.textBitmap(ctx, format(screenTimeToday(ctx)), 26f, serif, Color.WHITE, tight = true))
                rv.setTextViewText(R.id.label, "Temps d'écran")
                rv.setOnClickPendingIntent(android.R.id.background, refresh)
            } else {
                rv.setImageViewBitmap(R.id.time, CalWidget.textBitmap(ctx, "–", 26f, serif, Color.WHITE, tight = true))
                rv.setTextViewText(R.id.label, "Appuie pour autoriser")
                rv.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(
                    ctx, 0, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
                ))
            }
            mgr.updateAppWidget(ids, rv)

            // Non-wakeup alarm: refreshes every ~15 min while the phone is in use, and on the next unlock otherwise
            ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 15 * 60_000, refresh)
        }

        fun format(ms: Long): String {
            val min = ms / 60_000
            return if (min < 60) "${min}m" else "${min / 60}h ${min % 60}m"
        }

        private fun hasUsageAccess(ctx: Context) = ctx.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED

        /** Time apps (other than the home screen) were in the foreground since midnight, like Digital Wellbeing counts it. */
        private fun screenTimeToday(ctx: Context): Long {
            val now = System.currentTimeMillis()
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
            val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(start, now)
            val e = UsageEvents.Event()
            var fg: String? = null
            var since = 0L
            var total = 0L
            // shortcut: an app already open at midnight only counts from its next resume; fine for a daily total
            while (events.getNextEvent(e)) {
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (fg != null) total += e.timeStamp - since
                        fg = e.packageName.takeIf { it != home }
                        since = e.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == fg) { total += e.timeStamp - since; fg = null }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> if (fg != null) { total += e.timeStamp - since; fg = null }
                }
            }
            if (fg != null) total += now - since
            return total
        }
    }
}
