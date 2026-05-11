// License: GPL v2 or later. For details, see LICENSE file.
//
// The date-normalization algorithms in this class are derived from
// utilEDTFFromOSMDateString() in the OpenHistoricalMap fork of the
// iD editor, originally authored by Minh Nguyễn (GitHub: @1ec5).
// See: https://github.com/OpenHistoricalMap/iD/commit/4d5cb19
// Original code licensed under the ISC license; see LICENSES/iD-ISC.txt.
package org.openstreetmap.josm.plugins.ohmtags;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.openhistoricalmap.edtf.Edtf;
import io.github.openhistoricalmap.edtf.EdtfParseException;
import io.github.openhistoricalmap.edtf.EdtfTemporal;
import io.github.openhistoricalmap.edtf.types.EdtfCentury;
import io.github.openhistoricalmap.edtf.types.EdtfDate;
import io.github.openhistoricalmap.edtf.types.EdtfDecade;
import io.github.openhistoricalmap.edtf.types.EdtfInterval;
import io.github.openhistoricalmap.edtf.types.EdtfList;
import io.github.openhistoricalmap.edtf.types.EdtfSeason;
import io.github.openhistoricalmap.edtf.types.EdtfSet;
import io.github.openhistoricalmap.edtf.types.Endpoint;
import io.github.openhistoricalmap.edtf.types.ListMember;

/**
 * Converts OHM/OSM-style approximate date strings into EDTF (ISO 8601-2).
 * Pure string-in / string-out; no JOSM dependencies.
 *
 * <p>Port of {@code utilEDTFFromOSMDateString} from the iD/OHM editor JavaScript.
 * Supported input formats:
 * <ul>
 *   <li>{@code YYYY}, {@code YYYY-MM}, {@code YYYY-MM-DD} (optionally with {@code BC}/{@code BCE})</li>
 *   <li>{@code ~YYYY} — approximate (circa)</li>
 *   <li>{@code YYYY..ZZZZ} — closed range</li>
 *   <li>{@code YYYY0s} — decade (e.g. {@code 1850s})</li>
 *   <li>{@code CNN} — century (e.g. {@code C19})</li>
 *   <li>{@code early|mid|late YYYY0s} — partial decade</li>
 *   <li>{@code early|mid|late CNN} — partial century</li>
 *   <li>{@code before YYYY-MM-DD} / {@code after YYYY-MM-DD}</li>
 * </ul>
 *
 * <p>Returns {@link Optional#empty()} for unparseable input.
 */
public final class DateNormalizer {

    private DateNormalizer() { }

    // --- Regexes (compile once) ---------------------------------------------

    private static final Pattern RANGE =
        Pattern.compile("^(.+)\\.\\.(.+?)( BCE?)?$");

    /**
     * {@code YYYY-MM..MM} where both the start month and the tail are valid
     * month values (01-12). Structurally ambiguous: the tail could be the
     * month sharing the year prefix ({@code 1904-05..08} as "May to August
     * 1904", i.e. {@code 1904-05/1904-08}) or a 2-digit year sharing the
     * century prefix ({@code 1904-05..08} as "May 1904 to 1908", parallel
     * to the existing {@code YYYY..YY} abbreviated-tail-range rule). The
     * generic RANGE branch would silently interpret the tail as a bare
     * 2-digit year and produce a backwards interval (e.g.
     * {@code 1904-05/0008}). Intercepted here to return
     * {@link Optional#empty()}; the validator's dedicated rule emits a
     * tailored unfixable warning describing the two interpretations.
     */
    private static final Pattern YYYY_MM_DOTDOT_MONTH_TAIL_AMBIGUOUS =
        Pattern.compile("^\\d{4}-(?:0[1-9]|1[0-2])\\.\\.(?:0[1-9]|1[0-2])$");

    /**
     * Simple year with optional qualifier. Qualifier may be prefix or suffix
     * and may be {@code ~}, {@code ?}, or {@code %}. The regex's group order
     * (year/monthDay before suffix qualifier before BCE marker) keeps trailing
     * {@code ~} unambiguous against the BCE suffix — {@code 1970~ BCE} parses
     * cleanly. Accepts both positive years and astronomical negative years
     * ({@code -180}, {@code ~-180}).
     */
    private static final Pattern SIMPLE_YEAR =
        Pattern.compile("^([~?%])?(-?\\d+)(-\\d\\d(?:-\\d\\d)?)?([~?%])?( BCE?)?$");

    /**
     * Range of two 1- or 2-digit years separated by a hyphen, with an
     * optional leading qualifier on the left bound (e.g. {@code ~47-50},
     * {@code 47-50}). The qualifier — when present — propagates to the
     * left bound only, matching the user's syntactic intent (the {@code ~}
     * sits before the first year).
     *
     * <p>The pattern requires the left bound to be 1–2 digits, which a
     * legitimate ISO year-month would never have ({@code 1950-05}'s left
     * bound is 4 digits), so this can't be confused with year-month parsing.
     */
    private static final Pattern QUALIFIED_SHORT_YEAR_RANGE =
        Pattern.compile("^([~?%])?(\\d{1,2})-(\\d{1,2})$");

    /**
     * Range of ordinal centuries with optional early/mid/late modifiers on
     * either or both sides, with the trailing word "Century" applying to
     * both: {@code 5th - mid 8th Century}, {@code early 1st - 3rd Century BC}.
     * Each side is normalized recursively via {@link #toEdtf} (as
     * {@code C5}, {@code mid C8}, etc.) and the bounds combined.
     */
    private static final Pattern ORDINAL_CENTURY_RANGE =
        Pattern.compile(
            "^(?i)" +
            "(?:(early|mid|late)\\s+)?(\\d+)(?:st|nd|rd|th)" +
            "\\s*-\\s*" +
            "(?:(early|mid|late)\\s+)?(\\d+)(?:st|nd|rd|th)" +
            "\\s+century(\\s+BC)?$"
        );

    private static final Pattern DECADE =
        Pattern.compile("^([~?%])?(\\d+)0s([~?%])?( BCE?)?$");

    private static final Pattern CENTURY =
        Pattern.compile("^([~?%])?C(\\d+)([~?%])?( BCE?)?$");

    private static final Pattern THIRD_DECADE =
        Pattern.compile("^(?i)([~?%])?(early|mid|late) (\\d+)0s( BCE?)?$");

    private static final Pattern THIRD_CENTURY =
        Pattern.compile("^(?i)([~?%])?(early|mid|late) C(\\d+)( BCE?)?$");

    /**
     * {@code early|mid|late YYYY} or {@code early|mid|late YYYY-MM}, with an
     * optional trailing {@code BC} / {@code BCE} marker. Case-insensitive on
     * the modifier. Captures (1) modifier, (2) 4-digit year, (3) optional
     * 2-digit month, (4) optional BCE marker. The handler in
     * {@link #toEdtf} splits the year into thirds (January-April /
     * May-August / September-December) or splits the month into thirds (days
     * 1-10 / 11-20 / 21-end for most months, with the special-case Feb 1-9 /
     * 10-19 / 20-end so it fits the shorter month). For BCE input the year
     * is converted to astronomical form (BC N → year {@code -(N-1)}, so
     * "100 BC" is astronomical -0099).
     */
    private static final Pattern THIRD_PARTIAL_YEAR_OR_MONTH =
        Pattern.compile("^(?i)(early|mid|late)\\s+(\\d{1,4})(?:-(\\d\\d))?( BCE?)?$");

    /**
     * Matches "before X", "by X", or "as of X" (and the colon-prefix
     * variants {@code before:X} / {@code by:X} / {@code as of:X}, plus
     * the dotdot-as-separator form {@code by..X} after multi-dot collapse
     * has reduced {@code by...X} to two dots). All three keywords resolve
     * to the same open-ended interval ending at X; the captured inner
     * value is normalized via {@link #beforeAfterInner}.
     */
    private static final Pattern BEFORE =
        Pattern.compile("^(?:before|by|as of)(?:[ :]+|\\.\\.+)(.+)$");

    /** Symmetric counterpart to {@link #BEFORE}: open-ended interval starting at X. */
    private static final Pattern AFTER =
        Pattern.compile("^after(?:[ :]+|\\.\\.+)(.+)$");

    /**
     * Matches "during X" / "during:X" — semantically equivalent to plain X
     * (the {@code during} prefix adds no information). The captured inner
     * value is normalized recursively via {@link #toEdtf}.
     */
    private static final Pattern DURING =
        Pattern.compile("^during(?:[ :]+|\\.\\.+)(.+)$");

    /**
     * Strict ISO date or year inside a before/after expression: {@code YYYY},
     * {@code YYYY-MM}, or {@code YYYY-MM-DD}. Preserved at full precision
     * because the format is unambiguous.
     */
    private static final Pattern STRICT_ISO_FOR_BEFORE_AFTER =
        Pattern.compile("^\\d{4}(?:-\\d\\d(?:-\\d\\d)?)?$");

    /**
     * Day-month-year or month-day-year with dash separators inside a
     * before/after expression: {@code 01-01-1882}, {@code 1-1-1882},
     * {@code 12-31-1999}. The day/month order is ambiguous, so the inner
     * normalization coarsens to year-only (the {@code (\d{4})} capture group);
     * "before X" with a fuzzy bound is fuzzy enough that losing day-of-month
     * precision is acceptable. Slash-separated variants ({@code 01/01/1882})
     * are normalized to ISO {@code 1882-01-01} earlier in {@link #preprocess},
     * so they hit {@link #STRICT_ISO_FOR_BEFORE_AFTER} instead.
     */
    private static final Pattern DAY_MONTH_YEAR_DASHED =
        Pattern.compile("^\\d{1,2}-\\d{1,2}-(\\d{4})$");

    /**
     * Open-ended slash-form intervals. These typically arise from the
     * preprocess step rewriting trailing/leading {@code ..} to {@code /}
     * (e.g. {@code ..1945-05-20} → {@code /1945-05-20}). Handled by
     * recursively normalizing the bounded side so canonicalization
     * (e.g. {@code cYYYY} → {@code ~YYYY}, year padding) still runs.
     */
    private static final Pattern LEADING_SLASH =
        Pattern.compile("^/(.+)$");

    private static final Pattern TRAILING_SLASH =
        Pattern.compile("^(.+)/$");

    // --- Preprocessing patterns ---------------------------------------------

    /** Trailing ISO datetime component like "T00:00:00" or "T00:00:00Z". */
    private static final Pattern ISO_DATETIME_SUFFIX =
        Pattern.compile("T\\d{2}:\\d{2}(?::\\d{2})?Z?$");

