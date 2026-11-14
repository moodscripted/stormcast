package com.example.stormcast

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/**
 * Rounded, theme-aware dialog used instead of the stock AlertDialog so every pop-up matches
 * the rest of the app (light and dark).
 */
class SheetDialog(private val act: Activity) : Dialog(act) {

    private val titleView = TextView(act).apply {
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(act.cText())
    }
    val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val buttons = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(22), act.dp(22), act.dp(22), act.dp(12))
            background = GradientDrawable().apply {
                setColor(act.cSurface())
                cornerRadius = act.dp(24).toFloat()
            }
        }
        root.addView(titleView)
        root.addView(
            ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { topMargin = act.dp(8) }
        )
        root.addView(
            buttons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = act.dp(14) }
        )
        setContentView(root)
    }

    fun title(t: String): SheetDialog { titleView.text = t; return this }

    fun message(t: String): SheetDialog {
        body.addView(TextView(act).apply {
            text = t
            textSize = 14.5f
            setTextColor(act.cDim())
            setLineSpacing(0f, 1.15f)
        })
        return this
    }

    /** Text button on the bottom row. Set [dismiss] = false to keep the dialog open. */
    fun button(
        label: String, primary: Boolean = false, dismiss: Boolean = true, onClick: () -> Unit = {}
    ): SheetDialog {
        buttons.addView(TextView(act).apply {
            text = label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (primary) act.cPrimary() else act.cDim())
            setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
            setOnClickListener {
                if (dismiss) dismiss()
                onClick()
            }
        })
        return this
    }

    override fun show() {
        super.show()
        val w = minOf((act.resources.displayMetrics.widthPixels * 0.92).toInt(), act.dp(520))
        window?.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}

// ---- building blocks ----

private fun Activity.tileColor(): Int = if (isNight()) 0x1AFFFFFF else 0x0F182238

fun Activity.inputField(hint: String, initial: String = ""): EditText = EditText(this).apply {
    this.hint = hint
    setText(initial)
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    setSingleLine()
    imeOptions = EditorInfo.IME_ACTION_SEARCH
    textSize = 16f
    setTextColor(cText())
    setHintTextColor(cDim())
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = GradientDrawable().apply {
        setColor(tileColor())
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), if (isNight()) 0x3D8FA3C4 else 0x335C6C8C)
    }
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(12) }
}

fun Activity.sectionLabel(text: String): TextView = TextView(this).apply {
    this.text = text.uppercase()
    textSize = 11f
    letterSpacing = 0.06f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(cPrimary())
    setPadding(dp(2), dp(18), 0, dp(4))
}

fun Activity.sheetRow(
    icon: String, title: String, sub: String? = null, trailing: View? = null, onClick: () -> Unit
): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(14), dp(12), dp(8), dp(12))
    background = GradientDrawable().apply {
        setColor(tileColor())
        cornerRadius = dp(14).toFloat()
    }
    addView(TextView(context).apply {
        text = icon
        textSize = 20f
        setPadding(0, 0, dp(12), 0)
    })
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 15.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText())
            })
            if (sub != null) addView(TextView(context).apply {
                text = sub
                textSize = 12.5f
                setTextColor(cDim())
            })
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    if (trailing != null) addView(trailing)
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }
}

/** Title + optional subtitle on the left, switch on the right. */
fun Activity.switchRow(title: String, sub: String?, checked: Boolean): Pair<View, SwitchCompat> {
    val sw = SwitchCompat(this).apply { isChecked = checked }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(8), dp(2), dp(8))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 15.5f
                    setTextColor(cText())
                })
                if (sub != null) addView(TextView(context).apply {
                    text = sub
                    textSize = 12.5f
                    setTextColor(cDim())
                })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(sw)
        setOnClickListener { sw.toggle() }
    }
    return row to sw
}

/** Pill-shaped option picker. Returns the view and a getter for the selected index. */
fun Activity.segmented(labels: List<String>, selected: Int): Pair<View, () -> Int> {
    var cur = selected.coerceIn(0, labels.size - 1)
    val (row, tabs) = tabRow(labels)
    (row.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(6), 0, dp(2))
    fun style() { tabs.forEachIndexed { i, t -> styleTab(t, i == cur) } }
    tabs.forEachIndexed { i, t ->
        t.textSize = 13.5f
        t.setOnClickListener { cur = i; style() }
    }
    style()
    return row to { cur }
}
