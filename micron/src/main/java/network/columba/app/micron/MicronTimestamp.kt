package network.columba.app.micron

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Renders the `` `T `` timestamp construct of Micron markup:
 *
 * ```
 * `T<unix-seconds>`T
 * `T<unix-seconds>|<strftime-format>`T
 * ```
 *
 * The page supplies unix seconds, which carry no timezone, and the client renders that
 * instant in the reader's own zone. [format] is a strftime format — the conversions Python
 * 3's `time.strftime` accepts — and an empty one selects [DEFAULT_FORMAT].
 *
 * The engine is hand-written because [DateTimeFormatter] has no no-pad (`%-`) flag and
 * rejects an unknown conversion instead of passing it through.
 */
object MicronTimestamp {
    /** Used when a construct names no format: `Fri Sep 11, 2026 9:08:27PM EST`. */
    const val DEFAULT_FORMAT = "%a %b %d, %Y %-I:%M:%S%p %Z"

    /** Opens and closes a timestamp construct. */
    internal const val MARKER = "`T"

    /**
     * Renders unix [seconds] in [zone], the device's own zone by default. Throws
     * [java.time.DateTimeException] for an instant outside `java.time`'s calendar.
     */
    fun formatUnix(
        seconds: Long,
        format: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        formatTime(
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), zone),
            format.ifEmpty { DEFAULT_FORMAT },
        )

    /** Expands every conversion in [format] against [time]. */
    private fun formatTime(
        time: ZonedDateTime,
        format: String,
    ): String {
        val out = StringBuilder()
        var i = 0

        while (i < format.length) {
            if (format[i] != '%') {
                out.append(format[i])
                i++
            } else {
                val expansion = expansionAt(time, format, i)
                out.append(expansion.text)
                i = expansion.nextIndex
            }
        }

        return out.toString()
    }

    /** The text of one conversion and the index just past it. */
    private data class Expansion(
        val text: String,
        val nextIndex: Int,
    )

    /**
     * Expands the conversion at the `%` at [percent], including its `-` no-pad flag. POSIX
     * leaves an unrecognised conversion undefined, so it is copied through verbatim: a
     * typo in a page's format stays visible instead of vanishing.
     */
    private fun expansionAt(
        time: ZonedDateTime,
        format: String,
        percent: Int,
    ): Expansion {
        var cursor = percent + 1
        val noPad = cursor < format.length && format[cursor] == '-'
        if (noPad) cursor++
        if (cursor >= format.length) return Expansion("%", format.length)

        val expansion = expand(time, format[cursor], noPad)
        return Expansion(expansion ?: format.substring(percent, cursor + 1), cursor + 1)
    }

    private fun expand(
        time: ZonedDateTime,
        conversion: Char,
        noPad: Boolean,
    ): String? =
        when (conversion) {
            '%' -> "%"
            'a', 'A', 'b', 'h', 'B' -> name(time, conversion)
            'd', 'e', 'H', 'I', 'j', 'm', 'M', 'S', 'y' -> number(time, conversion, noPad)
            'D', 'F', 'R', 'T' -> composite(time, conversion)
            'p' -> if (time.hour < 12) "AM" else "PM"
            's' -> time.toEpochSecond().toString()
            'Y' -> time.year.toString()
            'z' -> zoneOffset(time)
            'Z' -> zoneName(time)
            else -> null
        }

    private fun name(
        time: ZonedDateTime,
        conversion: Char,
    ): String =
        when (conversion) {
            'a' -> time.dayOfWeek.getDisplayName(TextStyle.SHORT, ENGLISH)
            'A' -> time.dayOfWeek.getDisplayName(TextStyle.FULL, ENGLISH)
            'b', 'h' -> time.month.getDisplayName(TextStyle.SHORT, ENGLISH)
            'B' -> time.month.getDisplayName(TextStyle.FULL, ENGLISH)
            else -> ""
        }

    private fun number(
        time: ZonedDateTime,
        conversion: Char,
        noPad: Boolean,
    ): String =
        when (conversion) {
            'd' -> padded(time.dayOfMonth, 2, spaceFill = false, noPad = noPad)
            'e' -> padded(time.dayOfMonth, 2, spaceFill = true, noPad = noPad)
            'H' -> padded(time.hour, 2, spaceFill = false, noPad = noPad)
            'I' -> padded(hour12(time.hour), 2, spaceFill = false, noPad = noPad)
            'j' -> padded(time.dayOfYear, 3, spaceFill = false, noPad = noPad)
            'm' -> padded(time.monthValue, 2, spaceFill = false, noPad = noPad)
            'M' -> padded(time.minute, 2, spaceFill = false, noPad = noPad)
            'S' -> padded(time.second, 2, spaceFill = false, noPad = noPad)
            'y' -> padded(time.year % 100, 2, spaceFill = false, noPad = noPad)
            else -> ""
        }

    private fun composite(
        time: ZonedDateTime,
        conversion: Char,
    ): String =
        when (conversion) {
            'D' -> formatTime(time, "%m/%d/%y")
            'F' -> formatTime(time, "%Y-%m-%d")
            'R' -> formatTime(time, "%H:%M")
            'T' -> formatTime(time, "%H:%M:%S")
            else -> ""
        }

    /** Reports an hour on the 12-hour clock, where midnight and noon are both 12. */
    private fun hour12(hour: Int): Int {
        val wrapped = hour % 12
        return if (wrapped == 0) 12 else wrapped
    }

    private fun padded(
        value: Int,
        width: Int,
        spaceFill: Boolean,
        noPad: Boolean,
    ): String =
        when {
            noPad -> value.toString()
            spaceFill -> value.toString().padStart(width, ' ')
            else -> value.toString().padStart(width, '0')
        }

    /** `%z`: the numeric offset from UTC, `-0500`. An offset with seconds is truncated, as strftime is. */
    private fun zoneOffset(time: ZonedDateTime): String {
        val total = time.offset.totalSeconds
        val magnitude = abs(total)

        return buildString {
            append(if (total < 0) '-' else '+')
            append((magnitude / 3600).toString().padStart(2, '0'))
            append(((magnitude % 3600) / 60).toString().padStart(2, '0'))
        }
    }

    /**
     * `%Z`, read from the `z` pattern rather than [ZoneId.getDisplayName]: the latter
     * returns CLDR metazone titles (`ET`) where strftime returns the abbreviation
     * (`EDT`). java.time spells UTC's name `Z`, so that one is normalized.
     */
    private fun zoneName(time: ZonedDateTime): String {
        val name = ZONE_TEXT.format(time)
        return if (name == "Z") "UTC" else name
    }

    /** Names are pinned to English so a page renders the same on every device. */
    private val ENGLISH = Locale.ENGLISH

    private val ZONE_TEXT = DateTimeFormatter.ofPattern("z", ENGLISH)
}