    /**
     * Century forms the preprocessor should canonicalize to {@code CN}:
     * {@code C4}, {@code 4C}, {@code C.4}, {@code 4-C}, {@code C 19.}, etc.
     * Case-insensitive; allows optional {@code .} and {@code -} and whitespace
     * between the C and the digits.
     */
    private static final Pattern CENTURY_VARIANT_CN =
        Pattern.compile("^(?i)c[.\\s-]*(\\d+)\\.?$");
    private static final Pattern CENTURY_VARIANT_NC =
        Pattern.compile("^(?i)(\\d+)[.\\s-]*c\\.?$");

    /** "20th century" (case-insensitive). */
    private static final Pattern CENTURY_ORDINAL =
        Pattern.compile("^(?i)(\\d+)(?:st|nd|rd|th)\\s+century$");

    /**
     * BCE variants to canonicalize to " BC" suffix. Accepts forms like
     * {@code " BC"}, {@code " BCE"}, {@code " B.C.E."}, {@code "bc"}
     * attached directly to digits ({@code "500bc"}), {@code "B.C."}, etc.
     * Case-insensitive.
     *
     * <p>Two anchors accepted: digit-attached (e.g. {@code "500bc"}) via
     * the lookbehind, or after one-or-more spaces (e.g.
     * {@code "8th Century BC"}) via {@code \\s+}. The {@code \\s+} arm
     * consumes the existing whitespace as part of the match, so the
     * {@code " BC"} replacement does not introduce a double-space — that
     * would otherwise break downstream "Nth century BC" head-extraction,
     * which uses a strict {@code endsWith(" BC")}/strict-ordinal pair.
     */
    private static final Pattern BCE_SUFFIX =
        Pattern.compile("(?i)(?:(?<=\\d)|\\s+)b\\.?\\s*c\\.?e?\\.?$");

    /** Unambiguous YYYY/MM/DD (year-first, 4 digits). */
    private static final Pattern SLASH_DATE_YMD =
        Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$");

    /** Potentially-ambiguous NN/NN/YYYY (year-last). */
    private static final Pattern SLASH_DATE_MDY =
        Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");

    /** Two-component slash like "1850/1900" (potential range). */
    private static final Pattern SLASH_RANGE_2 =
        Pattern.compile("^(\\d{3,4}(?:-\\d{1,2}(?:-\\d{1,2})?)?)/(\\d{3,4}(?:-\\d{1,2}(?:-\\d{1,2})?)?)$");

    /**
     * "circa YYYY" / "ca YYYY" / "ca. YYYY" / "around YYYY" etc.,
     * case-insensitive. Captures the tail date portion (everything after
     * the circa marker).
     */
    private static final Pattern CIRCA_PREFIX =
        Pattern.compile("^(?i)(?:circa|ca\\.?|around)\\s+(.+)$");

    /**
     * Compact "cYYYY" / "c YYYY" / "c-YYYY" shorthand — captures the
     * signed digits. Used by {@link #parseCShorthandYear} after BCE-suffix
     * stripping. Matches case-insensitively.
     */
    private static final Pattern C_SHORTHAND =
        Pattern.compile("^(?i)c\\s*(-?\\d+)$");

    /**
     * "between X and Y" — explicit range expressed in English.
     * Case-insensitive. Captures the two endpoint tails.
     */
    private static final Pattern BETWEEN_RANGE =
        Pattern.compile("^(?i)between\\s+(.+?)\\s+and\\s+(.+)$");

    /**
     * "first half of X" / "second half of X" — half-year/decade/century ranges.
     * Case-insensitive. Captures the half indicator (1=first, 2=second) and
     * the tail.
     */
    private static final Pattern HALF_OF =
        Pattern.compile("^(?i)(first|second|1st|2nd)\\s+half\\s+of\\s+(.+)$");

    /**
     * Julian-calendar date marker: {@code j:YYYY-MM-DD} (or YYYY / YYYY-MM).
     * The prefix indicates the date is given in the Julian calendar and needs
     * to be converted to Gregorian for ISO output.
     */
    private static final Pattern JULIAN_DATE =
        Pattern.compile("^(?i)j:(-?\\d{1,4})(?:-(\\d{1,2}))?(?:-(\\d{1,2}))?$");

    /**
     * Julian day number (astronomical continuous day count from 4713 BCE noon UTC):
     * {@code jd:NNNNNNN}. Typically 7 digits for dates in recent history, but
     * we accept a broad range.
     */
    private static final Pattern JULIAN_DAY_NUMBER =
        Pattern.compile("^(?i)jd:(\\d{1,8})$");

    /**
     * Natural-language season expression: optional "early/mid/late" (ignored
     * for season assignment — EDTF season codes already mean "the whole
     * season", and EDTF Level 2 subdivisions within a season are uncommon
     * enough we don't bother), a season name, optional "of", a year, and
     * optional " BC" suffix.
     *
     * <p>Season synonyms: fall = autumn, winter. Captures season (group 1)
     * and year (group 2).
     */
    private static final Pattern NATURAL_SEASON =
        Pattern.compile("^(?i)(?:early\\s+|mid[-\\s]|late\\s+)?"
                      + "(spring|summer|fall|autumn|winter)"
                      + "(?:[,\\s]+of)?[,\\s]+(\\d{3,4})( BC)?$");

    /**
     * Map of lowercase English month names and 3-letter abbreviations to
     * month numbers. The abbreviations use either the standard 3-letter form
     * with or without a trailing period (the period is stripped before lookup).
     */
    private static final java.util.Map<String, Integer> MONTH_NAMES = buildMonthNames();

    private static java.util.Map<String, Integer> buildMonthNames() {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        String[] longs = {"january", "february", "march", "april", "may", "june",
                          "july", "august", "september", "october", "november", "december"};
        String[] shorts = {"jan", "feb", "mar", "apr", "may", "jun",
                           "jul", "aug", "sep", "oct", "nov", "dec"};
        // Also accept "sept" as a 4-letter variant for September.
        for (int i = 0; i < 12; i++) {
            m.put(longs[i], i + 1);
            m.put(shorts[i], i + 1);
        }
        m.put("sept", 9);
        return m;
    }

    /**
     * Map of lowercase English season names to EDTF Level 1 season codes
     * (21-24). "Fall" and "autumn" are synonyms. "Winter of YYYY" per wiki
     * convention maps to {@code YYYY-24} (the EDTF-labeled winter associated
     * with that year), not the December-of-previous-year interpretation.
     */
    private static final java.util.Map<String, Integer> SEASON_NAMES = buildSeasonNames();

    private static java.util.Map<String, Integer> buildSeasonNames() {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        m.put("spring", 21);
        m.put("summer", 22);
        m.put("autumn", 23);
        m.put("fall",   23);
        m.put("winter", 24);
        return m;
    }

    /**
     * "MonthName day, year" — e.g. "March 10, 1970", "Mar 10, 1970",
     * "Mar. 10, 1970", "March 10th, 1970". Captures: (1) month, (2) day,
     * (3) year, (4) optional " BC" suffix.
     */
    private static final Pattern WRITTEN_MDY =
        Pattern.compile("^(?i)([A-Za-z]+)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{3,4})( BC)?$");

    /**
     * "day MonthName year" — e.g. "10 March 1970", "10 Mar 1970",
     * "10th March 1970". Captures: (1) day, (2) month, (3) year, (4) BC.
     */
    private static final Pattern WRITTEN_DMY =
        Pattern.compile("^(?i)(\\d{1,2})(?:st|nd|rd|th)?\\s+([A-Za-z]+)\\.?\\s+(\\d{3,4})( BC)?$");

    /** "MonthName year" — e.g. "September 1945", "Mar 1970", "March 1945 BC". */
    private static final Pattern WRITTEN_MY =
        Pattern.compile("^(?i)([A-Za-z]+)\\.?\\s+(\\d{3,4})( BC)?$");

    /** "year MonthName" — e.g. "2018 Dec", "1970 September". (1) year, (2) month, (3) BC. */
    private static final Pattern WRITTEN_YM =
        Pattern.compile("^(?i)(\\d{3,4})\\s+([A-Za-z]+)\\.?( BC)?$");

    /** "year MonthName day" — e.g. "2021 May 01", "1970 Mar 10th". (1) year, (2) month, (3) day, (4) BC. */
    private static final Pattern WRITTEN_YMD =
        Pattern.compile("^(?i)(\\d{3,4})\\s+([A-Za-z]+)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?( BC)?$");

    /** "YYYY-MonthName-DD" / "YYYY-MonthName" — hyphen-separated with month name. */
    private static final Pattern WRITTEN_ISO_LIKE =
        Pattern.compile("^(?i)(\\d{3,4})-([A-Za-z]+)(?:-(\\d{1,2}))?( BC)?$");

    /**
     * Try to parse an English-language written date. Returns the ISO
     * equivalent ({@code YYYY-MM-DD} or {@code YYYY-MM}) on success, or
     * {@code null} if the input isn't a recognized written-date form.
     * A trailing " BC" is preserved verbatim so later BCE handling can
     * process it.
     */
    private static String tryParseWrittenDate(String s) {
        Matcher m = WRITTEN_MDY.matcher(s);
        if (m.matches()) {
            Integer month = MONTH_NAMES.get(m.group(1).toLowerCase());
            if (month != null) {
                String bc = m.group(4) == null ? "" : m.group(4);
                return m.group(3) + "-" + pad2(String.valueOf(month))
                                  + "-" + pad2(m.group(2)) + bc;
            }
        }
        m = WRITTEN_DMY.matcher(s);
        if (m.matches()) {
            Integer month = MONTH_NAMES.get(m.group(2).toLowerCase());
            if (month != null) {
                String bc = m.group(4) == null ? "" : m.group(4);
                return m.group(3) + "-" + pad2(String.valueOf(month))
                                  + "-" + pad2(m.group(1)) + bc;
            }
        }
        m = WRITTEN_YMD.matcher(s);
        if (m.matches()) {
            Integer month = MONTH_NAMES.get(m.group(2).toLowerCase());
            if (month != null) {
                String bc = m.group(4) == null ? "" : m.group(4);
                return m.group(1) + "-" + pad2(String.valueOf(month))
                                  + "-" + pad2(m.group(3)) + bc;
            }
        }
        m = WRITTEN_YM.matcher(s);
        if (m.matches()) {
            Integer month = MONTH_NAMES.get(m.group(2).toLowerCase());
            if (month != null) {
                String bc = m.group(3) == null ? "" : m.group(3);
                return m.group(1) + "-" + pad2(String.valueOf(month)) + bc;
            }
        }
        m = WRITTEN_MY.matcher(s);
        if (m.matches()) {
            Integer month = MONTH_NAMES.get(m.group(1).toLowerCase());
            if (month != null) {
                String bc = m.group(3) == null ? "" : m.group(3);
                return m.group(2) + "-" + pad2(String.valueOf(month)) + bc;
            }
        }
        m = WRITTEN_ISO_LIKE.matcher(s);
        if (m.matches()) {
            Integer month = MONTH_NAMES.get(m.group(2).toLowerCase());
            if (month != null) {
                String bc = m.group(4) == null ? "" : m.group(4);
                String day = m.group(3);
                if (day != null) {
                    return m.group(1) + "-" + pad2(String.valueOf(month))
                                      + "-" + pad2(day) + bc;
                }
                return m.group(1) + "-" + pad2(String.valueOf(month)) + bc;
            }
        }
        return null;
    }

