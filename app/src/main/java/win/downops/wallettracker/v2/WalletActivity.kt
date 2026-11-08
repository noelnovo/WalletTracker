package win.downops.wallettracker.v2

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import win.downops.wallettracker.BuildConfig
import win.downops.wallettracker.R
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.Cipher

/** Material 3 shell. Data, transport, crypto and sync are deliberately outside the view. */
class WalletActivity : AppCompatActivity() {
    private val model: WalletModel by viewModels()
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private val design by lazy { WalletDesign(this) }
    private var screen = 1
    private var showingLogin = false
    private var networkRegistered = false
    private var visibleRows = emptyList<JSONObject>()
    private var categoryNames = emptyMap<String, String>()
    private val activeDialogs = mutableSetOf<androidx.appcompat.app.AlertDialog>()
    private var activeSnackbar: Snackbar? = null
    private var migrationCheckedForAccount: String? = null
    private var legacyDataAvailable = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { model.syncSoon() } }
    }
    private val ink get() = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY)
    private val muted get() = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val primary get() = com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(design.surface) }
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
        if (!isChangingConfigurations) {
            activeSnackbar?.dismiss(); activeSnackbar = null
            activeDialogs.toList().forEach { it.dismiss() }
            (supportFragmentManager.findFragmentByTag("wallet-date") as? androidx.fragment.app.DialogFragment)?.dismissAllowingStateLoss()
            if (model.unlocked) {
                showingLogin = false; visibleRows = emptyList(); categoryNames = emptyMap()
                root.removeAllViews(); model.lock()
            }
        }
        super.onStop()
    }
    override fun onDestroy() {
        if (networkRegistered) (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
    private fun text(value: String, size: Float = 16f, color: Int = ink) = design.text(value, size, color)
    private fun button(label: String, prominent: Boolean = true, action: () -> Unit) = MaterialButton(this, null,
        if (prominent) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label; isAllCaps = false; minHeight = dp(52); setOnClickListener { action() }
    }
    private fun textButton(label: String, action: () -> Unit) = MaterialButton(this, null,
        androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = label; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() }
    }
    private fun watch(field: android.widget.EditText, action: () -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { action() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun input(parent: LinearLayout, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): TextInputEditText {
        val box = TextInputLayout(this).apply { this.hint = hint; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val field = TextInputEditText(box.context).apply {
            inputType = type; setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_NEXT
            if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) {
                isSaveEnabled = false; box.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
        }
        box.addView(field, LinearLayout.LayoutParams(-1, -2))
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return field
    }
    private fun dropdown(parent: LinearLayout, hint: String, choices: List<String>, selected: String?): MaterialAutoCompleteTextView {
        val box = TextInputLayout(this).apply {
            this.hint = hint; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        val field = MaterialAutoCompleteTextView(box.context).apply {
            inputType = InputType.TYPE_NULL; minHeight = dp(56); maxLines = 1
            setPaddingRelative(dp(16), paddingTop, dp(16), paddingBottom)
            setAdapter(ArrayAdapter(this@WalletActivity, android.R.layout.simple_list_item_1, choices))
            setText(selected ?: choices.firstOrNull().orEmpty(), false)
        }
        box.addView(field, LinearLayout.LayoutParams(-1, -2))
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return field
    }
    private fun displaySnackbar(snackbar: Snackbar) {
        activeSnackbar?.dismiss(); activeSnackbar = snackbar; snackbar.show()
    }
    private fun message(value: String) { displaySnackbar(Snackbar.make(root, value, Snackbar.LENGTH_LONG)) }
    private fun showDialog(dialog: androidx.appcompat.app.AlertDialog) {
        activeDialogs.add(dialog)
        dialog.setOnDismissListener { activeDialogs.remove(dialog) }
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }
    /** A scrollable form with its action outside the scroll area and above the IME. */
    private fun formDialog(title: String, body: LinearLayout, saveLabel: String = "Save",
                           cancelLabel: String = "Cancel", onCancel: (() -> Unit)? = null,
                           save: () -> Unit): androidx.appcompat.app.AlertDialog {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(design.surface) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(12), dp(12), dp(8)) }
        header.addView(design.text(title, 26f, ink, true).apply { tag = "wallet-form-title" }, LinearLayout.LayoutParams(0, -2, 1f))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        val dialog = MaterialAlertDialogBuilder(this).setView(form).create()
        header.addView(textButton("") { dialog.dismiss() }.apply {
            setIconResource(R.drawable.wallet_ic_close); iconSize = dp(22); iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; contentDescription = "Close form"
            minWidth = dp(48); minimumWidth = dp(48)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        form.addView(header)
        form.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val divider = View(this).apply { setBackgroundColor(design.color(com.google.android.material.R.attr.colorOutlineVariant)) }
        form.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
        val actions = column().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(design.cardSurface) }
        actions.addView(textButton(cancelLabel) { if (onCancel != null) onCancel() else dialog.dismiss() }.apply {
            tag = "wallet-form-secondary"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(saveLabel) { save() }.apply { tag = "wallet-form-primary" }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        form.addView(actions)
        dialog.setOnShowListener {
            dialog.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setBackgroundDrawable(ColorDrawable(design.surface))
                window.setLayout(minOf(resources.displayMetrics.widthPixels, dp(600)), ViewGroup.LayoutParams.MATCH_PARENT)
                // The custom panel is normally wrap-content. Fill the dialog so
                // the weighted scroll region leaves an always-visible action footer.
                form.layoutParams = form.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                (form.parent as? View)?.let { panel ->
                    panel.layoutParams = panel.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                }
            }
            ViewCompat.setOnApplyWindowInsetsListener(form) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(form)
        }
        return dialog
    }
    private fun task(block: suspend () -> Unit) {
        lifecycleScope.launch { try { block() } catch (error: Exception) { message(error.message ?: "Unable to finish. Your local data is retained.") } }
    }

    private fun render() {
        if (!model.unlocked) {
            migrationCheckedForAccount = null
            legacyDataAvailable = false
            activeSnackbar?.dismiss(); activeSnackbar = null
            visibleRows = emptyList(); categoryNames = emptyMap()
            if (!showingLogin) login()
            return
        }
        showingLogin = false
        visibleRows = model.store!!.rows()
        categoryNames = visibleRows.filter { it.getString("kind") == "category" }
            .associate { it.getString("id") to it.getJSONObject("payload").getString("name") }
        root.removeAllViews()
        val header = column()
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(design.text("WalletTracker", 13f, primary, true))
        if (BuildConfig.DEBUG) brand.addView(design.pill("DEV", design.color(com.google.android.material.R.attr.colorSurfaceContainerHigh), muted),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        header.addView(brand)
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(design.text(when (screen) { 2 -> "Split bills"; 3 -> "Settings"; else -> "Expenses" }, 30f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        val status = model.status.value
        val statusLabel = when {
            model.account == "local" -> "On this phone"
            status == "Synced" -> "Up to date"
            status.startsWith("Syncing") -> "Syncing"
            status == "Offline" -> "Offline"
            status == "Pending" -> "Pending"
            else -> "Needs attention"
        }
        if (resources.configuration.fontScale < 1.3f) titleRow.addView(textButton(statusLabel) { screen = 3; render() }.apply {
            textSize = 12f; contentDescription = "Wallet status: $statusLabel. Open settings"
            setPadding(dp(8), 0, dp(8), 0)
        })
        header.addView(titleRow)
        val readableWidth = minOf(resources.displayMetrics.widthPixels, dp(640))
        root.addView(header, LinearLayout.LayoutParams(readableWidth, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        contentFrame = FrameLayout(this)
        content = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        contentFrame.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(contentFrame, LinearLayout.LayoutParams(readableWidth, 0, 1f).apply { gravity = Gravity.CENTER_HORIZONTAL })
        when (screen) { 2 -> finances("bill"); 3 -> settings(); else -> finances("expense") }
        val nav = BottomNavigationView(this)
        nav.menu.add(0, 1, 0, "Expenses").setIcon(R.drawable.wallet_ic_expense)
        nav.menu.add(0, 2, 1, "Bills").setIcon(R.drawable.wallet_ic_people)
        nav.menu.add(0, 3, 2, "Settings").setIcon(R.drawable.wallet_ic_settings)
        nav.setBackgroundColor(design.cardSurface)
        val navigationTint = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(primary, muted))
        nav.itemIconTintList = navigationTint; nav.itemTextColor = navigationTint
        nav.itemActiveIndicatorColor = ColorStateList.valueOf(design.container)
        nav.selectedItemId = screen
        nav.setOnItemSelectedListener { item -> screen = item.itemId; render(); true }
        root.addView(nav)
        offerLegacyMigration()
    }

    private fun login(createAccount: Boolean = false, usePassword: Boolean = false) {
        showingLogin = true
        root.removeAllViews()
        val page = column().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(24)) }
        val body = design.column(20)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(480)), -2, Gravity.CENTER_HORIZONTAL))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(24)) }
        brand.addView(design.badge(R.drawable.wallet_ic_wallet, 60))
        val branding = design.column().apply {
            addView(design.text("WalletTracker", 23f, ink, true))
            addView(text(if (BuildConfig.DEBUG) "Development wallet" else "Your personal wallet", 13f, muted))
        }
        brand.addView(branding, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        page.addView(brand)
        page.addView(design.card(body), LinearLayout.LayoutParams(-1, -2))
        val legacyCredentials = model.legacyCredentials()
        val biometricAvailable = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        if (!createAccount && !usePassword && biometricAvailable && (model.wrappedRefresh != null || legacyCredentials != null)) {
            body.addView(design.text("Welcome back", 28f, ink, true))
            body.addView(text(model.savedName.ifEmpty { legacyCredentials?.username.orEmpty() }, 16f, muted))
            body.addView(text("Unlock to view your wallet.", 14f, muted).apply { setPadding(0, dp(8), 0, dp(20)) })
            body.addView(button("Unlock wallet") {
                if (model.wrappedRefresh != null) unlock() else legacyCredentials?.let(::unlockLegacy)
            }.apply { setIconResource(R.drawable.ic_fingerprint) }, LinearLayout.LayoutParams(-1, -2))
            page.addView(textButton("Use password") { login(usePassword = true) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return
        }
        body.addView(design.text(if (createAccount) "Create account" else "Sign in", 28f, ink, true))
        body.addView(text(if (createAccount) "Choose your account details." else "Access your synced wallet.", 14f, muted).apply {
            setPadding(0, dp(4), 0, dp(20))
        })
        val username = input(body, "Username").apply {
            if (!createAccount) setText(model.savedName.ifEmpty { legacyCredentials?.username.orEmpty() })
        }
        val password = input(body, "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        if (createAccount) (password.parent.parent as TextInputLayout).helperText = "At least 12 characters"
        val remember = MaterialCheckBox(this).apply {
            text = "Use fingerprint next time"; isChecked = biometricAvailable
            visibility = if (biometricAvailable) View.VISIBLE else View.GONE
        }
        body.addView(remember)
        lateinit var signIn: MaterialButton
        signIn = button(if (createAccount) "Create account" else "Sign in") {
            if (username.text.isNullOrBlank() || password.text.isNullOrEmpty()) { message("Enter your username and password"); return@button }
            if (createAccount && username.text.toString().trim().length !in 3..128) { username.error = "3–128 characters"; return@button }
            if (createAccount && password.text.toString().length !in 12..1024) { password.error = "12–1024 characters"; return@button }
            signIn.isEnabled = false
            task {
                try {
                    if (createAccount) model.register(username.text.toString().trim(), password.text.toString())
                    model.login(username.text.toString().trim(), password.text.toString(), remember.isChecked)
                    password.text?.clear(); render()
                }
                finally { signIn.isEnabled = true }
            }
        }
        body.addView(signIn, LinearLayout.LayoutParams(-1, -2))
        password.imeOptions = EditorInfo.IME_ACTION_DONE
        password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { if (signIn.isEnabled) signIn.performClick(); true } else false
        }
        // Registration is a separate form; the backend still authorizes it.
        page.addView(textButton(if (createAccount) "Already have an account? Sign in" else "Create an account") {
            password.text?.clear(); login(createAccount = !createAccount, usePassword = true)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (!createAccount) page.addView(textButton("Continue without an account") {
            MaterialAlertDialogBuilder(this).setTitle("Local wallet")
                .setMessage("Only on this phone. No account or sync.")
                .setNegativeButton("Cancel", null).setPositiveButton("Open") { _, _ -> task { model.localOnly(); render() } }.create().let(::showDialog)
        }.apply { setTextColor(muted) }, LinearLayout.LayoutParams(-1, -2))
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
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) login(usePassword = true) else message(errString.toString())
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock your wallet")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use password").build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) { login(usePassword = true); message("Fingerprint unavailable. Sign in again.") }
    }

    private fun unlockLegacy(credentials: LegacyUpgrade.Credentials) {
        try {
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("USER_KEY", null) as SecretKey
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding").apply {
                init(Cipher.DECRYPT_MODE, key, IvParameterSpec(android.util.Base64.decode(credentials.iv, android.util.Base64.NO_WRAP)))
            }
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    task {
                        val bytes = requireNotNull(result.cryptoObject?.cipher).doFinal(android.util.Base64.decode(credentials.ciphertext, android.util.Base64.NO_WRAP))
                        try {
                            val parts = bytes.toString(Charsets.UTF_8).split(';', limit = 2)
                            check(parts.size == 2 && (credentials.username.isEmpty() || parts[0] == credentials.username)) { "Sign in again" }
                            model.login(parts[0], parts[1], true)
                            render()
                        } finally { bytes.fill(0) }
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) login(usePassword = true) else message(errString.toString())
                }
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock your wallet")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use password").build(), BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) { login(usePassword = true); message("Fingerprint unavailable. Sign in again.") }
    }

    private fun offerLegacyMigration() {
        val account = model.account ?: return
        if (migrationCheckedForAccount == account) return
        migrationCheckedForAccount = account
        task {
            val snapshot = model.legacySnapshot()
            if (!model.unlocked || model.account != account) return@task
            val available = snapshot != null
            if (legacyDataAvailable != available) { legacyDataAvailable = available; render() }
            if (snapshot == null) return@task
            val target = if (account == "local") "Local wallet" else model.savedName
            val details = "${snapshot.expenses} expenses · ${snapshot.categories} categories" +
                (if (snapshot.reviewCount > 0) "\n${snapshot.reviewCount} need review" else "") +
                (if (snapshot.retainedOtherRows > 0) "\n${snapshot.retainedOtherRows} other records archived" else "")
            MaterialAlertDialogBuilder(this@WalletActivity).setTitle("Move local data to $target?")
                .setMessage(details).setNegativeButton("Not now", null)
                .setPositiveButton("Move") { _, _ -> task { model.migrateLegacy(snapshot); render() } }
                .create().let(::showDialog)
        }
    }

    private fun money(minor: Long, currency: String = "EUR"): String = NumberFormat.getCurrencyInstance().apply {
        this.currency = java.util.Currency.getInstance(currency)
    }.format(BigDecimal.valueOf(minor, 2))

    private fun finances(kind: String) {
        val rows = visibleRows.filter { it.getString("kind") == kind }
        val totals = rows.groupBy { it.getJSONObject("payload").optString("currency", "EUR") }
            .map { (currency, values) -> money(values.sumOf { it.getJSONObject("payload").optLong("amount_minor") }, currency) }
        val overview = design.column()
        val summary = design.column(20)
        summary.addView(design.text(if (kind == "bill") "BILLS TOTAL" else "TOTAL SPENDING", 12f, design.onContainer, true))
        (totals.ifEmpty { listOf(money(0)) }).forEach { value -> summary.addView(design.text(value, 34f, design.onContainer, true)) }
        summary.addView(design.text("${rows.size} ${if (kind == "bill") "bills" else "expenses"} · All time", 13f, design.onContainer))
        overview.addView(design.featuredCard(summary))
        val section = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
        section.addView(design.text(if (kind == "bill") "Your splits" else "Recent expenses", 18f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (kind == "expense") section.addView(textButton("Categories") { categories() }.apply {
            textSize = 13f; setIconResource(R.drawable.wallet_ic_category); iconSize = dp(16)
        })
        overview.addView(section)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@WalletActivity)
            setPadding(0, 0, 0, dp(12)); clipToPadding = false
        }
        val adapter = RecordAdapter(rows, overview)
        list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) = if (viewHolder.itemViewType == 0) 0 else super.getSwipeDirs(recyclerView, viewHolder)
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
                val index = viewHolder.bindingAdapterPosition - 1
                if (index in rows.indices) stageDelete(rows[index])
            }
        }).attachToRecyclerView(list)
        if (rows.isEmpty()) {
            val empty = design.column().apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(24), dp(20), dp(24)) }
            empty.addView(design.badge(if (kind == "bill") R.drawable.wallet_ic_people else R.drawable.wallet_ic_expense, 60))
            empty.addView(design.text(if (kind == "bill") "No bills yet" else "No expenses yet", 21f, ink, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(4))
            })
            empty.addView(text(if (kind == "bill") "Add a bill and give everyone a fair share." else "Your spending will appear here.", 14f, muted).apply { gravity = Gravity.CENTER })
            overview.addView(empty)
        }
        // Reserve the action area instead of allowing the extended FAB to cover records.
        content.addView(list, LinearLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(80) })
        contentFrame.addView(ExtendedFloatingActionButton(this).apply {
            text = if (kind == "bill") "Split bill" else "Add expense"; isAllCaps = false
            setIconResource(R.drawable.wallet_ic_plus)
            backgroundTintList = ColorStateList.valueOf(primary)
            val onPrimary = design.color(com.google.android.material.R.attr.colorOnPrimary)
            setTextColor(onPrimary); iconTint = ColorStateList.valueOf(onPrimary)
            minHeight = dp(56); setOnClickListener { editor(kind) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(20); bottomMargin = dp(16) })
    }

    private fun displayDay(value: String): String {
        val localDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = localDate.format(Date())
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }.time
        if (value == today) return "Today"
        if (value == localDate.format(yesterday)) return "Yesterday"
        return runCatching {
            val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(requireNotNull(iso.parse(value)))
        }.getOrDefault(value)
    }

    private inner class RecordAdapter(private val rows: List<JSONObject>, private val overview: View) : RecyclerView.Adapter<RecordAdapter.Holder>() {
        inner class Holder(val box: LinearLayout) : RecyclerView.ViewHolder(box)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder = Holder(column().apply {
            setPadding(0, 0, 0, dp(10)); layoutParams = RecyclerView.LayoutParams(-1, -2)
        })
        override fun getItemCount() = rows.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.box.removeAllViews()
            if (position == 0) {
                (overview.parent as? ViewGroup)?.removeView(overview)
                holder.box.addView(overview); return
            }
            val index = position - 1
            val row = rows[index]; val payload = row.getJSONObject("payload")
            val day = payload.optString("date")
            if (index == 0 || rows[index - 1].getJSONObject("payload").optString("date") != day) {
                holder.box.addView(design.text(displayDay(day), 13f, muted, true).apply { setPadding(dp(4), dp(12), 0, dp(8)) })
            }
            val line = LinearLayout(this@WalletActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(design.badge(if (row.getString("kind") == "bill") R.drawable.wallet_ic_people else R.drawable.wallet_ic_expense))
            val detail = if (row.getString("kind") == "bill") "${payload.optJSONArray("participants")?.length() ?: 0} people · paid by ${payload.optString("paid_by")}" else categoryName(payload.optString("category_id"))
            val labels = design.column()
            labels.addView(design.text(payload.optString("description"), 16f, ink, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            labels.addView(text(detail, 12f, muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            val amount = money(payload.optLong("amount_minor"), payload.optString("currency", "EUR"))
            val largeText = resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 360
            if (largeText) labels.addView(design.text(amount, 20f, primary, true))
            line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            if (!largeText) line.addView(design.text(amount, 18f, ink, true).apply { setPadding(dp(8), 0, 0, 0) })
            val cardBody = design.column(16).apply { addView(line) }
            if (row.optBoolean("has_conflict") || row.optBoolean("needs_review")) cardBody.addView(design.pill("Needs review",
                design.color(com.google.android.material.R.attr.colorTertiaryContainer), design.color(com.google.android.material.R.attr.colorOnTertiaryContainer)))
            holder.box.addView(design.card(cardBody, radius = 20).apply { setOnClickListener {
                when { row.optBoolean("has_conflict") -> conflict(row); row.getString("kind") == "bill" -> billDetails(row); else -> editor(row.getString("kind"), row) }
            } })
        }
    }

    private fun categoryRows() = visibleRows.filter { it.getString("kind") == "category" }
    private fun categoryName(id: String) = categoryNames[id] ?: "Uncategorized"
    private fun categories() {
        val rows = categoryRows()
        val body = column()
        body.addView(text("Keep your expenses organized.", 14f, muted).apply { setPadding(0, 0, 0, dp(16)) })
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        if (rows.isEmpty()) {
            val empty = design.column(20).apply { gravity = Gravity.CENTER }
            empty.addView(design.badge(R.drawable.wallet_ic_category, 60))
            empty.addView(design.text("No categories yet", 22f, ink, true).apply { gravity = Gravity.CENTER })
            empty.addView(text("Add one to give your spending a place.", 14f, muted).apply { gravity = Gravity.CENTER })
            body.addView(design.card(empty))
        }
        rows.forEach { row ->
            val count = visibleRows.count { it.getString("kind") == "expense" && it.getJSONObject("payload").optString("category_id") == row.getString("id") }
            val subtitle = if (row.optBoolean("has_conflict") || row.optBoolean("needs_review")) "Needs review" else "$count ${if (count == 1) "expense" else "expenses"}"
            body.addView(design.group(design.actionRow(row.getJSONObject("payload").getString("name"), subtitle, R.drawable.wallet_ic_category) {
                dialog.dismiss()
                if (row.optBoolean("has_conflict")) conflict(row) else categoryEditor(row) { categories() }
            }))
        }
        dialog = formDialog("Categories", body, "Add category", "Done") { dialog.dismiss(); categoryEditor(null) { categories() } }
        showDialog(dialog)
    }

    private fun categoryEditor(row: JSONObject?, onSaved: (() -> Unit)? = null) {
        val body = column()
        body.addView(text("Use a short, recognizable name.", 14f, muted).apply { setPadding(0, 0, 0, dp(16)) })
        val fields = design.column(20)
        val name = input(fields, "Category name").apply { setText(row?.getJSONObject("payload")?.optString("name")) }
        body.addView(design.card(fields))
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        if (row != null) {
            body.addView(design.heading("Manage category"))
            body.addView(design.group(design.actionRow("Delete category", "Only available when the category has no expenses.", R.drawable.wallet_ic_delete, design.error) {
                val used = model.store!!.rows().any { it.getString("kind") == "expense" && it.getJSONObject("payload").optString("category_id") == row.getString("id") }
                if (used) name.error = "Move this category's expenses first"
                else { dialog.dismiss(); stageDelete(row) }
            }))
        }
        dialog = formDialog(if (row == null) "New category" else "Edit category", body, "Save category") {
            if (name.text.isNullOrBlank() || name.text!!.length > 80) name.error = "Enter 1–80 characters"
            else { model.store!!.save("category", JSONObject().put("name", name.text.toString().trim()), row); model.changed(); render(); dialog.dismiss(); onSaved?.invoke() }
        }
        showDialog(dialog)
    }

    private fun editor(kind: String, row: JSONObject? = null) {
        if (kind == "bill") { billEditor(row); return }
        val categories = categoryRows().filter { !it.optBoolean("needs_review") && !it.optBoolean("has_conflict") }
        if (kind == "expense" && categories.isEmpty()) { categoryEditor(null) { editor(kind, row) }; return }
        val old = row?.getJSONObject("payload")
        val body = column()
        val amountFields = design.column(20)
        val amount = input(amountFields, "Amount", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
            textSize = 28f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            old?.let { setText(BigDecimal.valueOf(it.getLong("amount_minor"), 2).toPlainString()) }
        }
        body.addView(design.card(amountFields))
        body.addView(design.heading("Details"))
        val details = design.column(20)
        val description = input(details, "Description").apply { setText(old?.optString("description")) }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
        val originalDay = old?.optString("date") ?: today
        var dateConfirmed = originalDay.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && runCatching { iso.format(requireNotNull(iso.parse(originalDay))) == originalDay }.getOrDefault(false)
        var selectedDay = if (dateConfirmed) originalDay else today
        val displayDate = DateFormat.getDateInstance(DateFormat.MEDIUM).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dateBox = TextInputLayout(this).apply {
            hint = "Date"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_CUSTOM; setEndIconDrawable(R.drawable.ic_calendar)
            endIconContentDescription = "Choose date"
        }
        if (!dateConfirmed) dateBox.error = "Choose date"
        val date = TextInputEditText(dateBox.context).apply {
            setText(displayDate.format(requireNotNull(iso.parse(selectedDay))))
            inputType = InputType.TYPE_NULL; isFocusable = false; isCursorVisible = false
            minHeight = dp(56); maxLines = 1
        }
        dateBox.addView(date, LinearLayout.LayoutParams(-1, -2))
        fun chooseDate() {
            MaterialDatePicker.Builder.datePicker().setTitleText("Date")
                .setSelection(requireNotNull(iso.parse(selectedDay)).time).build().apply {
                    addOnPositiveButtonClickListener { value ->
                        selectedDay = iso.format(Date(value)); date.setText(displayDate.format(Date(value)))
                        dateConfirmed = true; dateBox.error = null
                    }
                }.show(supportFragmentManager, "wallet-date")
        }
        date.setOnClickListener { chooseDate() }; dateBox.setEndIconOnClickListener { chooseDate() }
        val currencyBox = TextInputLayout(this).apply {
            hint = "Currency"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        val currency = MaterialAutoCompleteTextView(currencyBox.context).apply {
            inputType = InputType.TYPE_NULL; minHeight = dp(56); maxLines = 1
            setPaddingRelative(dp(16), paddingTop, dp(16), paddingBottom)
            setAdapter(ArrayAdapter(this@WalletActivity, android.R.layout.simple_list_item_1, listOf("EUR", "USD", "GBP")))
            setText(old?.optString("currency") ?: "EUR", false)
        }
        currencyBox.addView(currency, LinearLayout.LayoutParams(-1, -2))
        val metadata = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        metadata.addView(dateBox, LinearLayout.LayoutParams(0, -2, 1.4f))
        metadata.addView(currencyBox, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        details.addView(metadata, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        var selectedCategory = categories.firstOrNull { it.getString("id") == old?.optString("category_id") } ?: categories.firstOrNull()
        if (kind == "expense") dropdown(details, "Category", categories.map { it.getJSONObject("payload").getString("name") }, selectedCategory?.getJSONObject("payload")?.getString("name")).apply {
            setOnItemClickListener { _, _, index, _ -> selectedCategory = categories[index] }
        }
        body.addView(design.card(details))
        val amountBox = amount.parent.parent as TextInputLayout
        amountBox.suffixText = currency.text.toString()
        currency.setOnItemClickListener { _, _, _, _ -> amountBox.suffixText = currency.text.toString() }
        val formError = text("", 14f, com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError, Color.RED))
        body.addView(formError)
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        dialog = formDialog(if (row == null) "Add expense" else "Edit expense", body) {
            try {
                formError.text = ""; amount.error = null; description.error = null
                val minor = BillSplitter.amount(amount.text.toString())
                val note = description.text.toString().trim(); require(note.isNotEmpty() && note.length <= 255) { "Enter a description up to 255 characters" }
                require(dateConfirmed) { "Choose a date" }
                val day = selectedDay; val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                require(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && parser.format(requireNotNull(parser.parse(day))) == day) { "Enter a valid YYYY-MM-DD date" }
                val payload = JSONObject().put("description", note).put("amount_minor", minor).put("currency", currency.text.toString()).put("date", day)
                payload.put("category_id", requireNotNull(selectedCategory).getString("id"))
                model.store!!.save(kind, payload, row); model.changed(); render(); dialog.dismiss()
            } catch (error: Exception) {
                formError.text = error.message ?: "Check the form"
                if (runCatching { BillSplitter.amount(amount.text.toString()) }.isFailure) amount.error = formError.text
                if (description.text.toString().trim().isEmpty() || description.text.toString().trim().length > 255) description.error = "Enter a description up to 255 characters"
                formError.requestRectangleOnScreen(android.graphics.Rect(0, 0, formError.width, formError.height), true)
            }
        }
        showDialog(dialog)
    }

    private fun billEditor(row: JSONObject? = null) {
        BillEditor(this, design, row?.getJSONObject("payload"), ::money,
            { title, body, primary, secondary, cancel, save -> formDialog(title, body, primary, secondary, cancel, save) },
            ::showDialog,
            { payload -> model.store!!.save("bill", payload, row); model.changed(); render() },
        ).show()
    }

    private fun stageDelete(row: JSONObject) {
        if (row.optBoolean("has_conflict")) { conflict(row); render(); return }
        val store = model.store!!
        store.save(row.getString("kind"), row.getJSONObject("payload"), row, deleted = true, delay = 6500)
        render()
        Snackbar.make(root, "Deleted", 6000).setAction("Undo") {
            if (!model.unlocked || model.store !== store) return@setAction
            val latest = store.rows(true).first { it.getString("id") == row.getString("id") }
            store.save(row.getString("kind"), row.getJSONObject("payload"), latest)
            model.changed(); render()
        }.let(::displaySnackbar)
        model.syncSoon(6700)
    }

    private fun billDetails(row: JSONObject) {
        val payload = row.getJSONObject("payload")
        val body = column()
        val currency = payload.optString("currency", "EUR")
        val extras = payload.optJSONArray("extras") ?: JSONArray()
        val extrasTotal = (0 until extras.length()).sumOf { extras.getJSONObject(it).getLong("amount_minor") }
        val hero = design.column(20).apply {
            addView(design.text(money(payload.getLong("amount_minor"), currency), 34f, design.onContainer, true))
            addView(design.text(displayDay(payload.optString("date")), 13f, design.onContainer))
            addView(design.text("${money(payload.getLong("amount_minor") - extrasTotal, currency)} shared · ${money(extrasTotal, currency)} extras", 13f, design.onContainer))
        }
        body.addView(design.featuredCard(hero))
        if (extras.length() > 0) body.addView(design.heading("Individual extras"))
        for (index in 0 until extras.length()) {
            val extra = extras.getJSONObject(index)
            val entry = design.column(16)
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(design.text(extra.optString("description").ifEmpty { "Extra" }, 16f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(design.text(money(extra.getLong("amount_minor"), currency), 17f, ink, true))
            entry.addView(line); entry.addView(text(extra.getString("participant"), 13f, muted))
            body.addView(design.card(entry, radius = 20), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        body.addView(design.heading("Everyone's share", "${payload.optString("paid_by")} paid the whole bill."))
        val shares = payload.optJSONArray("shares") ?: JSONArray()
        for (i in 0 until shares.length()) {
            val share = shares.getJSONObject(i)
            val entry = design.column(16)
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(design.text(share.getString("name"), 17f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(design.text(money(share.getLong("amount_minor"), currency), 20f, primary, true))
            entry.addView(line)
            if (share.getString("name") == payload.optString("paid_by")) entry.addView(design.pill("Paid the bill"))
            else if (share.getLong("amount_minor") > 0) entry.addView(text("Owes ${payload.optString("paid_by")} ${money(share.getLong("amount_minor"), currency)}", 13f, muted))
            body.addView(design.card(entry, radius = 20), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        dialog = formDialog(payload.getString("description"), body, "Edit split", "Done") { dialog.dismiss(); billEditor(row) }
        showDialog(dialog)
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
                    payload.optJSONArray("extras")?.let { extras ->
                        for (index in 0 until extras.length()) {
                            val extra = extras.getJSONObject(index)
                            detail.addView(text("${extra.optString("description").ifEmpty { "Extra" }} · ${extra.optString("participant")} · ${money(extra.optLong("amount_minor"), payload.optString("currency", "EUR"))}", 12f, muted))
                        }
                    }
                }
            }
            card.addView(detail); body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        version("THIS DEVICE", row); version("ONLINE", remote)
        MaterialAlertDialogBuilder(this).setTitle("Keep which version?").setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Later", null)
            .setNeutralButton("Use online") { _, _ -> model.store!!.resolve(row, false); model.changed(); render() }
            .setPositiveButton("Keep local") { _, _ -> model.store!!.resolve(row, true); model.changed(); render() }.create().let(::showDialog)
    }

    private fun settings() {
        val scroll = ScrollView(this); val body = column().apply { setPadding(0, 0, 0, dp(16)) }; scroll.addView(body); content.addView(scroll)
        val identity = design.column(20).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        identity.addView(design.badge(R.drawable.wallet_ic_wallet, 52))
        identity.addView(design.column().apply {
            addView(design.text(if (model.account == "local") "Local wallet" else model.savedName, 21f, ink, true))
            addView(text(if (model.account == "local") "Only on this phone" else "Account wallet", 13f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        body.addView(design.card(identity, design.cardSurface))
        body.addView(design.heading("Wallet"))
        val categoryCount = categoryRows().size
        val walletRows = mutableListOf<View>(design.actionRow("Categories", "$categoryCount ${if (categoryCount == 1) "category" else "categories"} · organize your expenses", R.drawable.wallet_ic_category) { categories() })
        if (model.account != "local") {
            walletRows.add(design.actionRow("Sync now", "${model.store!!.pendingCount()} waiting · automatically syncs while open", R.drawable.wallet_ic_sync) { model.retrySync() })
        }
        body.addView(design.group(*walletRows.toTypedArray()))
        body.addView(text(if (model.account == "local") "This wallet stays on your phone. It doesn't sync." else model.status.value, 13f, muted).apply { setPadding(dp(16), 0, dp(16), dp(4)) })
        val conflicts = model.store!!.rows(true).filter { it.optBoolean("has_conflict") }
        val review = visibleRows.filter { it.optBoolean("needs_review") }
        if (conflicts.isNotEmpty() || review.isNotEmpty()) body.addView(design.heading("Needs your attention"))
        conflicts.forEach { row -> body.addView(design.group(design.actionRow(
            row.getJSONObject("payload").optString("description", row.getJSONObject("payload").optString("name", "Deleted record")),
            "Choose which version to keep", R.drawable.wallet_ic_sync) { conflict(row) })) }
        review.forEach { row ->
            body.addView(design.group(design.actionRow(row.getJSONObject("payload").optString("description", row.getJSONObject("payload").optString("name")),
                "Review imported details", R.drawable.wallet_ic_expense) {
                if (row.getString("kind") == "category") categoryEditor(row) else editor(row.getString("kind"), row)
            }))
        }
        body.addView(design.heading("Privacy"))
        val privacyRows = mutableListOf<View>(design.actionRow(if (model.account == "local") "Close wallet" else "Lock wallet",
            if (model.account == "local") "Hide this wallet from the screen" else "Locks automatically when you leave the app", R.drawable.wallet_ic_lock) {
            model.lock(); showingLogin = false; render()
        })
        if (model.account != "local") privacyRows.add(design.actionRow("Sign out", "Remove this device's saved session", R.drawable.wallet_ic_logout) { signOut() })
        body.addView(design.group(*privacyRows.toTypedArray()))
        body.addView(design.heading("Data"))
        val dataRows = mutableListOf<View>()
        if (legacyDataAvailable) dataRows.add(design.actionRow("Import previous wallet data", "Move records from the older app", R.drawable.wallet_ic_wallet) {
            migrationCheckedForAccount = null; offerLegacyMigration()
        })
        dataRows.add(design.actionRow("Delete expenses and bills", "Categories are kept. You can undo immediately.", R.drawable.wallet_ic_delete, design.error) { resetWallet() })
        body.addView(design.group(*dataRows.toTypedArray()))
    }

    private fun signOut() {
        MaterialAlertDialogBuilder(this).setTitle("Sign out?")
            .setMessage(if (model.store!!.pendingCount() > 0) "${model.store!!.pendingCount()} changes stay on this phone." else "Remove this device's session?")
            .setNegativeButton("Cancel", null).setPositiveButton("Sign out") { _, _ -> task {
                try { model.logout() } finally { showingLogin = false; render() }
            } }.create().let(::showDialog)
    }

    private fun resetWallet() {
            MaterialAlertDialogBuilder(this).setTitle("Delete all expenses and bills?")
                .setMessage(if (model.account == "local") "Categories stay." else "Categories stay. Deletes sync online.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ ->
                    val records = model.store!!.rows().filter { it.getString("kind") != "category" }
                    if (model.store!!.rows(true).any { it.optBoolean("has_conflict") }) { message("Resolve conflicts before resetting"); return@setPositiveButton }
                    val store = model.store!!
                    records.forEach { store.save(it.getString("kind"), it.getJSONObject("payload"), it, deleted = true, delay = 6500) }
                    render()
                    Snackbar.make(root, "${records.size} deleted", 6000).setAction("Undo") {
                        if (!model.unlocked || model.store !== store) return@setAction
                        records.forEach { old -> val latest = store.rows(true).first { it.getString("id") == old.getString("id") }; store.save(old.getString("kind"), old.getJSONObject("payload"), latest) }
                        model.changed(); render()
                    }.let(::displaySnackbar)
                    model.syncSoon(6700)
                }.create().let(::showDialog)
    }
}
