package com.quickcapture.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * Kotlin port of the Windows QuickCapture SmartParse.cs — identical rules.
 * Reminder date, #tags, ~list, !category, /priority and #N/`N priorities are
 * extracted; the remaining human text is [ParsedCapture.cleanText].
 * [ParsedCapture.tokens] records where each modifier sits in the ORIGINAL
 * input text, for syntax coloring.
 */
enum class TokenKind { Priority, Tag, List, DateTime, Category }

data class TokenSpan(val start: Int, val length: Int, val kind: TokenKind)

class ParsedCapture {
    var cleanText: String = ""
    var reminder: ZonedDateTime? = null
    val tags: MutableList<String> = mutableListOf()
    var list: String? = null
    var category: String? = null
    var priority: String? = null
    val tokens: MutableList<TokenSpan> = mutableListOf()

    val hint: String
        get() {
            val parts = mutableListOf<String>()
            reminder?.let { r ->
                val today = LocalDate.now()
                val day = when (r.toLocalDate()) {
                    today -> "Today"
                    today.plusDays(1) -> "Tomorrow"
                    else -> r.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
                }
                parts.add(
                    if (r.toLocalTime() == LocalTime.MIDNIGHT) day
                    else "$day ${r.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.US))}"
                )
            }
            parts.addAll(tags.map { "#$it" })
            if (!list.isNullOrEmpty()) parts.add("~$list")
            if (!category.isNullOrEmpty()) parts.add("!$category")
            if (!priority.isNullOrEmpty()) parts.add(priority!!)
            return parts.joinToString("  ·  ")
        }
}

object SmartParse {

    private val priorityMap = mapOf(
        "urgent" to "P0", "p0" to "P0",
        "high" to "P1", "p1" to "P1",
        "medium" to "P2", "p2" to "P2",
        "low" to "P3", "p3" to "P3"
    )

    private val weekdays = mapOf(
        "sunday" to DayOfWeek.SUNDAY, "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY
    )