    /**
     * If the string ends in a trailing-position century form after a
     * modifier like "early", "mid", or "late" (e.g. "mid 2C", "late 19C",
     * "early 20 c.", "mid C.19"), normalize the tail to canonical "CN"
     * form so the existing THIRD_CENTURY regex can match.
     *
     * <p>Returns the original string unchanged if no such pattern is present.
     */
    private static String normalizeTrailingCentury(String s) {
        // Match: <modifier> <tail-century>, with optional " BC" at the end.
        // Modifiers we handle: early, mid, late.
        Matcher m = Pattern.compile("^(?i)(early|mid|late)\\s+(.+?)( BC)?$").matcher(s);
        if (!m.matches()) return s;

        String tail = m.group(2);
        String bc = m.group(3) == null ? "" : m.group(3);

        // Is the tail already a canonical "CN"? Then leave alone.
        if (tail.matches("^C\\d+$")) return s;

        // Try to normalize the tail to a "CN" form using the century-variant
        // patterns we already have.
        Matcher cnMatch = CENTURY_VARIANT_CN.matcher(tail);
        if (cnMatch.matches()) {
            return m.group(1) + " C" + cnMatch.group(1) + bc;
        }
        Matcher ncMatch = CENTURY_VARIANT_NC.matcher(tail);
        if (ncMatch.matches()) {
            return m.group(1) + " C" + ncMatch.group(1) + bc;
        }
        Matcher ordMatch = CENTURY_ORDINAL.matcher(tail);
        if (ordMatch.matches()) {
            return m.group(1) + " C" + ordMatch.group(1) + bc;
        }

        return s;
    }

    /**
     * Resolve a "first half of X" / "second half of X" expression to an
     * explicit slash-interval EDTF string.
     *
     * <p>Supported tail forms:
     * <ul>
     *   <li>Year (e.g. "1943"): first half = {@code YYYY-01/YYYY-06},
     *       second half = {@code YYYY-07/YYYY-12}</li>
     *   <li>Decade (e.g. "1940s" or "~1940s"): first half = 5-year range,
     *       second half = 5-year range</li>
     *   <li>Century (e.g. "20C", "C20", "19th century"): first half =
     *       50-year range, second half = 50-year range</li>
     * </ul>
     *
     * <p>Returns {@code null} if the tail isn't a recognized form.
     * Otherwise returns a ready-to-use EDTF slash interval that the caller
     * can skip the rest of preprocess on.
     */
    private static String tryResolveHalfOf(String tail, boolean firstHalf) {
        tail = tail.trim();

        // Normalize century variants in the tail if needed.
        Matcher cnMatch = CENTURY_VARIANT_CN.matcher(tail);
        Matcher ncMatch = CENTURY_VARIANT_NC.matcher(tail);
        Matcher ordMatch = CENTURY_ORDINAL.matcher(tail);
        Integer century = null;
        if (cnMatch.matches()) century = Integer.parseInt(cnMatch.group(1));
        else if (ncMatch.matches()) century = Integer.parseInt(ncMatch.group(1));
        else if (ordMatch.matches()) century = Integer.parseInt(ordMatch.group(1));

        if (century != null) {
            // Century N = years (N-1)*100 .. (N-1)*100+99.
            int centuryStart = (century - 1) * 100;
            if (firstHalf) {
                return padYear4(centuryStart) + "/" + padYear4(centuryStart + 49);
            } else {
                return padYear4(centuryStart + 50) + "/" + padYear4(centuryStart + 99);
            }
        }

        // Decade: "1940s" or "~1940s"
        Matcher decadeMatch = Pattern.compile("^~?(\\d+)0s$").matcher(tail);
        if (decadeMatch.matches()) {
            int decadeTens = Integer.parseInt(decadeMatch.group(1));
            int decadeStart = decadeTens * 10;
            if (firstHalf) {
                return padYear4(decadeStart) + "/" + padYear4(decadeStart + 4);
            } else {
                return padYear4(decadeStart + 5) + "/" + padYear4(decadeStart + 9);
            }
        }

        // Year: plain 3-4 digit number.
        Matcher yearMatch = Pattern.compile("^(\\d{3,4})$").matcher(tail);
        if (yearMatch.matches()) {
            String y = padYear4(Integer.parseInt(yearMatch.group(1)));
            if (firstHalf) {
                return y + "-01/" + y + "-06";
            } else {
                return y + "-07/" + y + "-12";
            }
        }

        return null;
    }

    /** Pad an integer year to 4 digits (for positive years). */
    private static String padYear4(int year) {
        return String.format("%04d", year);
    }

