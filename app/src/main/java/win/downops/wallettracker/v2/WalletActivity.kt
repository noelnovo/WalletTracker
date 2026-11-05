package win.downops.wallettracker.v2

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import win.downops.wallettracker.BuildConfig
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher

/** Material 3 shell. Data, transport, crypto and sync are deliberately outside the view. */
class WalletActivity : AppCompatActivity() {
    private val model: WalletModel by viewModels()
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var screen = 1
    private var showingLogin = false
    private var networkRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { model.syncSoon() } }
    }
    private val ink get() = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY)
    private val muted get() = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val primary get() = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        screen = savedInstanceState?.getInt("screen", 1) ?: 1
        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.changes.collect { if (model.unlocked || !showingLogin) render() } }
                launch { model.status.collect { if (model.unlocked) render() } }
            }
        }
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.registerDefaultNetworkCallback(networkCallback)
        networkRegistered = true
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("screen", screen); super.onSaveInstanceState(outState) }
    override fun onStop() {
        if (!isChangingConfigurations && model.unlocked) { showingLogin = false; model.lock() }
        super.onStop()
    }
    override fun onDestroy() {
        if (networkRegistered) (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
    private fun text(value: String, size: Float = 16f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5))
    }
    private fun button(label: String, prominent: Boolean = true, action: () -> Unit) = MaterialButton(this, null,
        if (prominent) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label; isAllCaps = false; minHeight = dp(52); setOnClickListener { action() }
    }
    private fun input(parent: LinearLayout, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): TextInputEditText {
        val box = TextInputLayout(this).apply { this.hint = hint; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val field = TextInputEditText(box.context).apply { inputType = type; maxLines = 1 }
        box.addView(field)
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return field
    }
    private fun message(value: String) { Snackbar.make(root, value, Snackbar.LENGTH_LONG).show() }
    private fun task(block: suspend () -> Unit) {
        lifecycleScope.launch { try { block() } catch (error: Exception) { message(error.message ?: "Unable to finish. Your local data is retained.") } }
    }

    private fun render() {
        if (!model.unlocked) { if (!showingLogin) login(); return }
        showingLogin = false
        root.removeAllViews()
        val header = column()
        if (BuildConfig.DEBUG) header.addView(text("DEV", 11f, primary))
        header.addView(text(when (screen) { 2 -> "Split bills"; 3 -> "Settings"; else -> "Expenses" }, 30f))
        header.addView(text(model.status.value, 12f, muted).apply {
            setOnClickListener { screen = 3; render() }
        })
        root.addView(header)
        content = column()
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        when (screen) { 2 -> finances("bill"); 3 -> settings(); else -> finances("expense") }
        val nav = BottomNavigationView(this)
        nav.menu.add(0, 1, 0, "Expenses").setIcon(android.R.drawable.ic_menu_agenda)
        nav.menu.add(0, 2, 1, "Bills").setIcon(android.R.drawable.ic_menu_myplaces)
        nav.menu.add(0, 3, 2, "Settings").setIcon(android.R.drawable.ic_menu_preferences)
        nav.selectedItemId = screen
        nav.setOnItemSelectedListener { item -> screen = item.itemId; render(); true }
        root.addView(nav)
    }

    private fun login() {
        showingLogin = true
        root.removeAllViews()
        val body = column()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        body.addView(text("Wallet", 34f))
        if (BuildConfig.DEBUG) body.addView(text("DEV", 11f, primary))
        val username = input(body, "Username").apply { setText(model.savedName) }
        val password = input(body, "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val biometricAvailable = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        val remember = MaterialCheckBox(this).apply { text = "Fingerprint unlock"; isChecked = biometricAvailable; isEnabled = biometricAvailable }
        body.addView(remember)
        lateinit var signIn: MaterialButton
        signIn = button("Sign in") {
            if (username.text.isNullOrBlank() || password.text.isNullOrEmpty()) { message("Enter your username and password"); return@button }
            signIn.isEnabled = false
            task {
                try { model.login(username.text.toString().trim(), password.text.toString(), remember.isChecked); password.text?.clear(); render() }
                finally { signIn.isEnabled = true }
            }
        }
        body.addView(signIn)
        if (biometricAvailable && model.wrappedRefresh != null) body.addView(button("Unlock", false) { unlock() })
        if (BuildConfig.ALLOW_REGISTER) body.addView(button("Create account", false) {
            if (username.text.toString().trim().length !in 3..128) { username.error = "3–128 characters"; return@button }
            if (password.text.toString().length !in 12..1024) { password.error = "12–1024 characters"; return@button }
            task {
                model.register(username.text.toString().trim(), password.text.toString())
                message("Account created")
            }
        })
        body.addView(button("Local wallet", false) {
            MaterialAlertDialogBuilder(this).setTitle("Local wallet")
                .setMessage("Only on this phone. No account or sync.")
                .setNegativeButton("Cancel", null).setPositiveButton("Open") { _, _ -> model.localOnly(); render() }.show()
        })
    }

    private fun unlock() {
        val id = model.savedAccount ?: return
        val wrapped = model.wrappedRefresh ?: return
        try {
            val cipher = DeviceVault(this, id).unlockCipher()
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    task {
                        val raw = String(requireNotNull(result.cryptoObject?.cipher).doFinal(android.util.Base64.decode(wrapped, android.util.Base64.NO_WRAP)))
                        model.unlock(id, raw)
                        render()
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { message(errString.toString()) }
            })
            val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock your wallet")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use password").build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) { message("Fingerprint unavailable. Sign in again.") }
    }

    private fun money(minor: Long, currency: String = "EUR"): String = NumberFormat.getCurrencyInstance().apply {
        this.currency = java.util.Currency.getInstance(currency)
    }.format(BigDecimal.valueOf(minor, 2))

    private fun finances(kind: String) {
        val rows = model.store!!.rows().filter { it.getString("kind") == kind }
        val totals = rows.groupBy { it.getJSONObject("payload").optString("currency", "EUR") }
            .map { (currency, values) -> money(values.sumOf { it.getJSONObject("payload").optLong("amount_minor") }, currency) }
        val summary = MaterialCardView(this).apply { radius = dp(24).toFloat(); strokeWidth = 0 }
        val inner = column()
        inner.addView(text("TOTAL", 11f, muted))
        inner.addView(text(totals.joinToString(" + ").ifEmpty { money(0) }, 32f, primary))
        summary.addView(inner); content.addView(summary)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button(if (kind == "bill") "Split bill" else "Expense") { editor(kind) }.apply {
            setIconResource(android.R.drawable.ic_input_add)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (kind == "expense") actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Categories"; isAllCaps = false; setOnClickListener { categories() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        content.addView(actions)
        if (rows.isEmpty()) content.addView(text(if (kind == "bill") "No bills yet" else "No expenses yet", 16f, muted))
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@WalletActivity) }
        val adapter = RecordAdapter(rows)
        list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onChildDraw(canvas: Canvas, recyclerView: RecyclerView, holder: RecyclerView.ViewHolder, dx: Float, dy: Float, state: Int, active: Boolean) {
                if (dx < 0) {
                    val view = holder.itemView
                    canvas.drawRoundRect(view.right + dx, view.top.toFloat(), view.right.toFloat(), view.bottom.toFloat(), dp(12).toFloat(), dp(12).toFloat(), Paint().apply { color = Color.rgb(174, 48, 48) })
                    val icon = ContextCompat.getDrawable(this@WalletActivity, android.R.drawable.ic_menu_delete)!!
                    val edge = dp(24); val right = view.right - dp(20); val top = view.top + (view.height - edge) / 2
                    icon.setTint(Color.WHITE); icon.setBounds(right - edge, top, right, top + edge); icon.draw(canvas)
                }
                super.onChildDraw(canvas, recyclerView, holder, dx, dy, state, active)
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index in rows.indices) stageDelete(rows[index])
            }
        }).attachToRecyclerView(list)
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private inner class RecordAdapter(private val rows: List<JSONObject>) : RecyclerView.Adapter<RecordAdapter.Holder>() {
        inner class Holder(val box: LinearLayout) : RecyclerView.ViewHolder(box)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder = Holder(column().apply {
            setPadding(0, dp(12), 0, dp(12)); layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]; val payload = row.getJSONObject("payload")
            holder.box.removeAllViews()
            val line = LinearLayout(this@WalletActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(text(payload.optString("description"), 17f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(text(money(payload.optLong("amount_minor"), payload.optString("currency", "EUR")), 19f, primary).apply { setPadding(dp(12), 0, 0, 0) })
            holder.box.addView(line)
            val detail = if (row.getString("kind") == "bill") "${payload.optJSONArray("participants")?.length() ?: 0} people · paid by ${payload.optString("paid_by")}" else categoryName(payload.optString("category_id"))
            holder.box.addView(text("${payload.optString("date")}  ·  $detail${if (row.optBoolean("has_conflict")) "  ·  Review" else ""}", 12f, muted))
            holder.box.setOnClickListener {
                when { row.optBoolean("has_conflict") -> conflict(row); row.getString("kind") == "bill" -> billDetails(row); else -> editor(row.getString("kind"), row) }
            }
        }
    }

    private fun categoryRows() = model.store!!.rows().filter { it.getString("kind") == "category" }
    private fun categoryName(id: String) = categoryRows().firstOrNull { it.getString("id") == id }?.getJSONObject("payload")?.optString("name") ?: "Uncategorized"
    private fun categories() {
        val rows = categoryRows()
        val options = rows.map { it.getJSONObject("payload").getString("name") + if (it.optBoolean("has_conflict")) " ⚠" else "" } + "+ Category"
        MaterialAlertDialogBuilder(this).setTitle("Categories").setItems(options.toTypedArray()) { _, index ->
            if (index == rows.size) categoryEditor(null)
            else if (rows[index].optBoolean("has_conflict")) conflict(rows[index])
            else categoryEditor(rows[index])
        }.setNegativeButton("Done", null).show()
    }

    private fun categoryEditor(row: JSONObject?) {
        val body = column(); val name = input(body, "Category name").apply { setText(row?.getJSONObject("payload")?.optString("name")) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (row == null) "New category" else "Edit category").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null)
            .apply { if (row != null) setNeutralButton("Delete") { _, _ ->
                val used = model.store!!.rows().any { it.getString("kind") == "expense" && it.getJSONObject("payload").optString("category_id") == row.getString("id") }
                if (used) message("Move or delete this category's expenses first") else stageDelete(row)
            } }.create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            if (name.text.isNullOrBlank() || name.text!!.length > 80) name.error = "Enter 1–80 characters"
            else { model.store!!.save("category", JSONObject().put("name", name.text.toString().trim()), row); model.changed(); render(); dialog.dismiss() }
        } }; dialog.show()
    }

    private fun editor(kind: String, row: JSONObject? = null) {
        val categories = categoryRows()
        if (kind == "expense" && categories.isEmpty()) { categoryEditor(null); return }
        val old = row?.getJSONObject("payload")
        val body = column()
        val description = input(body, if (kind == "bill") "Restaurant" else "Description").apply { setText(old?.optString("description")) }
        val amount = input(body, "Amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            old?.let { setText(BigDecimal.valueOf(it.getLong("amount_minor"), 2).toPlainString()) }
        }
        val dateValue = old?.optString("date") ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val date = button(dateValue, false) { }.apply { setIconResource(android.R.drawable.ic_menu_today) }
        date.setOnClickListener {
            val parts = date.text.toString().split('-').map(String::toInt)
            android.app.DatePickerDialog(this, { _, year, month, day -> date.text = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day) }, parts[0], parts[1] - 1, parts[2]).show()
        }
        val currency = Spinner(this).apply { adapter = ArrayAdapter(this@WalletActivity, android.R.layout.simple_spinner_dropdown_item, listOf("EUR", "USD", "GBP")); setSelection(listOf("EUR", "USD", "GBP").indexOf(old?.optString("currency") ?: "EUR").coerceAtLeast(0)) }
        val metadata = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        metadata.addView(date, LinearLayout.LayoutParams(0, -2, 1f)); metadata.addView(currency, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(metadata)
        val category = Spinner(this).apply { adapter = ArrayAdapter(this@WalletActivity, android.R.layout.simple_spinner_dropdown_item, categories.map { it.getJSONObject("payload").getString("name") }) }
        if (kind == "expense") { body.addView(category); category.setSelection(categories.indexOfFirst { it.getString("id") == old?.optString("category_id") }.coerceAtLeast(0)) }
        val participants = if (kind == "bill") input(body, "People").apply {
            (parent.parent as? TextInputLayout)?.placeholderText = "Alex, Sam, …"
            setText(old?.optJSONArray("participants")?.let { array -> (0 until array.length()).joinToString(", ") { array.getString(it) } })
        } else null
        val paidBy = if (kind == "bill") Spinner(this) else null
        if (paidBy != null) {
            body.addView(text("Paid by", 12f, muted)); body.addView(paidBy)
            fun updatePayers() {
                val selected = paidBy.selectedItem?.toString() ?: old?.optString("paid_by")
                val people = participants!!.text.toString().split(',').map(String::trim).filter(String::isNotBlank)
                paidBy.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, people)
                paidBy.setSelection(people.indexOf(selected).coerceAtLeast(0))
            }
            participants!!.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePayers() }
                override fun afterTextChanged(s: Editable?) {}
            })
            updatePayers()
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (kind == "bill") "Split bill" else "Expense")
            .setView(ScrollView(this).apply { addView(body) }).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            try {
                val minor = BigDecimal(amount.text.toString().replace(',', '.')).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
                require(minor in 1..99999999999) { "Enter a positive amount with at most two decimals" }
                val note = description.text.toString().trim(); require(note.isNotEmpty() && note.length <= 255) { "Enter a description up to 255 characters" }
                val day = date.text.toString(); val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                require(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && parser.format(requireNotNull(parser.parse(day))) == day) { "Enter a valid YYYY-MM-DD date" }
                val payload = JSONObject().put("description", note).put("amount_minor", minor).put("currency", currency.selectedItem.toString()).put("date", day)
                if (kind == "expense") payload.put("category_id", categories[category.selectedItemPosition].getString("id"))
                else {
                    val people = participants!!.text.toString().split(',').map { it.trim() }
                    require(people.size in 2..100 && people.all { it.isNotBlank() && it.length <= 80 } && people.distinct().size == people.size) { "Enter 2–100 unique people" }
                    val payer = paidBy!!.selectedItem?.toString().orEmpty(); require(payer in people) { "Choose who paid" }
                    val shares = JSONArray(); people.forEachIndexed { index, person -> shares.put(JSONObject().put("name", person).put("amount_minor", minor / people.size + if (index < minor % people.size) 1 else 0)) }
                    payload.put("participants", JSONArray(people)).put("paid_by", payer).put("shares", shares)
                }
                model.store!!.save(kind, payload, row); model.changed(); render(); dialog.dismiss()
            } catch (error: Exception) { message(error.message ?: "Check the form") }
        } }; dialog.show()
    }

    private fun stageDelete(row: JSONObject) {
        if (row.optBoolean("has_conflict")) { conflict(row); render(); return }
        val store = model.store!!
        store.save(row.getString("kind"), row.getJSONObject("payload"), row, deleted = true, delay = 6500)
        render()
        Snackbar.make(root, "Deleted", 6000).setAction("Undo") {
            val latest = store.rows(true).first { it.getString("id") == row.getString("id") }
            store.save(row.getString("kind"), row.getJSONObject("payload"), latest)
            model.changed(); render()
        }.show()
        model.syncSoon(6700)
    }

    private fun billDetails(row: JSONObject) {
        val payload = row.getJSONObject("payload")
        val body = column()
        body.addView(text(money(payload.getLong("amount_minor"), payload.optString("currency", "EUR")), 30f, primary))
        body.addView(text(payload.optString("date"), 12f, muted))
        val shares = payload.optJSONArray("shares") ?: JSONArray()
        for (i in 0 until shares.length()) {
            val share = shares.getJSONObject(i)
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(text(share.getString("name") + if (share.getString("name") == payload.optString("paid_by")) " · paid" else ""), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(text(money(share.getLong("amount_minor"), payload.optString("currency", "EUR")), 18f, primary))
            body.addView(line)
        }
        MaterialAlertDialogBuilder(this).setTitle(payload.getString("description")).setView(body)
            .setNegativeButton("Done", null).setPositiveButton("Edit") { _, _ -> editor("bill", row) }.show()
    }

    private fun conflict(row: JSONObject) {
        val remote = model.store!!.remoteConflict(row.getString("id"))
        val body = column()
        fun version(title: String, record: JSONObject?) {
            val card = MaterialCardView(this).apply { radius = dp(16).toFloat() }
            val detail = column(); detail.addView(text(title, 12f, muted))
            if (record == null || record.optBoolean("deleted")) detail.addView(text("Deleted", 20f))
            else {
                val payload = record.getJSONObject("payload")
                detail.addView(text(payload.optString("description", payload.optString("name")), 18f))
                if (payload.has("amount_minor")) detail.addView(text(money(payload.getLong("amount_minor"), payload.optString("currency", "EUR")), 24f, primary))
                if (payload.has("date")) detail.addView(text(payload.getString("date"), 12f, muted))
                if (payload.has("category_id")) detail.addView(text(categoryName(payload.getString("category_id")), 12f, muted))
                payload.optJSONArray("participants")?.let { people ->
                    detail.addView(text((0 until people.length()).joinToString(", ") { people.getString(it) }, 14f))
                    detail.addView(text("Paid by ${payload.optString("paid_by")}", 12f, muted))
                }
            }
            card.addView(detail); body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        version("THIS DEVICE", row); version("ONLINE", remote)
        MaterialAlertDialogBuilder(this).setTitle("Keep which version?").setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Later", null)
            .setNeutralButton("Use online") { _, _ -> model.store!!.resolve(row, false); model.changed(); render() }
            .setPositiveButton("Keep local") { _, _ -> model.store!!.resolve(row, true); model.changed(); render() }.show()
    }

    private fun settings() {
        val scroll = ScrollView(this); val body = column(); scroll.addView(body); content.addView(scroll)
        body.addView(text(if (model.account == "local") "Local wallet" else model.savedName, 22f))
        if (model.account != "local") {
            body.addView(text("${model.store!!.pendingCount()} pending", 12f, muted))
            body.addView(button("Sync") { model.syncSoon() }.apply { setIconResource(android.R.drawable.ic_popup_sync) })
        }
        val conflicts = model.store!!.rows(true).filter { it.optBoolean("has_conflict") }
        conflicts.forEach { row -> body.addView(button("⚠ ${row.getJSONObject("payload").optString("description", row.getJSONObject("payload").optString("name", "Deleted record"))}") { conflict(row) }) }
        body.addView(button("Lock", false) { model.lock(); showingLogin = false; render() }.apply { setIconResource(android.R.drawable.ic_lock_lock) })
        body.addView(button("Log out", false) {
            MaterialAlertDialogBuilder(this).setTitle("Log out?")
                .setMessage(if (model.store!!.pendingCount() > 0) "${model.store!!.pendingCount()} changes stay on this phone." else "Remove this device's session?")
                .setNegativeButton("Cancel", null).setPositiveButton("Log out") { _, _ -> task {
                    try { model.logout() } finally { showingLogin = false; render() }
                } }.show()
        })
        body.addView(button("Reset wallet…", false) {
            MaterialAlertDialogBuilder(this).setTitle("Delete all expenses and bills?")
                .setMessage(if (model.account == "local") "Categories stay." else "Categories stay. Deletes sync online.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ ->
                    val records = model.store!!.rows().filter { it.getString("kind") != "category" }
                    if (model.store!!.rows(true).any { it.optBoolean("has_conflict") }) { message("Resolve conflicts before resetting"); return@setPositiveButton }
                    val store = model.store!!
                    records.forEach { store.save(it.getString("kind"), it.getJSONObject("payload"), it, deleted = true, delay = 6500) }
                    render()
                    Snackbar.make(root, "${records.size} deleted", 6000).setAction("Undo") {
                        records.forEach { old -> val latest = store.rows(true).first { it.getString("id") == old.getString("id") }; store.save(old.getString("kind"), old.getJSONObject("payload"), latest) }
                        model.changed(); render()
                    }.show()
                    model.syncSoon(6700)
                }.show()
        }.apply { setTextColor(com.google.android.material.color.MaterialColors.getColor(this@WalletActivity, androidx.appcompat.R.attr.colorError, Color.RED)); setIconResource(android.R.drawable.ic_menu_delete) })
        body.addView(button("About · ${BuildConfig.VERSION_NAME}", false) {
            MaterialAlertDialogBuilder(this).setTitle("Wallet")
                .setMessage("${BuildConfig.API_BASE_URL}\n\nEncrypted on this device. Sync resumes when unlocked.")
                .setPositiveButton("Done", null).show()
        })
    }
}
