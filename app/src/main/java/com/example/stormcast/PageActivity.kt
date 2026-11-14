package com.example.stormcast

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Full-screen page used for the cyclone pop-outs (replaces the old AlertDialogs):
 *  - TEXT mode: an NHC text product fetched from a URL and laid out for a phone
 *  - MAP mode: the cone & track Leaflet page
 */
class PageActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val EXTRA_REFLOW = "reflow"
        private const val EXTRA_MAP = "map"

        // Map HTML can exceed the Intent size limit, so hand it over in memory.
        private var pendingMapHtml: String? = null

        fun openText(ctx: Context, title: String, url: String, reflow: Boolean) {
            ctx.startActivity(
                Intent(ctx, PageActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_REFLOW, reflow)
            )
        }

        fun openMap(ctx: Context, title: String, html: String) {
            pendingMapHtml = html
            ctx.startActivity(
                Intent(ctx, PageActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_MAP, true)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val isMap = intent.getBooleanExtra(EXTRA_MAP, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cHeader())
            setPadding(dp(6), dp(10), dp(16), dp(10))
            elevation = dp(3).toFloat()
        }
        bar.addView(TextView(this).apply {
            text = "\u2190"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(2), dp(14), dp(2))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = title
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(bar)

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        if (isMap) {
            val w = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Leaflet handles pinch-zoom itself; WebView zoom would scale the whole page.
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                setBackgroundColor(0xFF0B1220.toInt())
            }
            web = w
            root.addView(w, lp)
            setContentView(root)
            w.loadDataWithBaseURL(
                "https://www.openstreetmap.org/",
                pendingMapHtml ?: "<p>No map data</p>",
                "text/html", "UTF-8", null
            )
        } else {
            val reflow = intent.getBooleanExtra(EXTRA_REFLOW, true)
            val tv = TextView(this).apply {
                setTextIsSelectable(true)
                setTextColor(cText())
                setPadding(dp(18), dp(16), dp(18), dp(40))
                if (reflow) {
                    textSize = 16f
                    setLineSpacing(dp(3).toFloat(), 1f)
                } else {
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                }
                text = "Loading\u2026"
            }
            val scroller: View = ScrollView(this).apply {
                if (reflow) addView(tv)
                else addView(HorizontalScrollView(context).apply { addView(tv) })
            }
            root.addView(scroller, lp)
            setContentView(root)

            val url = intent.getStringExtra(EXTRA_URL) ?: ""
            scope.launch {
                tv.text = try {
                    val plain = extractText(Api.getText(url))
                    if (reflow) reflowParagraphs(plain) else plain
                } catch (e: Exception) {
                    "Couldn't load this product.\n\n${e.message ?: "Network error"}"
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        web?.destroy()
        super.onDestroy()
    }

    /** NHC text products live in <pre> blocks; fall back to the whole page. */
    private fun extractText(html: String): String {
        val pre = Regex("<pre[^>]*>(.*?)</pre>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).map { it.groupValues[1] }.toList()
        val src = if (pre.isNotEmpty()) pre.joinToString("\n\n")
        else html.replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
        return Html.fromHtml(src, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace("\r", "")
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * NHC text is hard-wrapped at ~69 columns. Join wrapped lines within a paragraph so the
     * text flows to the screen; keep "KEY...value" lines, bullets and "$$" on their own lines.
     */
    private fun reflowParagraphs(s: String): String {
        val structural = Regex("""^([A-Z][A-Z0-9 /&-]{2,}\.{3}.*|[-*\u2022].*|\$\$|\.\.\..*)$""")
        val out = StringBuilder()
        for (para in s.split(Regex("\n\\s*\n"))) {
            var cur = StringBuilder()
            for (raw in para.lines()) {
                val l = raw.trim()
                if (l.isEmpty()) continue
                val breakBefore = structural.matches(l) ||
                    (cur.isNotEmpty() && structural.matches(cur.lines().last()))
                if (cur.isNotEmpty() && breakBefore) {
                    out.append(cur).append('\n'); cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(l)
            }
            if (cur.isNotEmpty()) out.append(cur)
            out.append("\n\n")
        }
        return out.toString().trim()
    }
}