    /**
     * Preprocess an input string to canonicalize common variants before
     * format-dispatch parsing. Returns a cleaned string ready for matching
     * against the format regexes. This only rewrites input shape; it doesn't
     * produce EDTF.
     *
     * <p>Steps applied, in order:
     * <ol>
     *   <li>Collapse internal whitespace to a single space, trim ends</li>
     *   <li>Strip trailing ISO datetime component ({@code T00:00:00})</li>
     *   <li>Normalize BCE suffix variants to " BC"</li>
     *   <li>Normalize century variants (e.g. "20th century", "4c.", "C-4") to "CN"</li>
     *   <li>Convert unambiguous slash date punctuation to dashes
     *       ({@code YYYY/MM/DD} and {@code MM/DD/YYYY} when disambiguable)</li>
     *   <li>Convert two-component slash ranges to {@code ..} range syntax
     *       ({@code 1850/1900} → {@code 1850..1900})</li>
     * </ol>
     *
     * <p>Whitespace-around-separator fixes (e.g. {@code 1850 - 03 - 15} →
     * {@code 1850-03-15}) are handled by collapsing whitespace entirely
     * within core date patterns: after collapse, we also strip whitespace
     * adjacent to {@code -}, {@code ..}, and {@code /} characters so
     * "1850 - 03 - 15" becomes "1850-03-15".
     */
    public static String preprocess(String raw) {
        if (raw == null) return null;

        // 1. Trim and collapse whitespace.
        String s = raw.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return s;

        // Normalize Unicode dash variants to ASCII hyphen-minus. Common in
        // pasted-from-word-processor data: en-dash (U+2013), em-dash (U+2014),
        // figure-dash (U+2012), minus-sign (U+2212).
        s = s.replace('‒', '-')
             .replace('–', '-')
             .replace('—', '-')
             .replace('−', '-');

        // Collapse runs of 3+ dots to two dots — three or more is always
        // junk (typo, ellipsis, or copy-paste artifact). Catches things
        // like "1839...1859" and "[1907...]".
        s = s.replaceAll("\\.{3,}", "..");

        // "5 - 1 BCE" style spaced-hyphen BCE range: must be caught BEFORE the
        // whitespace-collapse step below eats the spaces and turns "5 - 1 BCE"
        // into "5-1 BCE" (which no pattern handles). Rewrite to ".." form so
        // the standard BCE normalization and RANGE branch can handle it.
        Matcher spacedBce = SPACED_HYPHEN_BCE_RANGE.matcher(s);
        if (spacedBce.matches()) {
            s = spacedBce.group(1) + ".." + spacedBce.group(2) + " BCE";
        }

        // Strip whitespace adjacent to hyphens, dots (EDTF range ..), slashes.
        s = s.replaceAll("\\s*-\\s*", "-");
        s = s.replaceAll("\\s*\\.\\.\\s*", "..");
        s = s.replaceAll("\\s*/\\s*", "/");

        // Qualifier adjacent to `..`: rewrite to slash form with the
        // qualifier on the bound year.
        //   "~..1907" → "/1907~"  (open-ended-left, ends ~1907)
        //   "1907..~" → "1907~/"  (open-ended-right, starts ~1907)
        // EDTF can't attach a qualifier directly to a `..` range marker,
        // so we promote the qualifier to the bound year via the slash form.
        // Guard: only fire when no `/` is already present — otherwise a value
        // like "1900/..~" (already a slash interval) would be wrongly mutated
        // to "1900/~/" instead of reaching the "/[.~]+$" strip below.
        if (!s.contains("/")) {
            s = s.replaceAll("^([~?%])\\.\\.+(.+)$", "/$2$1");
            s = s.replaceAll("^(.+)\\.\\.+([~?%])$", "$1$2/");
        }

        // Strip "junk-edge" `..` markers immediately adjacent to `/` —
        // e.g. "1839../..1859" → "1839/1859". These typically arise from
        // partial edits (the `..` and the `/` are both range-separators
        // and aren't meaningful together).
        boolean adjacentJunkStripped = false;
        if (s.contains("../")) {
            s = s.replaceAll("\\.\\.+/", "/");
            adjacentJunkStripped = true;
        }
        if (s.contains("/..")) {
            s = s.replaceAll("/\\.\\.+", "/");
            adjacentJunkStripped = true;
        }

        // If the inner-junk strip just fired AND a leading or trailing `..`
        // remains alongside the `/`, that remaining `..` is also junk —
        // the user wrote redundant range markers on both ends. Strip
        // outright (rather than rewriting to `/`) to keep a single
        // canonical `/` in the output. Without this guard, the standard
        // step 8 leading/trailing-`..` → `/` rewrite below would produce
        // a double-slash like "/1839/1859" (invalid EDTF).
        //
        // We require `adjacentJunkStripped` to gate this: a leading `..`
        // on a value that came in cleanly with no inner-`..` junk (e.g.
        // "...15/11/1997" → "..15/11/1997") is a meaningful open-ended-left
        // marker, not junk, so leave it alone — step 8 will correctly
        // rewrite it to a leading `/`.
        if (adjacentJunkStripped && s.contains("/")) {
            if (s.startsWith("..")) s = s.substring(2);
            if (s.endsWith("..")) s = s.substring(0, s.length() - 2);
        }

        // Strip a stray qualifier directly after an X-form (e.g. "196X?",
        // "18XX~"). EDTF rejects qualifiers attached to X-forms; the
        // closest valid form is to drop the qualifier and keep the
        // unspecified-digit form.
        s = s.replaceAll("([Xx]+)[~?%]", "$1");

        // 1a. Underscore-as-separator: "1855_12" → "1855-12", "1970_01_01"
        //     → "1970-01-01". We only replace underscores that sit between
        //     two digits, to avoid clobbering anything word-like.
        s = s.replaceAll("(\\d)_(\\d)", "$1-$2");
        // Run a second pass because overlapping matches (e.g. "1970_01_01"
        // only matches one underscore per non-overlapping pass).
        s = s.replaceAll("(\\d)_(\\d)", "$1-$2");

        // 2. Strip ISO datetime suffix on a YYYY-MM-DD prefix.
        s = ISO_DATETIME_SUFFIX.matcher(s).replaceAll("");

        // 3. Normalize BCE suffix variants (BC, B.C., bce, B.C.E., etc.) to " BC".
        s = BCE_SUFFIX.matcher(s).replaceAll(" BC");

        // 3a. Normalize "circa" / "ca" / "ca." / "around" prefixes to "~".
        Matcher mc = CIRCA_PREFIX.matcher(s);
        if (mc.matches()) {
            s = "~" + mc.group(1);
        }

        // 3a-bis. Compact "cYYYY" shorthand for circa, when the magnitude
        //   is unambiguously a year (abs(YYYY) >= 100). Smaller magnitudes
        //   are NOT preprocessed here:
        //     - 22 <= abs <= 99 is ambiguous (could be circa year or
        //       century N); DateTagTest.checkBaseOnly fires the unfixable
        //       variant before reaching this point.
        //     - abs <= 21 falls through to the existing CN century
        //       shorthand pipeline below (so "c19" still maps to "C19").
        Integer cYear = parseCShorthandYear(s);
        if (cYear != null && Math.abs(cYear) >= 100) {
            s = "~" + (cYear < 0 ? "-" : "") + String.format("%04d", Math.abs(cYear));
        }

        // 3b. "between X and Y" → "X..Y" so the existing range parser can
        //     handle it. Recurse through preprocess on each side so inner
        //     normalizations (circa, month names, etc.) still apply.
        Matcher mb = BETWEEN_RANGE.matcher(s);
        if (mb.matches()) {
            s = preprocess(mb.group(1)) + ".." + preprocess(mb.group(2));
        }

        // 3c. "first half of X" / "second half of X" — halfs of a year,
        //     decade, or century. Resolved to an explicit slash interval.
        //     The "half" handling is inline here (rather than going through
        //     toEdtf) because EDTF has no single-token form for it.
        Matcher mhalf = HALF_OF.matcher(s);
        if (mhalf.matches()) {
            String halfToken = mhalf.group(1).toLowerCase();
            boolean firstHalf = halfToken.equals("first") || halfToken.equals("1st");
            String tail = mhalf.group(2);
            String resolved = tryResolveHalfOf(tail, firstHalf);
            if (resolved != null) {
                s = resolved;
            }
        }

        // 3d. Written-date forms. Convert English month-name expressions to
        //     ISO. Returns early on successful rewrite.
        String written = tryParseWrittenDate(s);
        if (written != null) {
            s = written;
        }

        // 4. Normalize century variants to "CN" (N is the century number).
        //    Also handle trailing-position century forms after "early" / "mid"
        //    / "late" so things like "mid 2C" normalize to "mid C2".
        s = normalizeTrailingCentury(s);
        Matcher m = CENTURY_ORDINAL.matcher(s);
        if (m.matches()) {
            s = "C" + m.group(1);
        } else {
            m = CENTURY_VARIANT_CN.matcher(s);
            if (m.matches()) {
                s = "C" + m.group(1);
            } else {
                m = CENTURY_VARIANT_NC.matcher(s);
                if (m.matches()) {
                    s = "C" + m.group(1);
                }
            }
        }
        // Also handle "CN BC" / "NC BC" — we need to preserve the " BC" tail.
        // Re-apply century patterns only to the portion before " BC" if present.
        if (s.endsWith(" BC")) {
            String head = s.substring(0, s.length() - 3);
            Matcher mh = CENTURY_VARIANT_CN.matcher(head);
            if (mh.matches()) {
                s = "C" + mh.group(1) + " BC";
            } else {
                mh = CENTURY_VARIANT_NC.matcher(head);
                if (mh.matches()) {
                    s = "C" + mh.group(1) + " BC";
                } else {
                    mh = CENTURY_ORDINAL.matcher(head);
                    if (mh.matches()) {
                        s = "C" + mh.group(1) + " BC";
                    }
                }
            }
        }

        // 5. Slash date punctuation.
        //    YYYY/MM/DD — unambiguous since the first component is 4 digits.
        Matcher ymd = SLASH_DATE_YMD.matcher(s);
        if (ymd.matches()) {
            s = ymd.group(1) + "-"
                + pad2(ymd.group(2)) + "-"
                + pad2(ymd.group(3));
        } else {
            // MM/DD/YYYY — only unambiguous if one of the first two > 12,
            // or if they're equal (in which case both interpretations give
            // the same result).
            Matcher mdy = SLASH_DATE_MDY.matcher(s);
            if (mdy.matches()) {
                int a = Integer.parseInt(mdy.group(1));
                int b = Integer.parseInt(mdy.group(2));
                String year = mdy.group(3);
                if (a == b && a <= 12) {
                    // MM and DD equal — both readings collapse to the same date.
                    s = year + "-" + pad2(String.valueOf(a)) + "-" + pad2(String.valueOf(b));
                } else if (a > 12 && b <= 12) {
                    // a must be day, b must be month (DD/MM/YYYY)
                    s = year + "-" + pad2(String.valueOf(b)) + "-" + pad2(String.valueOf(a));
                } else if (b > 12 && a <= 12) {
                    // a must be month, b must be day (MM/DD/YYYY)
                    s = year + "-" + pad2(String.valueOf(a)) + "-" + pad2(String.valueOf(b));
                }
                // Else genuinely ambiguous — leave as-is and let parsing fail;
                // the validator will report it as unparseable.
            }
        }

        // 6. Two-component slash ranges (unambiguous because both parts have
        //    ≥3 digits and neither is a month/day). Convert to "..".
        //    This must come AFTER the slash-date handling above, otherwise we'd
        //    try to parse "1850/03/15" as a range.
        Matcher sr = SLASH_RANGE_2.matcher(s);
        if (sr.matches()) {
            s = sr.group(1) + ".." + sr.group(2);
        }

        // 6b. Bracket-enclosed hyphen range: "[YYYY-ZZZZ]". Strip the brackets
        //     so HYPHEN_RANGE_YY (step 7 below) can normalise the result.
        Matcher br = BRACKET_HYPHEN_RANGE.matcher(s);
        if (br.matches()) {
            s = br.group(1) + "-" + br.group(2);
        }

        // 6c. Double-bracket-enclosed dotdot range: "[[date1..date2]]".
        //     Strip the wrappers (and any whitespace immediately inside them)
        //     so the standard RANGE branch in toEdtf can normalise the inner.
        //     The RANGE branch recurses on each side and validates each as a
        //     date; if either is unparseable the whole value falls through
        //     to the unfixable path. Examples that this enables:
        //       "[[1900..1950]]"        → "1900/1950"
        //       "[[1900-05..1950-08]]"  → "1900-05/1950-08"
        s = s.replaceAll("^\\[\\[\\s*(.+\\.\\..+?)\\s*\\]\\]$", "$1");

        // 7. Hyphen-as-range-separator between two 4-digit years (e.g.
        //    "1850-1900"). Safe to interpret as a range because no valid
        //    ISO year-month has a 4-digit month. We require both parts to
        //    be 4 digits so we don't mangle legitimate year-month inputs
        //    like "1850-03".
        Matcher hr = HYPHEN_RANGE_YY.matcher(s);
        if (hr.matches()) {
            s = hr.group(1) + ".." + hr.group(2);
        }

        // 7a. Qualified hyphen range: "~1848-1854" or "?47-50". Rewrite
        //     so the qualifier moves to the end of the left bound and the
        //     hyphen becomes ".." — the standard RANGE branch then picks
        //     each side up and propagates the qualifier to the start.
        Matcher hrq = QUALIFIED_HYPHEN_RANGE.matcher(s);
        if (hrq.matches()) {
            s = hrq.group(2) + hrq.group(1) + ".." + hrq.group(3);
        }

        // 8. Open-ended range indicators expressed with "..". Normalize
        //    trailing ".." to trailing "/", and leading ".." to leading "/".
        //    This catches things like "1958..", "..1900".
        //
        //    Note: a trailing single "-" like "2021-" is NOT auto-converted
        //    here. It's too ambiguous — could be a typo for "2021", an
        //    incomplete input, or the user meaning "2021/". The validator
        //    flags it separately as ambiguous with no autofix.
        if (s.endsWith("..")) {
            s = s.substring(0, s.length() - 2) + "/";
        }
        if (s.startsWith("..")) {
            s = "/" + s.substring(2);
        }

        // 8a. Open-ended range indicators expressed with a single ".".
        //    Same intent as step 8 but with a single dot — e.g. ".1900" and
        //    "1900.". Rewritten to "/1900" / "1900/" so the rest of the
        //    pipeline (and the EDTF parser downstream) sees a valid
        //    open-ended interval.
        //
        //    Anchored on the whole value: only fires when the dot is the
        //    sole leading/trailing character before/after a clean
        //    YYYY[-MM[-DD]] body. Internal ".." (the standard OHM range
        //    form, e.g. "1900..1950") is untouched.
        s = s.replaceAll("^\\.(\\d{4}(?:-\\d\\d(?:-\\d\\d)?)?)$", "/$1");
        s = s.replaceAll("^(\\d{4}(?:-\\d\\d(?:-\\d\\d)?)?)\\.$", "$1/");

        // Strip junk-tail after a trailing slash: "/..", "/..~", "/.~",
        // "/~". These typically arise from incomplete edits where the user
        // meant just an open-ended "/" (e.g. "1959/..~" → "1959/").
        s = s.replaceAll("/[.~]+$", "/");
        // Collapse runs of `/` introduced when the `..` → `/` rewrite above
        // lands adjacent to an existing slash (e.g. "../871" → "//871",
        // "1850/.." → "1850//"). Genuine EDTF never has `//` so this is
        // safe.
        if (s.contains("//")) {
            s = s.replaceAll("/+", "/");
        }

        // 8b. X-form decade with early/mid/late modifier: "mid 197X",
        //     "early 18X". Rewrite to the equivalent YY[Y]0s form so the
        //     existing THIRD_DECADE pattern in toEdtf can match. The X is
        //     accepted in either case; the modifier is also case-insensitive
        //     (matches the rest of the early/mid/late family).
        s = s.replaceAll(
            "(?i)^([~?%]?)(early|mid|late)\\s+(\\d+)[Xx]( BCE?)?$",
            "$1$2 $30s$4");

        // 9. Uppercase lowercase 'x' in X-forms (e.g. "185x" → "185X").
        //    We do this last because the other preprocessing steps produce
        //    canonical forms we don't want to disturb, and X-forms don't
        //    appear inside them.
        if (s.matches("^-?\\d{2,3}x{1,2}$")) {
            s = s.replace('x', 'X');
        }

        return s;
    }

