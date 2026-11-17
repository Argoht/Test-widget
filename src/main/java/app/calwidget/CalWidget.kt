package app.calwidget

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val P = "app.calwidget."
private const val PREV = P + "PREV"
private const val NEXT = P + "NEXT"
private const val TODAY = P + "TODAY"
private const val TOGGLE = P + "TOGGLE"
private const val CLEAR = P + "CLEAR"
private const val REFRESH = P + "REFRESH"

private const val JULIAN_EPOCH = 2440588L // Julian day of 1970-01-01, as used by CalendarContract
private const val MAX_TODOS = 12
private val HM = DateTimeFormatter.ofPattern("HH:mm")

data class Todo(val id: Long, val text: String, val done: Boolean)

object Todos {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Todo> {
        val a = JSONArray(prefs(ctx).getString("todos", "[]"))
        return List(a.length()) { i ->
            a.getJSONObject(i).let { Todo(it.getLong("id"), it.getString("text"), it.getBoolean("done")) }
        }
    }

    fun save(ctx: Context, list: List<Todo>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
        prefs(ctx).edit().putString("todos", a.toString()).apply()
    }

    fun monthOffset(ctx: Context) = prefs(ctx).getInt("offset", 0)
    fun setMonthOffset(ctx: Context, v: Int) = prefs(ctx).edit().putInt("offset", v).apply()
}

private class Ev(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val startDay: Long)

