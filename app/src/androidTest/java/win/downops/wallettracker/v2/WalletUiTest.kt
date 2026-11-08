package win.downops.wallettracker.v2

import android.view.ViewGroup
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.startsWith
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.util.UUID
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import win.downops.wallettracker.R

/** Run on an isolated emulator: these scenarios use only its synthetic local wallet. */
@RunWith(AndroidJUnit4::class)
class WalletUiTest {
    @Before fun requireEmulatorForWalletMutationsAndSyntheticPreviews() {
        assumeTrue("Wallet mutation/preview scenarios must never run on a physical phone",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    }
    private fun action(label: String) = onView(allOf(withText(label), isAssignableFrom(MaterialButton::class.java)))

    private fun localWallet(scenario: ActivityScenario<WalletActivity>) {
        action("Continue without an account").perform(click())
        onView(withText("Open")).perform(click())
        waitUntil {
            var open = false
            scenario.onActivity { open = ViewModelProvider(it)[WalletModel::class.java].unlocked }
            open
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) fail("UI did not reach its expected state")
            Thread.sleep(50)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun registrationHasItsOwnPrimaryActionAndDoesNotRetainPassword() {
        ActivityScenario.launch(WalletActivity::class.java).use {
            action("Sign in").check(matches(isDisplayed()))
            onView(withHint("Password")).perform(replaceText("synthetic-password-only"), closeSoftKeyboard())
            action("Create an account").perform(click())
            action("Create account").check(matches(isDisplayed()))
            onView(withHint("Password")).check(matches(withText("")))
            action("Already have an account? Sign in").perform(click())
            action("Sign in").check(matches(isDisplayed()))
        }
    }

    @Test fun expenseSaveRemainsVisibleWithKeyboardAndCategoryMatchesTextPadding() {
        ActivityScenario.launch(WalletActivity::class.java).use { scenario ->
            localWallet(scenario)
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[WalletModel::class.java]
                model.store!!.save("category", JSONObject().put("name", "Test ${UUID.randomUUID()}"))
                model.changed()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            action("Add expense").perform(click())
            onView(withHint("Description")).perform(scrollTo(), replaceText("Synthetic lunch"))
            onView(withHint("Amount")).perform(scrollTo(), click(), typeText("15.00"))
            action("Save").check(matches(isCompletelyDisplayed()))
            action("Save").check { view, error ->
                if (error != null) throw error
                assertTrue("Keyboard must actually be visible for this regression test",
                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            }
            var descriptionTextStart = 0
            onView(withHint("Description")).check { view, error ->
                if (error != null) throw error
                val location = IntArray(2); view.getLocationOnScreen(location)
                descriptionTextStart = location[0] + view.paddingStart
            }
            onView(withHint("Category")).check { view, error ->
                if (error != null) throw error
                assertEquals((16 * view.resources.displayMetrics.density).toInt(), view.paddingStart)
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.width)
                val location = IntArray(2); view.getLocationOnScreen(location)
                assertEquals("Category value must align with the description value", descriptionTextStart, location[0] + view.paddingStart)
            }
            action("Save").perform(click())
        }
    }

    @Test fun blankPersonNamesAndUnnamedExtraProduceTenAndFiveEuroShares() {
        ActivityScenario.launch(WalletActivity::class.java).use { scenario ->
            localWallet(scenario)
            onView(withContentDescription("Bills")).perform(click())
            action("Split bill").perform(click())
            onView(withHint("Bill total")).perform(replaceText("15.00"), closeSoftKeyboard())
            action("Continue").perform(click())
            action("Add extra").perform(scrollTo(), click())
            onView(withHint("Extra price")).perform(scrollTo(), click(), typeText("5.00"))
            action("Add extra").check(matches(isCompletelyDisplayed())).perform(click())
            action("Continue").perform(click())
            action("Save split").check(matches(isCompletelyDisplayed())).perform(click())
            waitUntil {
                var saved = false
                scenario.onActivity { activity ->
                    saved = ViewModelProvider(activity)[WalletModel::class.java].store!!.rows().any {
                        it.getString("kind") == "bill" && it.getJSONObject("payload").optString("description") == "Bill"
                    }
                }
                saved
            }
            scenario.onActivity { activity ->
                val payload = ViewModelProvider(activity)[WalletModel::class.java].store!!.rows()
                    .first { it.getString("kind") == "bill" && it.getJSONObject("payload").optString("description") == "Bill" }
                    .getJSONObject("payload")
                assertEquals("Person 1", payload.getJSONArray("participants").getString(0))
                assertEquals(1000, payload.getJSONArray("shares").getJSONObject(0).getInt("amount_minor"))
                assertEquals(500, payload.getJSONArray("shares").getJSONObject(1).getInt("amount_minor"))
                assertEquals("", payload.getJSONArray("extras").getJSONObject(0).getString("description"))
            }
        }
    }

    @Test fun guidedSplitKeepsDraftAndAssignmentsWhenGoingBackAndRenaming() {
        ActivityScenario.launch(WalletActivity::class.java).use { scenario ->
            localWallet(scenario)
            onView(withContentDescription("Bills")).perform(click())
            action("Split bill").perform(click())
            onView(withHint("Bill total")).perform(replaceText("15"), closeSoftKeyboard())
            onView(withHint("Bill name (optional)")).perform(scrollTo(), replaceText("Rename proof"), closeSoftKeyboard())
            action("Continue").perform(click())
            onView(withHint("Person 1 (name optional)")).perform(replaceText("Alex"), closeSoftKeyboard())
            onView(withHint("Person 2 (name optional)")).perform(replaceText("Sam"), closeSoftKeyboard())
            action("Add extra").perform(scrollTo(), click())
            onView(withHint("Extra price")).perform(replaceText("5"), closeSoftKeyboard())
            action("Add extra").perform(click())
            action("Continue").perform(click())
            action("Back").perform(click())
            onView(withHint("Person 1 (name optional)")).perform(scrollTo(), replaceText("Riley"), closeSoftKeyboard())
            action("Continue").perform(click())
            onView(withHint("Paid by")).check(matches(withText("Riley")))
            action("Save split").perform(click())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val payload = ViewModelProvider(activity)[WalletModel::class.java].store!!.rows()
                    .first { it.getString("kind") == "bill" && it.getJSONObject("payload").optString("description") == "Rename proof" }.getJSONObject("payload")
                assertEquals("Riley", payload.getJSONArray("extras").getJSONObject(0).getString("participant"))
                assertEquals(1000, payload.getJSONArray("shares").getJSONObject(0).getInt("amount_minor"))
            }
        }
    }

    @Test fun settingsUseGroupedActionsAndCategoriesHaveTheirOwnAddAction() {
        ActivityScenario.launch(WalletActivity::class.java).use { scenario ->
            localWallet(scenario)
            onView(withContentDescription("Settings")).perform(click())
            onView(withText(startsWith("About"))).check(doesNotExist())
            onView(withContentDescription(startsWith("Categories,"))).perform(click())
            action("Add category").check(matches(isCompletelyDisplayed()))
            onView(withText("+ Category")).check(doesNotExist())
        }
    }

    @Test fun lightAndDarkDesignTokensMeetTextContrastTargets() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val config = Configuration(base.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode }
            val context = ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_WalletTracker_Modern)
            val design = WalletDesign(context)
            for ((foreground, background) in listOf(
                design.ink to design.surface, design.muted to design.cardSurface, design.onContainer to design.container,
                design.color(com.google.android.material.R.attr.colorOnPrimary) to design.primary,
                design.error to design.cardSurface,
            )) assertTrue("Readable text contrast in mode $mode", ColorUtils.calculateContrast(foreground, background) >= 4.5)
            for (resource in listOf(R.color.wallet_hero_start, R.color.wallet_hero_center, R.color.wallet_hero_end)) {
                assertTrue("Readable text across the brand gradient in mode $mode",
                    ColorUtils.calculateContrast(design.onContainer, androidx.core.content.ContextCompat.getColor(context, resource)) >= 4.5)
            }
        }
    }

    private fun syntheticPreview(view: android.view.View, name: String) {
        // Render controlled, synthetic app Views, not a phone/system screenshot.
        // FLAG_SECURE remains enabled; this never runs against real wallet data.
        val root = view.rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ui-redesign").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun syntheticDesignPreviews() {
        ActivityScenario.launch(WalletActivity::class.java).use { scenario ->
            action("Sign in").check { view, error -> if (error != null) throw error; syntheticPreview(view, "login") }
            localWallet(scenario)
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[WalletModel::class.java]
                val store = model.store!!
                store.rows().forEach { store.save(it.getString("kind"), it.getJSONObject("payload"), it, deleted = true) }
                val category = store.save("category", JSONObject().put("name", "Food"))
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                listOf("Groceries" to 3460, "Coffee & breakfast" to 790, "Dinner with friends" to 2200).forEach { (title, amount) ->
                    store.save("expense", JSONObject().put("description", title).put("amount_minor", amount).put("currency", "EUR").put("date", day).put("category_id", category.getString("id")))
                }
                model.changed()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            action("Add expense").check { view, error -> if (error != null) throw error; syntheticPreview(view, "expenses") }
            onView(withContentDescription("Settings")).perform(click())
            onView(withText("Privacy")).check { view, error -> if (error != null) throw error; syntheticPreview(view, "settings") }
            onView(withContentDescription("Bills")).perform(click())
            action("Split bill").perform(click())
            onView(withHint("Bill total")).perform(replaceText("15"), closeSoftKeyboard())
            action("Continue").perform(click())
            action("Add extra").perform(scrollTo(), click())
            onView(withHint("Extra price")).perform(replaceText("5"), closeSoftKeyboard())
            onView(withHint("Extra name (optional)")).perform(scrollTo(), replaceText("Wine"), closeSoftKeyboard())
            action("Add extra").perform(click())
            action("Continue").perform(click())
            action("Save split").check { view, error -> if (error != null) throw error; syntheticPreview(view, "split-review") }
        }
    }
}
