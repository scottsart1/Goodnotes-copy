package com.inkwell.notes.core.storage

import java.text.Collator
import java.util.Locale

/**
 * Natural, case-insensitive ordering for titles: digit runs compare by value
 * ("Lecture 2" < "Lecture 10"), text compares with the locale's collation ignoring case.
 * Not thread-safe (Collator); create one per sort.
 */
class NaturalOrder(locale: Locale = Locale.getDefault()) : Comparator<String> {
    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val aDigit = a[i].isAsciiDigit()
            val bDigit = b[j].isAsciiDigit()
            if (aDigit && bDigit) {
                val si = i
                while (i < a.length && a[i].isAsciiDigit()) i++
                val sj = j
                while (j < b.length && b[j].isAsciiDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length.compareTo(nb.length)
                val c = na.compareTo(nb)
                if (c != 0) return c
                val zeros = (i - si).compareTo(j - sj)
                if (zeros != 0) return zeros
            } else {
                val si = i
                while (i < a.length && a[i].isAsciiDigit() == aDigit) i++
                val sj = j
                while (j < b.length && b[j].isAsciiDigit() == bDigit) j++
                val c = collator.compare(a.substring(si, i), b.substring(sj, j))
                if (c != 0) return c
            }
        }
        val rest = (a.length - i).compareTo(b.length - j)
        if (rest != 0) return rest
        return a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