    private val months = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2,
        "mar" to 3, "march" to 3, "apr" to 4, "april" to 4,
        "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "october" to 10, "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12
    )

    private const val DAY_NAMES = "sunday|monday|tuesday|wednesday|thursday|friday|saturday"
    private const val MONTH_NAMES = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec"

    fun parse(input: String, knownLists: Array<String>, knownCategories: Array<String>): ParsedCapture {
        val r = ParsedCapture()
        val padded = " $input " // padding so boundary lookarounds work at edges
        val now = ZonedDateTime.now()
        val today = now.toLocalDate()
        var date: LocalDate? = null
        var time: LocalTime? = null
        val consumed = mutableListOf<Pair<Int, Int>>() // ranges in ORIGINAL coordinates

        fun overlaps(s: Int, e: Int): Boolean {
            for ((cs, ce) in consumed) if (s < ce && e > cs) return true
            return false
        }

        // Finds matches on the padded text; coordinates are converted back to
        // the original input. handle() returns true when the match is consumed.
        fun eat(pattern: String, kind: TokenKind, handle: (MatchResult) -> Boolean) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            for (m in regex.findAll(padded)) {
                val s = m.range.first - 1
                val e = s + m.value.length
                if (s < 0 || e > input.length || overlaps(s, e)) continue
                if (handle(m)) {
                    consumed.add(s to e)
                    r.tokens.add(TokenSpan(s, m.value.length, kind))
                }
            }
        }

        // priority: #1 / `1 -> P1 .. #3 / `3 -> P3, #0 / `0 -> P0; also /high etc.
        eat("""(?<=\s)[#`]([0-3])(?=\s)""", TokenKind.Priority) { m ->
            r.priority = "P" + m.groupValues[1]
            true
        }
        eat("""(?<=\s)/(urgent|high|medium|low|p[0-3])(?=\s)""", TokenKind.Priority) { m ->
            r.priority = priorityMap[m.groupValues[1].lowercase(Locale.US)]
            true
        }

        // #tags
        eat("""(?<=\s)#([A-Za-z0-9_]+)(?=\s)""", TokenKind.Tag) { m ->
            r.tags.add(m.groupValues[1])
            true
        }

        // ~lists
        eat("""(?<=\s)~([A-Za-z0-9_]+)(?=\s)""", TokenKind.List) { m ->
            val v = m.groupValues[1]
            r.list = knownLists.firstOrNull { it.equals(v, ignoreCase = true) } ?: v
            true
        }

        // !categories (only consumed when it matches a known category)
        eat("""(?<=\s)!([A-Za-z0-9_]+)(?=\s)""", TokenKind.Category) { m ->
            val v = m.groupValues[1]
            val known = knownCategories.firstOrNull { it.equals(v, ignoreCase = true) }
            if (known == null) false // leave unknown !words as plain text
            else {
                r.category = known
                true
            }
        }

        // relative days
        eat("""(?<=\s)day after tomorrow(?=\s)""", TokenKind.DateTime) { date = today.plusDays(2); true }
        eat("""(?<=\s)(tomorrow|tmr)(?=\s)""", TokenKind.DateTime) { date = today.plusDays(1); true }
        eat("""(?<=\s)tonight(?=\s)""", TokenKind.DateTime) {
            date = today
            if (time == null) time = LocalTime.of(20, 0)
            true
        }
        eat("""(?<=\s)today(?=\s)""", TokenKind.DateTime) {
            if (date == null) date = today
            true
        }

        // in N hours/minutes/days/weeks
        eat("""(?<=\s)in (\d+) (hours?|hrs?|minutes?|mins?|days?|weeks?)(?=\s)""", TokenKind.DateTime) { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2].lowercase(Locale.US)
            val dt = when {
                unit.startsWith("hour") || unit.startsWith("hr") -> now.plusHours(n)
                unit.startsWith("min") -> now.plusMinutes(n)
                unit.startsWith("week") -> now.plusDays(7 * n)
                else -> now.plusDays(n)
            }
            date = dt.toLocalDate()
            time = dt.toLocalTime()
            true
        }

        // next <weekday> / <weekday>
        eat("""(?<=\s)next ($DAY_NAMES)(?=\s)""", TokenKind.DateTime) { m ->
            date = upcoming(weekdays[m.groupValues[1].lowercase(Locale.US)]!!, today)
            true
        }
        eat("""(?<=\s)($DAY_NAMES)(?=\s)""", TokenKind.DateTime) { m ->
            date = upcoming(weekdays[m.groupValues[1].lowercase(Locale.US)]!!, today)
            true
        }

        // parts of day (only meaningful with a date already set, e.g. "tomorrow morning")
        eat("""(?<=\s)(morning|afternoon|evening|noon)(?=\s)""", TokenKind.DateTime) { m ->
            if (date == null) false // keep "good morning team" untouched
            else {
                if (time == null) {
                    time = when (m.groupValues[1].lowercase(Locale.US)) {
                        "morning" -> LocalTime.of(9, 0)
                        "afternoon" -> LocalTime.of(14, 0)
                        "evening" -> LocalTime.of(18, 0)
                        else -> LocalTime.of(12, 0)
                    }
                }
                true
            }
        }

        // times: at 5pm / 5:30pm / 17:00
        eat("""(?<=\s)(?:at )?(\d{1,2})(?::(\d{2}))?\s*(am|pm)(?=\s)""", TokenKind.DateTime) { m ->
            var h = m.groupValues[1].toInt()
            val min = if (m.groups[2] != null) m.groupValues[2].toInt() else 0
            val ap = m.groupValues[3].lowercase(Locale.US)
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h > 23) false
            else {
                time = LocalTime.of(h, min)
                true
            }
        }
        eat("""(?<=\s)(?:at )?(\d{1,2}):(\d{2})(?=\s)""", TokenKind.DateTime) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) false
            else {
                time = LocalTime.of(h, min)
                true
            }
        }

        // explicit dates: dec 25 / 25 dec / 12/25 / 2026-12-25
        fun mkDate(y: Int, mo: Int, d: Int): LocalDate {
            val dt = LocalDate.of(y, mo, d)
            return if (dt.isBefore(today)) dt.plusYears(1) else dt
        }
        eat("""(?<=\s)($MONTH_NAMES)[a-z]* (\d{1,2})(?=\s)""", TokenKind.DateTime) { m ->
            date = mkDate(today.year, months[m.groupValues[1].lowercase(Locale.US)]!!, m.groupValues[2].toInt())
            true
        }
        eat("""(?<=\s)(\d{1,2}) ($MONTH_NAMES)[a-z]*(?=\s)""", TokenKind.DateTime) { m ->
            date = mkDate(today.year, months[m.groupValues[2].lowercase(Locale.US)]!!, m.groupValues[1].toInt())
            true
        }
        eat("""(?<=\s)(\d{1,2})/(\d{1,2})(?=\s)""", TokenKind.DateTime) { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a < 1 || a > 12 || b < 1 || b > 31) false
            else {
                date = mkDate(today.year, a, b)
                true
            }
        }
        eat("""(?<=\s)(\d{4})-(\d{1,2})-(\d{1,2})(?=\s)""", TokenKind.DateTime) { m ->
            date = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            true
        }

        // time without date -> today, or tomorrow if that time already passed
        if (time != null && date == null) {
            date = if (today.atTime(time).isAfter(LocalDateTime.now())) today else today.plusDays(1)
        }

        if (date != null) {
            r.reminder = ZonedDateTime.of(date, time ?: LocalTime.MIDNIGHT, now.zone)
        }

        // CleanText: original input minus consumed ranges
        val sb = StringBuilder()
        var pos = 0
        for ((s, e) in consumed.sortedBy { it.first }) {
            if (s > pos) sb.append(input, pos, s)
            pos = max(pos, e)
        }
        if (pos < input.length) sb.append(input, pos, input.length)
        r.cleanText = sb.toString().trim().replace(Regex("""\s+"""), " ")
        return r
    }

    private fun upcoming(dow: DayOfWeek, from: LocalDate): LocalDate {
        var delta = (dow.value - from.dayOfWeek.value + 7) % 7
        if (delta == 0) delta = 7
        return from.plusDays(delta.toLong())
    }
}
