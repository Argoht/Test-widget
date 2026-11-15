package app.calwidget

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/** Small dialog: adds a to-do, and asks for the calendar permission the widget needs. */
class MainActivity : Activity() {
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        input = EditText(this).apply {
            hint = "À faire…"
            isSingleLine = true
            setOnEditorActionListener { _, _, _ -> add(); true }
        }
        val button = Button(this).apply {
            text = "Ajouter"
            setOnClickListener { add() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(input)
            addView(button)
        })
        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CalWidget.render(this)
    }

    private fun add() {
        val text = input.text.toString().trim()
        if (isFinishing) return
        if (text.isNotEmpty()) {
            Todos.save(this, Todos.load(this) + Todo(System.currentTimeMillis(), text, false))
            CalWidget.render(this)
        }
        finish()
    }
}