open class CalWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) = render(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val action = intent.action ?: return
        if (!action.startsWith(P)) return
        when (action) {
            PREV -> Todos.setMonthOffset(ctx, Todos.monthOffset(ctx) - 1)
            NEXT -> Todos.setMonthOffset(ctx, Todos.monthOffset(ctx) + 1)
            TODAY -> Todos.setMonthOffset(ctx, 0)
            TOGGLE -> {
                val id = intent.data?.lastPathSegment?.toLongOrNull()
                Todos.save(ctx, Todos.load(ctx).map { if (it.id == id) it.copy(done = !it.done) else it })
            }
            CLEAR -> Todos.save(ctx, Todos.load(ctx).filter { !it.done })
        }
        render(ctx)
    }

    companion object {
        fun render(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CalWidget::class.java))
            val weekIds = mgr.getAppWidgetIds(ComponentName(ctx, WeekWidget::class.java))
            if (ids.isEmpty() && weekIds.isEmpty()) return

            val pkg = ctx.packageName
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val weekStart = today.with(DayOfWeek.MONDAY)
            val month = YearMonth.from(today).plusMonths(Todos.monthOffset(ctx).toLong())
            val gridStart = month.atDay(1).with(DayOfWeek.MONDAY)

            val granted = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            val events = if (granted) loadEvents(
                ctx, zone, minOf(weekStart, gridStart), maxOf(gridStart.plusDays(42), today.plusDays(15))
            ) else emptyMap()

            val rv = RemoteViews(pkg, R.layout.widget)
            val wk = RemoteViews(pkg, R.layout.widget_week)

            // Today's agenda: as many cards as fit the widget height (a card is ~65dp, padding ~28dp)
            val heightDp = weekIds.firstOrNull()?.let { mgr.getAppWidgetOptions(it).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) } ?: 0
            val maxCards = if (heightDp == 0) 2 else ((heightDp - 22) / 65).coerceAtLeast(1)
            wk.setTextViewText(R.id.day, today.format(DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)).trimEnd('.').replaceFirstChar { it.uppercase() })
            wk.setTextViewText(R.id.daynum, today.dayOfMonth.toString())
            wk.setOnClickPendingIntent(R.id.header, openDay(ctx, zone, today))
            wk.setOnClickPendingIntent(R.id.new_event, PendingIntent.getActivity(
                ctx, 0, Intent(Intent.ACTION_INSERT, Events.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
            ))
            wk.removeAllViews(R.id.events)
            val todayEvs = events[today].orEmpty()
            val cards = when {
                !granted -> listOf("Autoriser l'agenda" to "Appuie pour afficher tes événements")
                todayEvs.isEmpty() -> listOf("Rien de prévu" to "Journée libre")
                else -> {
                    val shown = if (todayEvs.size > maxCards) maxCards - 1 else todayEvs.size
                    todayEvs.take(shown).map { e ->
                        e.title to if (e.allDay) "Toute la journée"
                        else Instant.ofEpochMilli(e.begin).atZone(zone).format(HM) + " – " + Instant.ofEpochMilli(e.end).atZone(zone).format(HM)
                    } + if (todayEvs.size > shown) listOf("+${todayEvs.size - shown} autres" to "Voir dans l'agenda") else emptyList()
                }
            }
            for ((title, time) in cards) {
                val card = RemoteViews(pkg, R.layout.event_card)
                card.setTextViewText(R.id.title, title)
                card.setTextViewText(R.id.time, time)
                card.setOnClickPendingIntent(R.id.card, if (granted) openDay(ctx, zone, today) else openApp(ctx))
                wk.addView(R.id.events, card)
            }

            // Month grid
            val title = month.month.getDisplayName(TextStyle.FULL, Locale.FRENCH).uppercase(Locale.FRENCH)
            rv.setImageViewBitmap(R.id.month_title, dotText(ctx, if (month.year == today.year) title else "$title ${month.year}", 16f))
            rv.setOnClickPendingIntent(R.id.month_title, broadcast(ctx, TODAY))
            rv.setOnClickPendingIntent(R.id.prev, broadcast(ctx, PREV))
            rv.setOnClickPendingIntent(R.id.next_month, broadcast(ctx, NEXT))
            rv.removeAllViews(R.id.month)
            for (r in 0..5) {
                val rowStart = gridStart.plusDays(r * 7L)
                val row = RemoteViews(pkg, R.layout.month_row)
                if (rowStart == weekStart) row.setInt(R.id.row, "setBackgroundResource", R.drawable.bg_week_row)
                for (c in 0..6) {
                    val d = rowStart.plusDays(c.toLong())
                    val inMonth = YearMonth.from(d) == month
                    val n = if (inMonth) events[d]?.size ?: 0 else 0
                    val cell = RemoteViews(pkg, R.layout.month_cell)
                    cell.setTextViewText(R.id.num, d.dayOfMonth.toString())
                    if (!inMonth) cell.setColorStateList(R.id.num, "setTextColor", R.color.fg3)
                    if (d == today) {
                        cell.setInt(R.id.num, "setBackgroundResource", R.drawable.circle_red)
                        cell.setTextColor(R.id.num, Color.WHITE)
                    }
                    cell.setViewVisibility(R.id.dot1, if (n > 0) View.VISIBLE else View.INVISIBLE)
                    cell.setViewVisibility(R.id.dot2, if (n > 1) View.VISIBLE else View.GONE)
                    cell.setOnClickPendingIntent(R.id.cell, openDay(ctx, zone, d))
                    row.addView(R.id.row, cell)
                }
                rv.addView(R.id.month, row)
            }

            // Upcoming events (from tomorrow: today's are already in the week strip)
            if (granted) {
                val next = events.entries
                    .filter { it.key > today }
                    .flatMap { (d, l) -> l.filter { it.startDay == d.toEpochDay() + JULIAN_EPOCH }.map { d to it } }
                    .take(2)
                    .map { (d, e) ->
                        val label = if (d == today.plusDays(1)) "Demain"
                        else d.format(DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH)).replaceFirstChar { it.uppercase() }
                        val time = if (e.allDay) "" else Instant.ofEpochMilli(e.begin).atZone(zone).format(HM) + " "
                        SpannableString("$label · $time${e.title}").apply {
                            setSpan(StyleSpan(Typeface.BOLD), 0, label.length, 0)
                        }
                    }
                rv.setTextViewText(R.id.next1, next.getOrNull(0) ?: "Rien de prévu")
                rv.setTextViewText(R.id.next2, next.getOrNull(1) ?: "")
                rv.setOnClickPendingIntent(R.id.upcoming, openDay(ctx, zone, today))
            } else {
                rv.setTextViewText(R.id.next1, "Appuie ici pour afficher")
                rv.setTextViewText(R.id.next2, "les événements de ton agenda")
                rv.setOnClickPendingIntent(R.id.upcoming, openApp(ctx))
            }

            // To-do
            val todos = Todos.load(ctx)
            rv.setTextViewText(R.id.todo_count, todos.count { !it.done }.toString())
            rv.removeAllViews(R.id.todos)
            // shortcut: no scrolling, only the first MAX_TODOS are shown; switch to a ListView if the list grows
            for (t in todos.sortedBy { it.done }.take(MAX_TODOS)) {
                val item = RemoteViews(pkg, R.layout.todo_item)
                item.setTextViewText(R.id.text, t.text)
                if (t.done) {
                    item.setImageViewResource(R.id.check, R.drawable.circle_red)
                    item.setColorStateList(R.id.text, "setTextColor", R.color.fg3)
                    item.setInt(R.id.text, "setPaintFlags", Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
                }
                item.setOnClickPendingIntent(R.id.item, broadcast(ctx, TOGGLE, t.id))
                rv.addView(R.id.todos, item)
            }
            rv.setViewVisibility(R.id.clear, if (todos.any { it.done }) View.VISIBLE else View.GONE)
            rv.setOnClickPendingIntent(R.id.clear, broadcast(ctx, CLEAR))
            rv.setOnClickPendingIntent(R.id.add, openApp(ctx))

            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, rv)
            if (weekIds.isNotEmpty()) mgr.updateAppWidget(weekIds, wk)

            // Redraw just after midnight so "today" moves; calendar edits show up within updatePeriodMillis (30 min)
            ctx.getSystemService(AlarmManager::class.java).set(
                AlarmManager.RTC,
                today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5_000,
                broadcast(ctx, REFRESH)
            )
        }

        // RemoteViews ignores custom fonts on TextViews here, so dot-matrix titles are drawn as bitmaps
        private fun dotText(ctx: Context, text: String, sp: Float): Bitmap {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = ctx.resources.getFont(R.font.doto)
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, ctx.resources.displayMetrics)
                color = Color.WHITE
            }
            val fm = paint.fontMetricsInt
            val bmp = Bitmap.createBitmap(paint.measureText(text).toInt() + 2, fm.descent - fm.ascent, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawText(text, 0f, -fm.ascent.toFloat(), paint)
            return bmp
        }

        private fun loadEvents(ctx: Context, zone: ZoneId, from: LocalDate, to: LocalDate): Map<LocalDate, List<Ev>> {
            val uri = Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, from.atStartOfDay(zone).toInstant().toEpochMilli())
                ContentUris.appendId(it, to.atStartOfDay(zone).toInstant().toEpochMilli())
            }.build()
            val out = sortedMapOf<LocalDate, MutableList<Ev>>()
            val cols = arrayOf(Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.START_DAY, Instances.END_DAY)
            ctx.contentResolver.query(uri, cols, "${Instances.VISIBLE}=1", null, "${Instances.ALL_DAY} DESC, ${Instances.BEGIN}")?.use { c ->
                while (c.moveToNext()) {
                    val ev = Ev(c.getString(0) ?: "(sans titre)", c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getLong(4))
                    for (jd in c.getLong(4)..c.getLong(5)) {
                        val d = LocalDate.ofEpochDay(jd - JULIAN_EPOCH)
                        if (d >= from && d < to) out.getOrPut(d) { mutableListOf() } += ev
                    }
                }
            }
            return out
        }

        private fun broadcast(ctx: Context, action: String, id: Long = 0): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0,
            // the data URI makes each PendingIntent distinct
            Intent(ctx, CalWidget::class.java).setAction(action).setData(Uri.parse("calwidget://$action/$id")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun openDay(ctx: Context, zone: ZoneId, d: LocalDate): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/" + d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Today's agenda on its own, so the two halves can each fill their launcher cells. Shares CalWidget's logic. */
class WeekWidget : CalWidget()
