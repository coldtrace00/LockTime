package io.github.timelock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Окремий екран вибору додатка: пошук і повний список.
 * Повертає вибраний пакет у MainActivity, яка відкриває вікно правил.
 */
class AppPickerActivity : Activity() {

    private var all: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()
    private var limited: Set<String> = emptySet()
    private lateinit var adapter: PickerAdapter
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Look.themeRes(this))
        super.onCreate(savedInstanceState)
        title = getString(R.string.picker_title)
        val prefs = Prefs(this)
        limited = prefs.rules().keys + prefs.pendingRules().keys

        val search = EditText(this).apply {
            hint = getString(R.string.picker_search)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            background = Look.rounded(Look.card(this@AppPickerActivity), 22 * resources.displayMetrics.density)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextColor(Look.text(this@AppPickerActivity))
            setHintTextColor(Look.muted(this@AppPickerActivity))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
        }
        status = TextView(this).apply {
            text = getString(R.string.loading_apps)
            setTextColor(Look.muted(this@AppPickerActivity))
            setPadding(dp(4), dp(12), 0, 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
            addView(search)
            addView(status)
        }

        adapter = PickerAdapter()
        val list = ListView(this).apply {
            divider = null
            adapter = this@AppPickerActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val app = shown.getOrNull(position) ?: return@setOnItemClickListener
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGE, app.pkg))
                finish()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        Thread {
            val items = Apps.loadLaunchable(this)
            runOnUiThread {
                all = items
                status.visibility = View.GONE
                filter(search.text.toString())
            }
        }.start()
    }

    private fun filter(query: String) {
        val q = query.trim().lowercase()
        shown = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) || it.pkg.contains(q) }
        adapter.notifyDataSetChanged()
    }

    private class Holder(val icon: ImageView, val name: TextView, val note: TextView)

    private inner class PickerAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: View
            val h: Holder
            if (convertView == null) {
                val icon = ImageView(this@AppPickerActivity)
                val name = TextView(this@AppPickerActivity).apply {
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Look.text(this@AppPickerActivity))
                }
                val note = TextView(this@AppPickerActivity).apply {
                    textSize = 13f
                    setTextColor(Look.accentText(this@AppPickerActivity))
                }
                row = LinearLayout(this@AppPickerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(10), dp(16), dp(10))
                    addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(LinearLayout(this@AppPickerActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), 0, 0, 0)
                        addView(name)
                        addView(note)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                h = Holder(icon, name, note)
                row.tag = h
            } else {
                row = convertView
                h = convertView.tag as Holder
            }
            val app = shown[position]
            h.icon.setImageDrawable(app.icon)
            h.name.text = app.label
            h.note.visibility = if (app.pkg in limited) View.VISIBLE else View.GONE
            h.note.text = getString(R.string.picker_has_limit)
            return row
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