    private static String pad2(String n) {
        return n.length() == 1 ? "0" + n : n;
    }

    /**
     * Normalize the inner date of a before/by/as-of/after expression.
     * Returns the form to embed in the slash interval, or {@code null} if
     * the inner is not a recognized format.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Strict ISO ({@code YYYY}, {@code YYYY-MM}, {@code YYYY-MM-DD}):
     *       preserved at full precision.</li>
     *   <li>Dash-separated DM/MD/Y ({@code 01-01-1882}, {@code 12-31-1999}):
     *       coarsened to year only because day/month order is ambiguous.</li>
     *   <li>Anything else: recurse into {@link #toEdtf} so OHM shorthand on
     *       the bound side normalizes too. Handles {@code before C12 → /11XX},
     *       {@code by c1900 → /1900~}, {@code as of 1850s → /185X}, etc.
     *       Only accepted if the recursive result is itself valid EDTF, to
     *       avoid feeding garbage into the slash interval.</li>
     * </ol>
     */
    private static String beforeAfterInner(String s) {
        if (STRICT_ISO_FOR_BEFORE_AFTER.matcher(s).matches()) return s;
        Matcher m = DAY_MONTH_YEAR_DASHED.matcher(s);
        if (m.matches()) return m.group(1);
        Optional<String> recursed = toEdtf(s);
        if (recursed.isPresent() && looksLikeValidEdtf(recursed.get())) {
            return recursed.get();
        }
        return null;
    }

    /**
     * "N - N BCE" spaced-hyphen range (e.g. {@code 5 - 1 BCE}). Must be
     * detected BEFORE the whitespace-collapse step strips the spaces and
     * turns it into {@code 5-1 BCE}, which nothing matches. Rewritten in
     * preprocess to {@code N..N BCE} so standard BCE normalization applies.
     * Captures (1) left number, (2) right number (trailing BCE is consumed).
     */
    private static final Pattern SPACED_HYPHEN_BCE_RANGE =
        Pattern.compile("^(-?\\d{1,4}) - (-?\\d{1,4})\\s+(?i:B\\.?C\\.?E?\\.?)$");

    /**
     * Bracket-enclosed year range: {@code [YYYY-ZZZZ]}. The brackets are
     * stripped in preprocess so {@link #HYPHEN_RANGE_YY} can handle the
     * result. Either year may carry a leading minus sign.
     */
    private static final Pattern BRACKET_HYPHEN_RANGE =
        Pattern.compile("^\\[(-?\\d{4})-(-?\\d{4})\\]$");

    /**
     * Matches two 4-digit years separated by a hyphen (a likely range).
     * Either or both years may carry a leading minus sign for astronomical
     * BCE notation (e.g. {@code -0800--0600} after preprocess strips the
     * spaces in {@code -0800 - -0600}).
     */
    private static final Pattern HYPHEN_RANGE_YY =
        Pattern.compile("^(-?\\d{4})-(-?\\d{4})$");

    /**
     * Hyphen range with leading qualifier ({@code ~1848-1854},
     * {@code ?47-50}). Captures (1) qualifier, (2) left year (1-4 digits,
     * optional negative), (3) right year. Rewritten in {@link #preprocess}
     * to {@code <left><qualifier>..<right>} so the qualifier propagates to
     * the start side via the standard RANGE branch in {@link #toEdtf}.
     */
    private static final Pattern QUALIFIED_HYPHEN_RANGE =
        Pattern.compile("^([~?%])(-?\\d{1,4})-(-?\\d{1,4})$");

    /**
     * "end of YYYY" / "end of YYYY BC" — collapses to year-month December
     * ({@code 1955-12}). The "end of" prefix narrows the year to its last
     * month. Symmetric handlers for {@code beginning of} and {@code mid of}
     * could be added similarly if/when needed.
     */
    private static final Pattern END_OF_YEAR =
        Pattern.compile("^(?i)end of\\s+(-?\\d{4})(\\s+BC)?$");

