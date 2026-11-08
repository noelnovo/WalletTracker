package win.downops.wallettracker.v2

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import win.downops.wallettracker.R
import java.math.BigDecimal
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

typealias WalletFormFactory = (String, LinearLayout, String, String, (() -> Unit)?, () -> Unit) -> AlertDialog

/** UI-only draft controller. Persistence, authorization and sync remain with the caller. */
class BillEditor(
    private val host: AppCompatActivity,
    private val design: WalletDesign,
    private val old: JSONObject?,
    private val money: (Long, String) -> String,
    private val form: WalletFormFactory,
    private val showDialog: (AlertDialog) -> Unit,
    private val save: (JSONObject) -> Unit,
) {
    private class Person(val number: Int, val name: TextInputEditText, val row: LinearLayout, val remove: MaterialButton) {
        fun label() = name.text.toString().trim().ifEmpty { "Person $number" }
    }
    private class Extra(var description: String, var minor: Long, var person: Person?)
    private val people = mutableListOf<Person>()
    private val extras = mutableListOf<Extra>()
    private var nextPerson = 1
    private var payer: Person? = null
    private var step = 0
    private var refresh: () -> Unit = {}
    private lateinit var dialog: AlertDialog
    private lateinit var total: TextInputEditText
    private lateinit var currency: MaterialAutoCompleteTextView
    private lateinit var paidBy: MaterialAutoCompleteTextView
    private lateinit var description: TextInputEditText
    private lateinit var peopleContainer: LinearLayout
    private lateinit var extraContainer: LinearLayout
    private lateinit var reviewContainer: LinearLayout
    private lateinit var error: android.widget.TextView
    private val body by lazy { design.column().apply { setPadding(design.dp(20), design.dp(8), design.dp(20), design.dp(16)) } }
    private val progress by lazy { LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL } }
    private val steps by lazy { List(3) { design.column() } }
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private var day = old?.optString("date")?.takeIf { value -> runCatching { iso.format(requireNotNull(iso.parse(value))) == value }.getOrDefault(false) } ?: today

    private fun textButton(label: String, action: () -> Unit) = MaterialButton(host, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = label; isAllCaps = false; minHeight = design.dp(48); setOnClickListener { action() }
    }

    private fun input(parent: LinearLayout, hint: String, numeric: Boolean = false): TextInputEditText {
        val box = TextInputLayout(host).apply { this.hint = hint; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val field = TextInputEditText(box.context).apply {
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        box.addView(field, LinearLayout.LayoutParams(-1, -2))
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = design.dp(12) })
        return field
    }

    private fun dropdown(parent: LinearLayout, hint: String, choices: List<String>, selected: String): MaterialAutoCompleteTextView {
        val box = TextInputLayout(host).apply {
            this.hint = hint; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        val field = MaterialAutoCompleteTextView(box.context).apply {
            inputType = InputType.TYPE_NULL; minHeight = design.dp(56)
            setPaddingRelative(design.dp(16), paddingTop, design.dp(16), paddingBottom)
            setAdapter(ArrayAdapter(host, android.R.layout.simple_list_item_1, choices)); setText(selected, false)
        }
        box.addView(field, LinearLayout.LayoutParams(-1, -2))
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = design.dp(12) })
        return field
    }

    private fun watch(field: android.widget.EditText) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { field.error = null; refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    fun show() {
        body.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = design.dp(16) })
        steps.forEach { body.addView(it) }
        val bill = design.column(20)
        total = input(bill, "Bill total", true).apply {
            textSize = 28f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            old?.let { setText(BigDecimal.valueOf(it.getLong("amount_minor"), 2).toPlainString()) }
        }
        steps[0].addView(design.card(bill))
        steps[0].addView(design.heading("Bill details", "Only the total is required."))
        val details = design.column(20)
        description = input(details, "Bill name (optional)").apply { setText(old?.optString("description")) }
        currency = dropdown(details, "Currency", listOf("EUR", "USD", "GBP"), old?.optString("currency") ?: "EUR")
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val date = input(details, "Date").apply {
            setText(dateFormat.format(requireNotNull(iso.parse(day)))); inputType = InputType.TYPE_NULL; isFocusable = false
        }
        fun chooseDate() {
            MaterialDatePicker.Builder.datePicker().setTitleText("Bill date").setSelection(requireNotNull(iso.parse(day)).time).build().apply {
                addOnPositiveButtonClickListener { value -> day = iso.format(Date(value)); date.setText(dateFormat.format(Date(value))) }
            }.show(host.supportFragmentManager, "wallet-date")
        }
        (date.parent.parent as TextInputLayout).apply {
            endIconMode = TextInputLayout.END_ICON_CUSTOM; setEndIconDrawable(R.drawable.ic_calendar)
            endIconContentDescription = "Choose date"; setEndIconOnClickListener { chooseDate() }
        }
        date.setOnClickListener { chooseDate() }
        steps[0].addView(design.card(details))

        steps[1].addView(design.heading("Who's sharing?", "Start with two. Names are optional."))
        peopleContainer = design.column()
        steps[1].addView(peopleContainer)
        old?.optJSONArray("participants")?.let { array -> for (i in 0 until array.length()) addPerson(array.getString(i)) }
        while (people.size < 2) addPerson()
        steps[1].addView(textButton("Add person") { addPerson() })
        steps[1].addView(design.heading("Individual extras", "Add only what wasn't shared equally."))
        extraContainer = design.column()
        steps[1].addView(extraContainer)
        old?.optJSONArray("extras")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                extras.add(Extra(item.optString("description"), item.getLong("amount_minor"), people.firstOrNull { it.label() == item.getString("participant") }))
            }
        }
        steps[1].addView(textButton("Add extra") { editExtra(null) }.apply { setIconResource(R.drawable.wallet_ic_plus) })

        steps[2].addView(design.heading("Everyone's share"))
        reviewContainer = design.column()
        steps[2].addView(reviewContainer)
        steps[2].addView(design.heading("Who paid the bill?", "This is separate from each person's share."))
        payer = people.firstOrNull { it.label() == old?.optString("paid_by") } ?: people.firstOrNull()
        val payment = design.column(20)
        paidBy = dropdown(payment, "Paid by", people.map { it.label() }, payer?.label().orEmpty())
        paidBy.setOnItemClickListener { _, _, index, _ -> payer = people[index]; refresh() }
        steps[2].addView(design.card(payment))
        error = design.text("", 14f, design.error)
        body.addView(error)
        refresh = {
            people.forEach { person -> person.remove.isEnabled = people.size > 2; person.remove.contentDescription = "Remove ${person.label()}" }
            paidBy.setAdapter(ArrayAdapter(host, android.R.layout.simple_list_item_1, people.map { it.label() }))
            paidBy.setText(payer?.label().orEmpty(), false)
            (total.parent.parent as TextInputLayout).suffixText = currency.text.toString()
            renderExtras(); renderReview(); error.text = ""
        }
        watch(total); watch(description)
        currency.setOnItemClickListener { _, _, _, _ -> refresh() }
        refresh()
        dialog = form(if (old == null) "Split a bill" else "Edit split bill", body, "Continue", "Cancel", { back() }, { advance() })
        dialog.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { back() } })
        showDialog(dialog)
        showStep(0)
    }

    private fun addPerson(name: String = "") {
        if (people.size >= 100) return
        val row = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        val fields = design.column()
        val number = nextPerson++
        val field = input(fields, "Person $number (name optional)").apply { setText(name) }
        val remove = textButton("") {}.apply {
            setIconResource(R.drawable.wallet_ic_close); iconSize = design.dp(18); iconPadding = 0
            minWidth = design.dp(48); minimumWidth = design.dp(48)
        }
        val person = Person(number, field, row, remove)
        row.addView(fields, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(remove, LinearLayout.LayoutParams(design.dp(48), design.dp(48)).apply { marginStart = design.dp(4) })
        remove.setOnClickListener {
            if (people.size > 2) {
                people.remove(person); peopleContainer.removeView(row)
                if (payer === person) payer = null
                extras.filter { it.person === person }.forEach { it.person = null }
                refresh()
            }
        }
        people.add(person); peopleContainer.addView(row); watch(field); refresh()
    }

    private fun values() = extras.map { BillSplitter.Extra(it.description, it.minor, people.indexOf(it.person)) }
    private fun totalValue() = BillSplitter.amount(total.text.toString())
    private fun shares() = BillSplitter.split(totalValue(), people.map { it.label() }, values())

    private fun renderExtras() {
        extraContainer.removeAllViews()
        if (extras.isEmpty()) extraContainer.addView(design.card(design.column(16).apply {
            addView(design.text("Nothing extra? Split equally.", 15f, design.ink, true))
            addView(design.text("The remainder is always shared evenly.", 13f, design.muted))
        }))
        extras.forEach { extra -> extraContainer.addView(design.group(design.actionRow(extra.description.ifEmpty { "Extra" },
            "${extra.person?.label() ?: "Choose a person"} · ${money(extra.minor, currency.text.toString())}", R.drawable.wallet_ic_expense,
            if (extra.person == null) design.error else design.ink) { editExtra(extra) })) }
    }

    private fun editExtra(existing: Extra?) {
        if (existing == null && extras.size >= 100) { error.text = "Use at most 100 extras"; return }
        val extraBody = design.column(20)
        val fields = design.column(20)
        val price = input(fields, "Extra price", true).apply {
            textSize = 28f
            existing?.let { setText(BigDecimal.valueOf(it.minor, 2).toPlainString()) }
        }
        var owner = existing?.person ?: if (existing == null) people.firstOrNull() else null
        val who = dropdown(fields, "Who had this extra?", people.map { it.label() }, owner?.label().orEmpty())
        who.setOnItemClickListener { _, _, index, _ -> owner = people[index] }
        val name = input(fields, "Extra name (optional)").apply { setText(existing?.description) }
        extraBody.addView(design.card(fields))
        val issue = design.text("", 14f, design.error); extraBody.addView(issue)
        lateinit var extraDialog: AlertDialog
        if (existing != null) extraBody.addView(textButton("Remove extra") {
            extras.remove(existing); extraDialog.dismiss(); refresh()
        }.apply { setTextColor(design.error) })
        extraDialog = form(if (existing == null) "Add extra" else "Edit extra", extraBody, if (existing == null) "Add extra" else "Save extra", "Cancel", null, {
            try {
                val minor = BillSplitter.amount(price.text.toString())
                val label = name.text.toString().trim()
                require(label.length <= 80) { "Extra names must be at most 80 characters" }
                require(owner in people) { "Choose who had this extra" }
                require(extras.filter { it !== existing }.sumOf { it.minor } + minor <= totalValue()) { "Extras cannot exceed the bill total" }
                if (existing == null) extras.add(Extra(label, minor, owner))
                else { existing.description = label; existing.minor = minor; existing.person = owner }
                extraDialog.dismiss(); refresh()
            } catch (problem: IllegalArgumentException) { issue.text = problem.message; price.error = problem.message }
        })
        showDialog(extraDialog)
    }

    private fun renderReview() {
        reviewContainer.removeAllViews()
        val result = runCatching { shares() }.getOrNull() ?: return
        val unit = currency.text.toString()
        val header = design.column(20).apply {
            addView(design.text(money(totalValue(), unit), 30f, design.onContainer, true))
            addView(design.text("${money(totalValue() - extras.sumOf { it.minor }, unit)} shared · ${money(extras.sumOf { it.minor }, unit)} extras", 13f, design.onContainer))
        }
        reviewContainer.addView(design.featuredCard(header), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = design.dp(12) })
        result.forEach { share ->
            val row = design.column(16)
            val line = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(design.text(share.name, 17f, design.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(design.text(money(share.amountMinor, unit), 20f, design.primary, true))
            row.addView(line)
            row.addView(design.text("${money(share.sharedMinor, unit)} shared" + if (share.extrasMinor > 0) " + ${money(share.extrasMinor, unit)} extras" else "", 13f, design.muted))
            if (share.name == payer?.label()) row.addView(design.text("Paid the whole bill", 13f, design.muted))
            else if (payer != null && share.amountMinor > 0) row.addView(design.text("Owes ${payer!!.label()} ${money(share.amountMinor, unit)}", 13f, design.muted))
            reviewContainer.addView(design.card(row, radius = 20), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = design.dp(8) })
        }
    }

    private fun showStep(value: Int) {
        step = value
        steps.forEachIndexed { index, view -> view.visibility = if (index == step) View.VISIBLE else View.GONE }
        progress.removeAllViews()
        listOf("Bill", "People", "Review").forEachIndexed { index, title ->
            progress.addView(design.pill("${index + 1} $title",
                if (index == step) design.container else design.color(com.google.android.material.R.attr.colorSurfaceContainerHigh),
                if (index == step) design.onContainer else design.muted).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = design.dp(8) })
        }
        dialog.window?.decorView?.findViewWithTag<MaterialButton>("wallet-form-primary")?.text = if (step == 2) "Save split" else "Continue"
        dialog.window?.decorView?.findViewWithTag<MaterialButton>("wallet-form-secondary")?.text = if (step == 0) "Cancel" else "Back"
        val token = dialog.window?.decorView?.windowToken
        (host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(token, 0)
        body.isFocusableInTouchMode = true; body.requestFocus()
        body.requestRectangleOnScreen(Rect(0, 0, body.width, design.dp(60)), true)
        error.text = ""
    }

    private fun back() { if (step > 0) showStep(step - 1) else dialog.dismiss() }

    private fun advance() {
        try {
            if (step == 0) { totalValue(); require(description.text.toString().trim().length <= 255) { "Bill names must be at most 255 characters" } }
            else shares()
            if (step < 2) { refresh(); showStep(step + 1); return }
            require(payer in people) { "Choose who paid the whole bill" }
            val labels = people.map { it.label() }
            val extraJson = JSONArray(); extras.forEach { extra -> extraJson.put(JSONObject().put("description", extra.description).put("amount_minor", extra.minor).put("participant", extra.person!!.label())) }
            val shareJson = JSONArray(); shares().forEach { share -> shareJson.put(JSONObject().put("name", share.name).put("amount_minor", share.amountMinor)) }
            save(JSONObject().put("description", description.text.toString().trim().ifEmpty { "Bill" }).put("amount_minor", totalValue())
                .put("currency", currency.text.toString()).put("date", day).put("participants", JSONArray(labels))
                .put("paid_by", payer!!.label()).put("extras", extraJson).put("shares", shareJson))
            dialog.dismiss()
        } catch (problem: IllegalArgumentException) {
            error.text = problem.message
            if (step == 0) total.error = problem.message
            error.requestRectangleOnScreen(Rect(0, 0, error.width, error.height), true)
        }
    }
}
