package win.downops.wallettracker.v2

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Shared cost = bill total minus assigned extras. All arithmetic uses integer cents. */
object BillSplitter {
    const val MAX_AMOUNT = 99999999999L
    data class Extra(val description: String, val amountMinor: Long, val participant: Int)
    data class Share(val name: String, val sharedMinor: Long, val extrasMinor: Long) {
        val amountMinor get() = sharedMinor + extrasMinor
    }

    fun amount(text: String): Long {
        val value = try {
            BigDecimal(text.trim().replace(',', '.')).movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: Exception) {
            throw IllegalArgumentException("Enter an amount with at most two decimals")
        }
        require(value in 1..MAX_AMOUNT) { "Enter a positive amount with at most two decimals" }
        return value
    }

    fun split(total: Long, people: List<String>, extras: List<Extra> = emptyList()): List<Share> {
        require(total in 1..MAX_AMOUNT) { "Enter a positive bill total" }
        require(people.size in 2..100) { "Add at least two people (up to 100)" }
        require(people.all { it.isNotBlank() && it.length <= 80 }) { "Names must be 1–80 characters" }
        require(people.map { it.trim().lowercase(Locale.ROOT) }.distinct().size == people.size) {
            "Give each person a different name, or leave names blank"
        }
        require(extras.size <= 100) { "Use at most 100 extras" }
        val assigned = LongArray(people.size)
        var extrasTotal = 0L
        extras.forEach { extra ->
            require(extra.description.length <= 80) { "Extra names must be at most 80 characters" }
            require(extra.participant in people.indices) { "Choose who had each extra" }
            require(extra.amountMinor in 1..total) { "Each extra must be positive and no more than the bill total" }
            extrasTotal += extra.amountMinor
            require(extrasTotal <= total) { "Extras cannot exceed the bill total" }
            assigned[extra.participant] += extra.amountMinor
        }
        val shared = total - extrasTotal
        return people.mapIndexed { index, name ->
            Share(name.trim(), shared / people.size + if (index < shared % people.size) 1 else 0, assigned[index])
        }
    }
}