    /**
     * Convert the given OHM/OSM-format date string to EDTF.
     *
     * @param osm input date string; may be null
     * @return the EDTF string, or empty if the input cannot be parsed
     */
    public static Optional<String> toEdtf(String osm) {
        if (osm == null) return Optional.empty();
        osm = preprocess(osm);
        if (osm == null || osm.isEmpty()) return Optional.empty();

        // --- Notation choice: slash vs. bracket for intervals --------------
        //
        // EDTF offers two ways to express a range of dates:
        //
        //   Slash (Level 0):  A/B       means "interval from A to B"
        //   Bracket (Level 1): [A..B]   means "one unknown date between A and B"
        //
        // These have different semantics — slash is a continuous interval,
        // bracket is set notation expressing a single-but-unknown date within
        // the span. Historically this code emitted brackets for unqualified
        // ranges and slash only when qualifiers were present (because EDTF
        // set notation is incompatible with qualifiers like ~ ? %).
        //
        // We now emit slash notation uniformly throughout this normalizer,
        // including open-ended intervals ("/1900", "1850/"):
        //
        //   - Readability: A/B is terser and easier to scan than [A..B].
        //   - Consistency: avoids having two output formats that differ
        //     only by whether any qualifier happens to appear.
        //   - Semantics: for start_date / end_date tags, the surrounding key
        //     already implies interval semantics ("the start of a lifespan"),
        //     which matches the slash reading better than the set reading.
        //   - Parser support: slash (Level 0) is more universally supported
        //     than bracket/set notation (Level 1) across lightweight EDTF
        //     libraries.
        //
        // The bound-extraction helpers (lowerBoundOf / upperBoundOf) still
        // parse both notations, defensively, for callers that pass in
        // pre-existing bracket-form EDTF.

        // --- Ordinal-century range: "5th - mid 8th Century" --------------
        // Has to come before the generic RANGE branch because the input
        // doesn't contain `..` — the dash is the range separator and the
        // trailing "Century" word applies to both sides. Each side is
        // recursively normalized as a CN expression (with optional
        // early/mid/late modifier and BCE suffix), and the bounds combined.
        Matcher m = ORDINAL_CENTURY_RANGE.matcher(osm);
        if (m.matches()) {
            String leftMod = m.group(1);
            String leftN = m.group(2);
            String rightMod = m.group(3);
            String rightN = m.group(4);
            String bcSuffix = m.group(5) == null ? "" : m.group(5);

            String leftKey = (leftMod == null ? "" : leftMod + " ")
                + "C" + leftN + bcSuffix;
            String rightKey = (rightMod == null ? "" : rightMod + " ")
                + "C" + rightN + bcSuffix;

            String leftEdtf = toEdtf(leftKey).orElse(null);
            String rightEdtf = toEdtf(rightKey).orElse(null);
            if (leftEdtf != null && rightEdtf != null) {
                // Resolve to plain ISO bounds so the resulting interval is
                // precise on both sides (e.g. "5th Century" → "04XX" → "0400";
                // "mid 8th Century" → "0730~/0770~" → "0770"). The bracket
                // form would otherwise leak the X-form / qualifier into the
                // outer interval.
                String low = lowerBoundIso(leftEdtf).orElse(null);
                String high = upperBoundIso(rightEdtf).orElse(null);
                if (low != null && !low.isEmpty()
                    && high != null && !high.isEmpty()) {
                    return Optional.of(low + "/" + high);
                }
            }
            return Optional.empty();
        }

        // --- Ambiguous tail: YYYY-MM..MM with both sides valid months ----
        //   Refuse to normalize; the validator's dedicated rule
        //   (CODE_AMBIGUOUS_MONTH_YEAR_TAIL) emits the unfixable warning
        //   explaining the two valid interpretations.
        if (YYYY_MM_DOTDOT_MONTH_TAIL_AMBIGUOUS.matcher(osm).matches()) {
            return Optional.empty();
        }

        // --- Range: A..B --------------------------------------------------
        m = RANGE.matcher(osm);
        if (m.matches()) {
            String start = m.group(1);
            String end = m.group(2);
            String bc = m.group(3) == null ? "" : m.group(3);

            // The trailing " BC" capture (group 3) is meant to distribute to
            // both bounds when the user writes a global suffix like
            // "1500..1700 BC". But when each bound already carries its own
            // BCE marker (e.g. "182 BC..174 BC"), the right side captured
            // " BC" only attaches to the end and would corrupt the start
            // ("182 BC BC") if appended unconditionally. Skip the append on
            // either side that already ends with a BCE-style marker.
            String startWithBc = start.matches("(?i).*\\sBC$") ? start : start + bc;
            String endWithBc   = end.matches("(?i).*\\sBC$")   ? end   : end + bc;

            String startEdtf = toEdtf(startWithBc).orElse(null);
            if (startEdtf != null) {
                startEdtf = lowerBoundOf(startEdtf);
            }
            String endEdtf = toEdtf(endWithBc).orElse(null);
            if (endEdtf != null) {
                endEdtf = upperBoundOf(endEdtf);
            }

            if (startEdtf != null && !startEdtf.isEmpty()
                && endEdtf != null && !endEdtf.isEmpty()) {
                // Slash notation — see header comment on toEdtf().
                return Optional.of(startEdtf + "/" + endEdtf);
            }
            // Recursion on one or both sides failed. Fall through to
            // subsequent patterns and the final valid-EDTF passthrough —
            // this lets bracket-set forms like "[1907..]" pass through
            // unchanged even though RANGE's `(.+)\\.\\.(.+?)` greedily
            // bites off the brackets and fails to recurse on the parts.
        }

        // --- Short-year range with optional qualifier ---------------------
        // "~47-50" / "47-50". Must come BEFORE SIMPLE_YEAR — otherwise that
        // pattern would parse "47-50" as year=47/month=50 (an invalid date
        // that also fails looksLikeValidEdtf, so the autofix would never
        // surface). Only fires when the right side is too large to be a
        // valid month (>12), or a qualifier is present (which signals year
        // semantics over month-day); ambiguous cases fall through.
        m = QUALIFIED_SHORT_YEAR_RANGE.matcher(osm);
        if (m.matches()) {
            String qualifier = m.group(1) == null ? "" : m.group(1);
            int startYear = Integer.parseInt(m.group(2));
            int endYear = Integer.parseInt(m.group(3));
            if (!qualifier.isEmpty() || endYear > 12) {
                return Optional.of(padYear(startYear) + qualifier
                                   + "/" + padYear(endYear));
            }
            // else fall through — could be year-month, let SIMPLE_YEAR try.
        }

        // --- Plain or approximate year (optionally with month/day, BCE) ---
        m = SIMPLE_YEAR.matcher(osm);
        if (m.matches()) {
            String prefixQual = m.group(1);          // ~, ?, or %
            String year       = m.group(2);          // may start with "-"
            String monthDay   = m.group(3) == null ? "" : m.group(3);
            String suffixQual = m.group(4);          // ~, ?, or %
            String bc         = m.group(5);

            // Choose the qualifier to emit. Prefer whichever the user supplied.
            // If both somehow appeared (shouldn't, given the regex), prefer
            // suffix since that's closer to EDTF output form.
            String qualifier = "";
            if (suffixQual != null) {
                qualifier = suffixQual;
            } else if (prefixQual != null) {
                qualifier = prefixQual;
            }

            // Year handling. Three cases:
            //   (a) explicit " BC" suffix: astronomical year = -(N - 1)
            //   (b) leading "-" on the number (astronomical form): use as-is
            //   (c) plain positive number: use as-is
            if (bc != null) {
                if (year.startsWith("-")) {
                    // Conflicting signals — the user wrote "-500 BC" or similar.
                    // Pick one interpretation: honor the explicit " BC" marker
                    // and ignore the leading "-". This matches the likely intent.
                    year = year.substring(1);
                }
                int y = Integer.parseInt(year) - 1;
                // y=0 means 1 BCE = astronomical year 0; "-0000" is not valid EDTF.
                year = (y == 0) ? padYear(0) : "-" + padYear(y);
            } else if (year.startsWith("-")) {
                int y = Integer.parseInt(year.substring(1));
                year = "-" + padYear(y);
            } else {
                year = padYear(Integer.parseInt(year));
            }
            return Optional.of(year + monthDay + qualifier);
        }

        // --- Decade: YYYY0s -----------------------------------------------
        m = DECADE.matcher(osm);
        if (m.matches()) {
            String prefixQ = m.group(1) == null ? "" : m.group(1);
            int decade = Integer.parseInt(m.group(2));
            String suffixQ = m.group(3) == null ? "" : m.group(3);
            String bc = m.group(4);
            String qualifier = !suffixQ.isEmpty() ? suffixQ : prefixQ;

            if (bc == null) {
                // CE decade. Two output shapes:
                //   No qualifier: EDTF unspecified-digit form (e.g. 1850s → 185X).
                //   Qualified:    explicit range with qualifier on each bound
                //                 (e.g. ~1850s → 1850~/1859~), because EDTF
                //                 rejects qualifiers attached to X-forms
                //                 (185X~ / ~185X are not parseable).
                if (qualifier.isEmpty()) {
                    return Optional.of(padDecade(decade) + "X");
                }
                int startYear = decade * 10;
                int endYear = decade * 10 + 9;
                return Optional.of(padYear(startYear) + qualifier
                                   + "/" + padYear(endYear) + qualifier);
            }
            // BCE decade: emit a rounded range rather than the astronomically
            // "correct" off-by-one bounds used by the upstream JS. For example
            // 1850s BCE → -1859/-1850 rather than -1858/-1849.
            //
            // EDTF astronomical year numbering includes a year 0, so
            // "1850s BCE" (1859–1850 BCE in human terms) maps astronomically
            // to -1858 through -1849 — an off-by-one shift that is technically
            // defensible but confusing at a glance. Round boundaries match
            // reader intuition and probable author intent. Deliberate
            // divergence from JS parity.
            int startYear = decade * 10 + 9;   // e.g. 1850s → 1859
            int endYear = decade * 10;         // e.g. 1850s → 1850
            return Optional.of("-" + padYear(startYear) + qualifier
                               + "/-" + padYear(endYear) + qualifier);
        }

        // --- Century: CNN -------------------------------------------------
        m = CENTURY.matcher(osm);
        if (m.matches()) {
            String prefixQ = m.group(1) == null ? "" : m.group(1);
            int century = Integer.parseInt(m.group(2));
            String suffixQ = m.group(3) == null ? "" : m.group(3);
            String bc = m.group(4);
            String qualifier = !suffixQ.isEmpty() ? suffixQ : prefixQ;

            if (bc == null) {
                // CE century. Plain → XX-form (e.g. C19 → 18XX).
                // Qualified → range (~C19 / C19~ → 1800~/1899~), because
                // EDTF rejects qualifiers attached to XX-forms.
                if (qualifier.isEmpty()) {
                    return Optional.of(padCentury(century - 1) + "XX");
                }
                int startYear = (century - 1) * 100;
                int endYear = (century - 1) * 100 + 99;
                return Optional.of(padYear(startYear) + qualifier
                                   + "/" + padYear(endYear) + qualifier);
            }
            // BCE century: emit a rounded range rather than the astronomically
            // "correct" off-by-one bounds used by the upstream JS. For example
            // C6 BC → -0599/-0500 rather than -0598/-0499.
            //
            // Why this is not precisely correct:
            //   EDTF uses astronomical year numbering, which includes a year 0.
            //   So 1 BCE = astronomical 0, 2 BCE = -1, ..., 600 BCE = -599.
            //   The 6th century BCE (600–501 BCE) is therefore astronomically
            //   -599 through -500, and the upstream JS shifts all BCE boundaries
            //   by one to preserve that exact correspondence.
            //
            // Why we do it this way anyway:
            //   The input "C6 BC" is a loose human label meaning "the 500s BCE."
            //   A reader eyeballing the normalized tag expects boundaries that
            //   match how historians talk — round hundreds, not hundreds shifted
            //   by one. The off-by-one is technically defensible but practically
            //   confusing, and the `~` qualifier (when present) already signals
            //   that these boundaries are approximate anyway.
            //
            // We intentionally diverge from the JS here, trading astronomical
            // precision for readability and probable author intent.
            int startYear = century * 100 - 1;       // e.g. C6  → 599
            int endYear = (century - 1) * 100;       // e.g. C6  → 500
            return Optional.of("-" + padYear(startYear) + qualifier
                               + "/-" + padYear(endYear) + qualifier);
        }

        // --- early/mid/late decade ----------------------------------------
        m = THIRD_DECADE.matcher(osm);
        if (m.matches()) {
            // group(1) is an optional leading qualifier (~ ? %); the output
            // already carries `~` on each bound (THIRD_DECADE means
            // approximate by definition), so the input qualifier is consumed
            // for free and not re-emitted.
            String third = m.group(2).toLowerCase();
            int decade = Integer.parseInt(m.group(3));
            boolean bc = m.group(4) != null;
            int[] offsets = offsetsForDecadeThird(third);

            int startYear = decade * 10 + offsets[bc ? 1 : 0];
            int endYear = decade * 10 + offsets[bc ? 0 : 1];
            if (bc) {
                startYear++;
                endYear++;
                return Optional.of("-" + padYear(startYear) + "~/-" + padYear(endYear) + "~");
            }
            return Optional.of(padYear(startYear) + "~/" + padYear(endYear) + "~");
        }

        // --- early/mid/late century ---------------------------------------
        m = THIRD_CENTURY.matcher(osm);
        if (m.matches()) {
            // See THIRD_DECADE above: optional leading qualifier in group(1)
            // is consumed; output already carries `~` on each bound.
            String third = m.group(2).toLowerCase();
            int century = Integer.parseInt(m.group(3)) - 1;
            boolean bc = m.group(4) != null;
            int[] offsets = offsetsForCenturyThird(third);

            int startYear = century * 100 + offsets[bc ? 1 : 0];
            int endYear = century * 100 + offsets[bc ? 0 : 1];
            if (bc) {
                startYear++;
                endYear++;
                return Optional.of("-" + padYear(startYear) + "~/-" + padYear(endYear) + "~");
            }
            return Optional.of(padYear(startYear) + "~/" + padYear(endYear) + "~");
        }

        // --- early/mid/late YYYY or early/mid/late YYYY-MM ----------------
        //   Splits a year or a month into thirds. Year-thirds use a
        //   four-month bucket on each side; month-thirds use a ten-day
        //   bucket on most months and a nine-day bucket on February so all
        //   three thirds fit within Feb's 28/29 days. Output is a clean
        //   slash interval at day precision; no qualifier on the bounds
        //   (the interval itself already expresses the approximation).
        //
        //   BCE: the year is converted via astronomical = -(BC - 1) so
        //   "100 BC" becomes -0099, "1 BC" becomes 0000. Month/day buckets
        //   are the same regardless of sign — they describe the position
        //   within the named year/month, not direction in time.
        m = THIRD_PARTIAL_YEAR_OR_MONTH.matcher(osm);
        if (m.matches()) {
            String thirdLc = m.group(1).toLowerCase();
            int yearInt = Integer.parseInt(m.group(2));
            String monthStr = m.group(3);  // null if year-only form
            boolean bc = m.group(4) != null;
            String yearStr;
            int leapYearTest;
            if (bc) {
                int astro = 1 - yearInt;        // 1 BC → 0; 100 BC → -99
                yearStr = astro < 0
                    ? "-" + padYear(-astro)
                    : padYear(astro);
                leapYearTest = astro;            // proleptic Gregorian leap year on astronomical
            } else {
                yearStr = padYear(yearInt);
                leapYearTest = yearInt;
            }
            if (monthStr == null) {
                // Year-thirds.
                String lo, hi;
                switch (thirdLc) {
                    case "early": lo = "01"; hi = "04"; break;
                    case "mid":   lo = "05"; hi = "08"; break;
                    default:      lo = "09"; hi = "12"; break;   // "late"
                }
                return Optional.of(yearStr + "-" + lo + "/" + yearStr + "-" + hi);
            }
            int monthInt = Integer.parseInt(monthStr);
            if (monthInt >= 1 && monthInt <= 12) {
                String loDay, hiDay;
                if (monthInt == 2) {
                    // Feb-special: 9-day / 10-day / variable late bucket.
                    switch (thirdLc) {
                        case "early": loDay = "01"; hiDay = "09"; break;
                        case "mid":   loDay = "10"; hiDay = "19"; break;
                        default:      loDay = "20"; hiDay = isLeapYear(leapYearTest) ? "29" : "28"; break;
                    }
                } else {
                    // Standard 10-day / 10-day / variable late bucket.
                    switch (thirdLc) {
                        case "early": loDay = "01"; hiDay = "10"; break;
                        case "mid":   loDay = "11"; hiDay = "20"; break;
                        default:      loDay = "21"; hiDay = monthEndDay(monthInt); break;
                    }
                }
                return Optional.of(yearStr + "-" + monthStr + "-" + loDay
                                 + "/" + yearStr + "-" + monthStr + "-" + hiDay);
            }
            // Invalid month — fall through.
        }

        // --- Natural-language season: "fall of 1814", "spring 1920" -------
        // Emit the EDTF season code (YYYY-23 etc.) directly. We don't
        // expand to a month-range interval because EDTF season codes exist
        // precisely to avoid that expansion — parsers should understand them.
        // Early/mid/late modifiers in the input are ignored; EDTF Level 2
        // has codes like -25..-28 for hemisphere-qualified seasons, but we
        // don't try to distinguish "early summer" from "mid summer" here.
        m = NATURAL_SEASON.matcher(osm);
        if (m.matches()) {
            String season = m.group(1).toLowerCase();
            int year = Integer.parseInt(m.group(2));
            boolean bc = m.group(3) != null;
            Integer code = SEASON_NAMES.get(season);
            if (code != null) {
                String yearStr;
                if (bc) {
                    // BCE: astronomical = -(N-1). "winter of 44 BC" → -0043-24.
                    yearStr = "-" + padYear(year - 1);
                } else {
                    yearStr = padYear(year);
                }
                return Optional.of(yearStr + "-" + code);
            }
        }

        // --- before/after -------------------------------------------------
        // Slash notation for open-ended intervals, matching the range branch.
        // "/1900" = interval ending at 1900 with unknown start;
        // "1850/"  = interval starting at 1850 with unknown end.
        m = BEFORE.matcher(osm);
        if (m.matches()) {
            String inner = beforeAfterInner(m.group(1));
            if (inner == null) return Optional.empty();
            return Optional.of("/" + inner);
        }
        m = AFTER.matcher(osm);
        if (m.matches()) {
            String inner = beforeAfterInner(m.group(1));
            if (inner == null) return Optional.empty();
            return Optional.of(inner + "/");
        }
        // "during X" — pure unwrap; the prefix adds no semantic content,
        // so the result is whatever toEdtf would have returned for X alone.
        m = DURING.matcher(osm);
        if (m.matches()) {
            return toEdtf(m.group(1));
        }

        // --- Open-ended slash-form intervals -----------------------------
        // Catches forms produced by the preprocess `..` → `/` rewrite,
        // e.g. "..1945-05-20" → "/1945-05-20", "1945-05-20.." → "1945-05-20/".
        // Recursive call normalizes the bounded side (year-padding,
        // qualifier reordering, cYYYY shorthand, etc.).
        m = LEADING_SLASH.matcher(osm);
        if (m.matches()) {
            return toEdtf(m.group(1)).map(s -> "/" + s);
        }
        m = TRAILING_SLASH.matcher(osm);
        if (m.matches()) {
            return toEdtf(m.group(1)).map(s -> s + "/");
        }

        // "end of YYYY" — collapse to year-month December (the last month
        // of the named year). Mirrors the THIRD_CENTURY-style "late C..."
        // handling but at year granularity.
        m = END_OF_YEAR.matcher(osm);
        if (m.matches()) {
            String year = m.group(1);
            boolean bc = m.group(2) != null;
            // Apply the existing N-1 BCE convention for individual years.
            if (bc) {
                int y = Integer.parseInt(year.startsWith("-") ? year.substring(1) : year) - 1;
                return Optional.of("-" + padYear(y) + "-12");
            }
            int y = year.startsWith("-") ? Integer.parseInt(year.substring(1)) : Integer.parseInt(year);
            return Optional.of((year.startsWith("-") ? "-" : "") + padYear(y) + "-12");
        }

        // Final passthrough: if the post-preprocess input is already valid
        // EDTF and no specific pattern produced output above, return it
        // unchanged. This catches forms the specific matchers don't cover
        // — bracket-set notation ({@code [1907..]}, {@code [196X]}),
        // X-form decade/century ({@code 192X}, {@code 18XX}), and
        // open-ended X-form intervals ({@code 192X/}). Without this,
        // recursing into LEADING_SLASH/TRAILING_SLASH on (e.g.) "192X/"
        // would fail because toEdtf("192X") returned empty.
        if (looksLikeValidEdtf(osm)) {
            return Optional.of(osm);
        }

        return Optional.empty();
    }

