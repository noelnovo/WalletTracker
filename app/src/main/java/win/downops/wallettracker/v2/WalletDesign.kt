package win.downops.wallettracker.v2

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import win.downops.wallettracker.R

/** Passive native-View design components. No data, networking or authentication ownership. */
class WalletDesign(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun color(attribute: Int) = MaterialColors.getColor(context, attribute, Color.DKGRAY)
    val ink get() = color(com.google.android.material.R.attr.colorOnSurface)
    val muted get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    val primary get() = color(androidx.appcompat.R.attr.colorPrimary)
    val surface get() = color(com.google.android.material.R.attr.colorSurface)
    val cardSurface get() = color(com.google.android.material.R.attr.colorSurfaceContainerLow)
    val container get() = color(com.google.android.material.R.attr.colorPrimaryContainer)
    val onContainer get() = color(com.google.android.material.R.attr.colorOnPrimaryContainer)
    val error get() = color(androidx.appcompat.R.attr.colorError)

    fun text(value: String, size: Float = 16f, tint: Int = ink, medium: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(tint)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setPadding(0, dp(4), 0, dp(4))
        if (size >= 28f) fontFeatureSettings = "tnum"
    }

    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun rounded(fill: Int, radius: Int = 16) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(radius).toFloat(); setColor(fill)
    }

    fun card(body: View, fill: Int = cardSurface, radius: Int = 24) = MaterialCardView(context).apply {
        this.radius = dp(radius).toFloat(); cardElevation = 0f; strokeWidth = 0
        setCardBackgroundColor(fill); clipToOutline = true
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }

    fun featuredCard(body: View) = card(body, container)

    fun icon(resource: Int, tint: Int = primary) = ImageView(context).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun badge(resource: Int, size: Int = 44, fill: Int = container, tint: Int = primary): LinearLayout {
        val holder = LinearLayout(context).apply {
            gravity = Gravity.CENTER; background = rounded(fill, if (size >= 60) 22 else 14)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        holder.addView(icon(resource, tint), LinearLayout.LayoutParams(dp(if (size >= 60) 30 else 22), dp(if (size >= 60) 30 else 22)))
        holder.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return holder
    }

    fun pill(label: String, fill: Int = container, tint: Int = onContainer) = text(label, 12f, tint, true).apply {
        background = rounded(fill, 12); setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    fun heading(title: String, helper: String? = null) = column().apply {
        addView(text(title, 20f, ink, true))
        if (helper != null) addView(text(helper, 13f, muted))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(12) }
    }

    fun divider() = View(context).apply {
        setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(64); marginEnd = dp(16) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun actionRow(title: String, subtitle: String?, resource: Int, tint: Int = ink, action: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(76)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isFocusable = true; contentDescription = listOfNotNull(title, subtitle).joinToString(", ")
            setOnClickListener { action() }
            val value = android.util.TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
                background = ContextCompat.getDrawable(context, value.resourceId)
            }
        }
        row.addView(icon(resource, tint), LinearLayout.LayoutParams(dp(24), dp(24)))
        val labels = column().apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(text(title, 16f, tint, true))
            if (subtitle != null) addView(text(subtitle, 13f, muted))
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16); marginEnd = dp(8) })
        row.addView(icon(R.drawable.wallet_ic_chevron, muted), LinearLayout.LayoutParams(dp(18), dp(18)))
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = "android.widget.Button"
            }
        })
        return row
    }

    fun group(vararg rows: View) = card(column().apply {
        rows.forEachIndexed { index, row -> if (index > 0) addView(divider()); addView(row) }
    }).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) } }
}
