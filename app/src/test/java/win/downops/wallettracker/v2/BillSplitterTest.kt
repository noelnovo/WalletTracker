package win.downops.wallettracker.v2

import org.junit.Assert.*
import org.junit.Test

class BillSplitterTest {
    @Test fun wineIsAssignedAndUnspecifiedRemainderIsShared() {
        val shares = BillSplitter.split(1500, listOf("Alex", "Sam"), listOf(BillSplitter.Extra("Wine", 500, 0)))
        assertEquals(listOf(1000L, 500L), shares.map { it.amountMinor })
        assertEquals(listOf(500L, 500L), shares.map { it.sharedMinor })
    }

    @Test fun descriptionsAreOptionalAndSeveralExtrasCanBelongToOnePerson() {
        val shares = BillSplitter.split(3000, listOf("A", "B", "C"), listOf(
            BillSplitter.Extra("", 300, 1), BillSplitter.Extra("Dessert", 600, 1), BillSplitter.Extra("Drink", 300, 2)))
        assertEquals(listOf(600L, 1500L, 900L), shares.map { it.amountMinor })
        assertEquals(3000L, shares.sumOf { it.amountMinor })
    }

    @Test fun roundingPreservesEveryCentAndLegacyEqualSplits() {
        assertEquals(listOf(334L, 333L, 333L), BillSplitter.split(1000, listOf("A", "B", "C")).map { it.amountMinor })
        val shares = BillSplitter.split(1001, listOf("A", "B", "C"), listOf(BillSplitter.Extra("", 100, 2)))
        assertEquals(listOf(301L, 300L, 400L), shares.map { it.amountMinor })
        assertEquals(1001L, shares.sumOf { it.amountMinor })
    }

    @Test fun fullyAssignedTotalAllowsZeroSharedCost() {
        assertEquals(listOf(1500L, 0L), BillSplitter.split(1500, listOf("A", "B"),
            listOf(BillSplitter.Extra("Wine", 1500, 0))).map { it.amountMinor })
    }

    @Test fun invalidAssignmentsAndExcessExtrasAreRejected() {
        val people = listOf("A", "B")
        listOf(
            listOf(BillSplitter.Extra("", 0, 0)),
            listOf(BillSplitter.Extra("", -1, 0)),
            listOf(BillSplitter.Extra("", 1501, 0)),
            listOf(BillSplitter.Extra("", 1000, 0), BillSplitter.Extra("", 501, 1)),
            listOf(BillSplitter.Extra("", 100, 2)),
        ).forEach { extras -> assertThrows(IllegalArgumentException::class.java) { BillSplitter.split(1500, people, extras) } }
        assertThrows(IllegalArgumentException::class.java) { BillSplitter.split(1500, listOf("Alex", " alex ")) }
        assertThrows(IllegalArgumentException::class.java) { BillSplitter.split(1500, listOf("A")) }
        assertThrows(IllegalArgumentException::class.java) { BillSplitter.split(1500, people, List(101) { BillSplitter.Extra("", 1, 0) }) }
    }

    @Test fun maximumTotalIsExactAndLocaleDecimalCommaIsAccepted() {
        val shares = BillSplitter.split(BillSplitter.MAX_AMOUNT, listOf("A", "B"),
            listOf(BillSplitter.Extra("", BillSplitter.MAX_AMOUNT - 1, 0)))
        assertEquals(BillSplitter.MAX_AMOUNT, shares.sumOf { it.amountMinor })
        assertEquals(1500L, BillSplitter.amount("15,00"))
        assertEquals(500L, BillSplitter.amount(" 5.00 "))
        listOf("", "0", "-1", "1.001", "99999999999999999999", "NaN").forEach {
            assertThrows(IllegalArgumentException::class.java) { BillSplitter.amount(it) }
        }
    }
}