    // --- Public helpers for the validator -----------------------------------

    /**
     * Matches ISO 8601 calendar dates accepted by the OHM time-slider:
     * {@code YYYY}, {@code YYYY-MM}, or {@code YYYY-MM-DD}, optionally negated
     * (astronomical BCE notation).
     */
    private static final Pattern ISO_CALENDAR =
        Pattern.compile("^-?\\d{4}(?:-\\d{2}(?:-\\d{2})?)?$");

    /** Returns true if the value is a plain ISO 8601 calendar date. */
    public static boolean isIsoCalendarDate(String value) {
        return value != null && ISO_CALENDAR.matcher(value).matches();
    }

    /**
     * If the input is a Julian-calendar or Julian-day-number marker,
     * convert to a Gregorian ISO calendar date. Returns empty otherwise.
     *
     * <p>Supported forms:
     * <ul>
     *   <li>{@code j:YYYY-MM-DD} (or {@code j:YYYY}, {@code j:YYYY-MM}) —
     *       Julian calendar date. Converts by computing the Julian day
     *       number from the Julian-calendar Y/M/D, then converting that
     *       Julian day number back into a Gregorian Y/M/D.</li>
     *   <li>{@code jd:NNNNNNN} — Julian day number. Converts directly to
     *       Gregorian Y/M/D.</li>
     * </ul>
     *
     * <p>The conversion uses the Fliegel-Van Flandern algorithm (the
     * standard textbook algorithm for calendar conversion). Precision is
     * whole days; sub-day fractions in Julian day numbers are ignored.
     *
     * <p>Since EDTF has no standard way to express a Julian-calendar
     * date or a raw Julian day number, the caller should annotate the
     * conversion in a {@code :note} tag (per OHM wiki convention) and
     * skip writing {@code :edtf}.
     */
    /**
     * Parse OHM "cYYYY" compact-circa shorthand and return the signed year,
     * or null if the input isn't c-shorthand.
     *
     * <p>Accepts {@code cN}, {@code c-N}, {@code cN bc}, {@code c-N bc}, with
     * optional whitespace and any BCE-suffix variant accepted by
     * {@link #BCE_SUFFIX}. Case-insensitive on the leading {@code c}.
     *
     * <p>BCE flips the sign directly without an N-1 offset, so
     * {@code "c1920bc"} returns {@code -1920} (not {@code -1919}). This is
     * deliberate: the historian's astronomical-year convention
     * ({@code 1 BC = year 0}) is too persnickety for typical OHM editing,
     * and a direct flip matches what an OHM contributor writing
     * {@code c1920bc} actually means.
     *
     * <p>Callers decide what to do with the year by magnitude:
     * <ul>
     *   <li>{@code abs(year) >= 100}: unambiguous "circa year" — autofix
     *       to {@code ~YYYY} (the preprocessor handles this).</li>
     *   <li>{@code 22 <= abs(year) <= 99}: ambiguous between "circa year"
     *       and "century N" — DateTagTest fires an unfixable warning.</li>
     *   <li>{@code abs(year) <= 21}: highly probable century shorthand —
     *       falls through to the existing CN/YY00s normalization.</li>
     * </ul>
     */
    public static Integer parseCShorthandYear(String input) {
        if (input == null) return null;
        String s = input.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        // Strip any trailing BCE marker, recording its presence.
        boolean bce = false;
        Matcher bceM = BCE_SUFFIX.matcher(s);
        if (bceM.find()) {
            bce = true;
            s = s.substring(0, bceM.start()).trim();
        }
        Matcher cm = C_SHORTHAND.matcher(s);
        if (!cm.matches()) return null;
        try {
            int year = Integer.parseInt(cm.group(1));
            return bce ? -year : year;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Optional<String> tryConvertJulian(String value) {
        if (value == null) return Optional.empty();
        value = value.trim();

        Matcher m = JULIAN_DATE.matcher(value);
        if (m.matches()) {
            int year = Integer.parseInt(m.group(1));
            int month = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));

            // Rough sanity check on components.
            if (month < 1 || month > 12 || day < 1 || day > 31) {
                return Optional.empty();
            }

            long jdn = julianCalendarToJdn(year, month, day);
            int[] gregorian = jdnToGregorian(jdn);
            String output = padAstronomicalYear(gregorian[0]);
            if (m.group(2) != null) {
                output += "-" + pad2(String.valueOf(gregorian[1]));
            }
            if (m.group(3) != null) {
                output += "-" + pad2(String.valueOf(gregorian[2]));
            }
            return Optional.of(output);
        }

        m = JULIAN_DAY_NUMBER.matcher(value);
        if (m.matches()) {
            long jdn = Long.parseLong(m.group(1));
            int[] gregorian = jdnToGregorian(jdn);
            return Optional.of(padAstronomicalYear(gregorian[0]) + "-"
                + pad2(String.valueOf(gregorian[1])) + "-"
                + pad2(String.valueOf(gregorian[2])));
        }

        return Optional.empty();
    }

    /**
     * Convert a Julian-calendar date to a Julian day number. Year is
     * astronomical (0 = 1 BCE, -1 = 2 BCE, etc.).
     */
    private static long julianCalendarToJdn(int year, int month, int day) {
        // Standard formula. Valid for all years, astronomical numbering.
        int a = (14 - month) / 12;
        long y = (long) year + 4800L - a;
        long mm = (long) month + 12L * a - 3L;
        return day + (153L * mm + 2L) / 5L + 365L * y + y / 4L - 32083L;
    }

    /**
     * Convert a Julian day number to a Gregorian Y/M/D. Returns
     * {@code [year, month, day]} (astronomical year numbering).
     */
    private static int[] jdnToGregorian(long jdn) {
        // Fliegel-Van Flandern algorithm.
        long l = jdn + 68569L;
        long n = (4L * l) / 146097L;
        l = l - (146097L * n + 3L) / 4L;
        long i = (4000L * (l + 1L)) / 1461001L;
        l = l - (1461L * i) / 4L + 31L;
        long j = (80L * l) / 2447L;
        int day = (int) (l - (2447L * j) / 80L);
        l = j / 11L;
        int month = (int) (j + 2L - 12L * l);
        int year = (int) (100L * (n - 49L) + i + l);
        return new int[]{year, month, day};
    }

    /** Format an astronomical year for ISO output, preserving BCE negatives. */
    private static String padAstronomicalYear(int year) {
        if (year < 0) {
            return "-" + String.format("%04d", -year);
        }
        return String.format("%04d", year);
    }

