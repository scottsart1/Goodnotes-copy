package com.inkwell.notes.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NaturalOrderTest {
    private val order = NaturalOrder(Locale.ENGLISH)

    @Test fun numbersCompareByValue() {
        val sorted = listOf("Page 10", "Page 2", "Page 1", "Page 02", "Page 100").sortedWith(order)
        assertEquals(listOf("Page 1", "Page 2", "Page 02", "Page 10", "Page 100"), sorted)
    }

    @Test fun dateNamedTitlesSortChronologically() {
        val sorted = listOf("20261015_Physics", "20250101_Physics", "20261001_Art").sortedWith(order)
        assertEquals(listOf("20250101_Physics", "20261001_Art", "20261015_Physics"), sorted)
    }

    @Test fun caseIsIgnoredButOrderIsStable() {
        assertTrue(order.compare("apple", "Banana") < 0)
        assertTrue(order.compare("Apple", "banana") < 0)
        assertTrue(order.compare("abc", "ABC") != 0)
        assertEquals(0, order.compare("same", "same"))
    }

    @Test fun accentsSortNextToTheirBaseLetter() {
        val sorted = listOf("Zebra", "École", "Eagle").sortedWith(order)
        assertEquals(listOf("Eagle", "École", "Zebra"), sorted)
    }

    @Test fun hugeNumbersDoNotOverflow() {
        assertTrue(order.compare("v99999999999999999999", "v100000000000000000000") < 0)
    }
}