    /**
     * Attempts to validate an EDTF expression. We use a lightweight heuristic
     * rather than a full EDTF parser: the string must round-trip through our
     * own normalizer, or already be in one of the forms this normalizer emits
     * (a plain ISO date, an XX/X unspecified-digit form, a slash interval, or
     * a qualifier suffix).
     *
     * <p>This is not a conformance test — it's a "did something reasonable
     * produce this value" check. False positives are possible (we may accept
     * strings a strict parser would reject) but false negatives — rejecting
     * output we emitted ourselves — should not occur.
     */
    /**
     * Returns true if {@code value} is canonical EDTF, as judged by the
     * upstream {@code edtf-java} library (single source of truth for the
     * EDTF grammar; tracks ISO 8601-2:2019 + amendments).
     *
     * <p>Empty / null input returns false. The library is tolerant of all
     * EDTF Level 0/1/2 forms relevant to OHM (plain ISO dates, BCE
     * negatives, X-digit decade forms like {@code 18XX} / {@code 185X},
     * qualifiers {@code ~?%}, slash and bracket-set intervals, season
     * codes 21–41, exponential-year notation, datetime with timezones).
     */
    public static boolean looksLikeValidEdtf(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Edtf.parse(value);
            return true;
        } catch (EdtfParseException e) {
            return false;
        }
    }

    /**
     * Extracts the lower bound of an EDTF expression as an ISO calendar date
     * suitable for the OHM time-slider's {@code start_date} tag.
     *
     * <p>For unbounded left ends (e.g. {@code /1900} representing "before
     * 1900"), this returns the only year mentioned. This is deliberately
     * lossy — "before 1900" technically has no lower bound — but the OHM
     * convention is to preserve the one specific year present in the input
     * so the time-slider shows something rather than treating the feature
     * as infinite-past. A future tool pass can refine these once the slider
     * supports EDTF directly.
     *
     * <p>EDTF qualifiers ({@code ~?%}) are stripped and season codes
     * ({@code YYYY-NN} where {@code NN} ∈ 21..41) are reduced to the bare
     * year, since the slider can't render either directly. The {@code :edtf}
     * sibling keeps the full precision for consumers that understand it.
     *
     * <p>Parsing is delegated to upstream {@code edtf-java}; OHM-specific
     * reductions (season → year, X-digit lower expansion) are applied on
     * top of the parsed structure.
     *
     * @return the bound, or empty if EDTF is empty / malformed
     */
    public static Optional<String> lowerBoundIso(String edtf) {
        return boundIso(edtf, false);
    }

    /**
     * Extracts the upper bound of an EDTF expression as an ISO calendar date
     * suitable for the OHM time-slider's {@code end_date} tag. Mirror of
     * {@link #lowerBoundIso(String)}.
     */
    public static Optional<String> upperBoundIso(String edtf) {
        return boundIso(edtf, true);
    }

    private static Optional<String> boundIso(String edtf, boolean upper) {
        if (edtf == null || edtf.isEmpty()) return Optional.empty();
        EdtfTemporal parsed;
        try {
            parsed = Edtf.parse(edtf);
        } catch (EdtfParseException e) {
            // edtf-java rejects X-digit forms combined with qualifiers
            // ({@code 18XX~}, {@code 18XX?}) — that's an EDTF Level 2 combo
            // it doesn't accept. The qualifier doesn't change the bound
            // semantics anyway, so strip a trailing qualifier and retry.
            String stripped = edtf.replaceAll("[~?%]+$", "");
            if (stripped.equals(edtf)) return Optional.empty();
            try {
                parsed = Edtf.parse(stripped);
            } catch (EdtfParseException e2) {
                return Optional.empty();
            }
        }
        return boundOf(parsed, upper);
    }

    /** Recursive bound extractor over a parsed {@link EdtfTemporal}. */
    private static Optional<String> boundOf(EdtfTemporal t, boolean upper) {
        if (t instanceof EdtfDate d) {
            return formatDateBound(d, upper);
        }
        if (t instanceof EdtfInterval iv) {
            // For OHM's time-slider, an open endpoint falls back to the
            // other (bounded) side — preserves the one year present rather
            // than reporting infinite-past / infinite-future.
            Endpoint primary = upper ? iv.upper() : iv.lower();
            Endpoint fallback = upper ? iv.lower() : iv.upper();
            if (primary instanceof Endpoint.Bounded b) return boundOf(b.value(), upper);
            if (fallback instanceof Endpoint.Bounded b) return boundOf(b.value(), upper);
            return Optional.empty();
        }
        if (t instanceof EdtfDecade dec) {
            int year = dec.firstYear() + (upper ? 9 : 0);
            return Optional.of(padAstronomicalYear(year));
        }
        if (t instanceof EdtfCentury c) {
            int year = c.firstYear() + (upper ? 99 : 0);
            return Optional.of(padAstronomicalYear(year));
        }
        if (t instanceof EdtfSeason s) {
            // OHM convention: reduce season codes to the bare year for the
            // base tag (the slider can't show seasons).
            return Optional.of(padAstronomicalYear(s.year()));
        }
        if (t instanceof EdtfSet set) {
            return setBound(set.members(), set.earlier(), set.later(), upper);
        }
        if (t instanceof EdtfList list) {
            return setBound(list.members(), list.earlier(), list.later(), upper);
        }
        // EdtfYear (Y-prefixed exponential / very-large years): use the
        // canonical string. Out of OHM time-slider's normal range; pass
        // through unchanged so the caller can decide.
        return Optional.of(t.toEdtfString());
    }

    /** Bound extraction for set / list members; mirrors interval semantics. */
    private static Optional<String> setBound(java.util.List<ListMember> members,
                                             boolean earlier, boolean later,
                                             boolean upper) {
        // OHM convention treats {@code [A..B]} as a range, with the
        // {@code earlier=true} ([..B]) and {@code later=true} ([A..])
        // flags marking the open ends — same fallback semantics as
        // EdtfInterval.
        if (members.isEmpty()) return Optional.empty();
        ListMember pick = upper ? members.get(members.size() - 1) : members.get(0);
        EdtfTemporal pickedValue;
        if (pick instanceof ListMember.Single single) {
            pickedValue = single.value();
        } else if (pick instanceof ListMember.Consecutive consec) {
            pickedValue = upper ? consec.end() : consec.start();
        } else {
            return Optional.empty();
        }
        // For open-set ends we can still produce a bound — the fallback
        // logic doesn't differ from a fully bounded set since the only
        // endpoint we have IS the one we want.
        if (upper && later) {
            // [A..]: upper bound falls back to A; pickedValue is already A.
        } else if (!upper && earlier) {
            // [..B]: lower bound falls back to B; pickedValue is already B.
        }
        return boundOf(pickedValue, upper);
    }

    /**
     * Format an {@link EdtfDate} bound at its native precision, applying
     * OHM-specific reductions: strip qualifiers ({@code ~?%}) and expand
     * X-digit unspecified-digit forms ({@code 18XX} → 1800/1899,
     * {@code 185X} → 1850/1859).
     */
    private static Optional<String> formatDateBound(EdtfDate d, boolean upper) {
        // Detect X-digit unspecified form via the canonical string —
        // EdtfDate normalises both lower and upper of an X-form to the
        // start year, so we can't distinguish bound directions through
        // the typed accessors alone.
        String canon = d.toEdtfString();
        String stripped = canon.replaceAll("[~?%]+$", "");
        if (stripped.matches("^-?\\d{2,3}X{1,2}$")) {
            boolean isNegative = stripped.startsWith("-");
            // For negative years, "earlier" (lower bound) = more negative = X→'9';
            // "later" (upper bound) = less negative = X→'0'. Reversed from positive.
            char xRepl = isNegative ? (upper ? '0' : '9') : (upper ? '9' : '0');
            return Optional.of(stripped.replace('X', xRepl));
        }
        // Otherwise format from the typed fields, preserving precision.
        int year = d.year();
        switch (d.precision()) {
            case YEAR:
                return Optional.of(padAstronomicalYear(year));
            case MONTH:
                return Optional.of(padAstronomicalYear(year) + "-" + pad2int(d.month()));
            case DAY:
            case MINUTE:
            case SECOND:
            case MILLISECOND:
            default:
                return Optional.of(padAstronomicalYear(year) + "-" + pad2int(d.month())
                                   + "-" + pad2int(d.day()));
        }
    }

    private static String pad2int(int n) {
        return String.format("%02d", n);
    }

    // --- Range-bound helpers (internal) -------------------------------------

    /** Given an EDTF expression, return its lower bound (start of range or the value itself). */
    private static String lowerBoundOf(String edtf) {
        // Interval notation with brackets: [A..B] → A
        if (edtf.startsWith("[") && edtf.endsWith("]")) {
            String inner = edtf.substring(1, edtf.length() - 1);
            int idx = inner.indexOf("..");
            if (idx >= 0) {
                String lower = inner.substring(0, idx);
                return lower.isEmpty() ? "" : lower;
            }
        }
        // Set/alternate notation: A/B → A (earliest)
        int slash = edtf.indexOf('/');
        if (slash >= 0) {
            return edtf.substring(0, slash);
        }
        return edtf;
    }

    /** Given an EDTF expression, return its upper bound (end of range or the value itself). */
    private static String upperBoundOf(String edtf) {
        if (edtf.startsWith("[") && edtf.endsWith("]")) {
            String inner = edtf.substring(1, edtf.length() - 1);
            int idx = inner.indexOf("..");
            if (idx >= 0) {
                String upper = inner.substring(idx + 2);
                return upper.isEmpty() ? "" : upper;
            }
        }
        int slash = edtf.indexOf('/');
        if (slash >= 0) {
            return edtf.substring(slash + 1);
        }
        return edtf;
    }

    // --- Padding helpers ----------------------------------------------------

    private static String padYear(int year) {
        return String.format("%04d", year);
    }

    private static String padDecade(int decade) {
        // decade like "185" should render as "0185" stub → "185X" overall
        return String.format("%03d", decade);
    }

    private static String padCentury(int century) {
        return String.format("%02d", century);
    }

    /** Gregorian leap-year test. */
    private static boolean isLeapYear(int year) {
        if (year % 4 != 0) return false;
        if (year % 100 != 0) return true;
        return year % 400 == 0;
    }

    /** Last day of month {@code 1..12}, excluding February (callers handle Feb). */
    private static String monthEndDay(int month) {
        // Jan, Mar, May, Jul, Aug, Oct, Dec = 31; Apr, Jun, Sep, Nov = 30.
        switch (month) {
            case 4: case 6: case 9: case 11: return "30";
            default: return "31";
        }
    }

    private static int[] offsetsForDecadeThird(String third) {
        // Non-overlapping splits of the 10-year decade (3 / 4 / 3 years).
        switch (third) {
            case "early": return new int[]{0, 2};
            case "mid":   return new int[]{3, 6};
            case "late":  return new int[]{7, 9};
            default: throw new IllegalArgumentException(third);
        }
    }

    private static int[] offsetsForCenturyThird(String third) {
        // Non-overlapping splits of the 100-year century (30 / 40 / 30 years).
        switch (third) {
            case "early": return new int[]{0, 29};
            case "mid":   return new int[]{30, 69};
            case "late":  return new int[]{70, 99};
            default: throw new IllegalArgumentException(third);
        }
    }
}
