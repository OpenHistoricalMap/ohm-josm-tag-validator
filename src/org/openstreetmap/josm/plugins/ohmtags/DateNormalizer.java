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
     * Simple year with optional qualifier. Qualifier may be prefix ({@code ~},
     * {@code ?}, or {@code %}) or suffix ({@code ?}, {@code %} — a trailing
     * {@code ~} would conflict with BCE suffix handling, so {@code ~} is
     * only accepted as prefix). Accepts both positive years and astronomical
     * negative years ({@code -180}, {@code ~-180}).
     */
    private static final Pattern SIMPLE_YEAR =
        Pattern.compile("^([~?%])?(-?\\d+)(-\\d\\d(?:-\\d\\d)?)?([?%])?( BCE?)?$");

    private static final Pattern DECADE =
        Pattern.compile("^(~)?(\\d+)0s( BCE?)?$");

    private static final Pattern CENTURY =
        Pattern.compile("^(~)?C(\\d+)( BCE?)?$");

    private static final Pattern THIRD_DECADE =
        Pattern.compile("^(early|mid|late) (\\d+)0s( BCE?)?$");

    private static final Pattern THIRD_CENTURY =
        Pattern.compile("^(early|mid|late) C(\\d+)( BCE?)?$");

    private static final Pattern BEFORE =
        Pattern.compile("^before (\\d{4}(?:-\\d\\d)?(?:-\\d\\d)?)$");

    private static final Pattern AFTER =
        Pattern.compile("^after (\\d{4}(?:-\\d\\d)?(?:-\\d\\d)?)$");

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
     * <p>Uses a lookbehind to require either whitespace or a digit before
     * the suffix, so this doesn't accidentally match inside random words.
     */
    private static final Pattern BCE_SUFFIX =
        Pattern.compile("(?i)(?<=[\\d\\s])\\s*b\\.?\\s*c\\.?e?\\.?$");

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
     * EDTF sub-year "season" codes. EDTF Level 1 defines four season codes
     * appended to a year with a hyphen:
     * <ul>
     *   <li>{@code -21} spring, {@code -22} summer, {@code -23} autumn/fall,
     *       {@code -24} winter</li>
     * </ul>
     * EDTF Level 2 extends this to further sub-year groupings:
     * <ul>
     *   <li>{@code -25}-{@code -28}: hemisphere-qualified seasons (N spring,
     *       N summer, N autumn, N winter)</li>
     *   <li>{@code -29}-{@code -32}: additional hemisphere qualifiers</li>
     *   <li>{@code -33}-{@code -36}: quarters (Q1, Q2, Q3, Q4)</li>
     *   <li>{@code -37}-{@code -39}: quadrimesters (tertiles)</li>
     *   <li>{@code -40}-{@code -41}: semesters (first/second half)</li>
     * </ul>
     *
     * <p>Accepts an optional trailing qualifier ({@code ~}, {@code ?}, {@code %}).
     * Accepts astronomical negative years.
     */
    private static final Pattern EDTF_SEASON_CODE =
        Pattern.compile("^(-?\\d{4})-(2[1-9]|3[0-9]|4[01])([~?%])?$");

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

        // Strip whitespace adjacent to hyphens, dots (EDTF range ..), slashes.
        s = s.replaceAll("\\s*-\\s*", "-");
        s = s.replaceAll("\\s*\\.\\.\\s*", "..");
        s = s.replaceAll("\\s*/\\s*", "/");

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

        // 7. Hyphen-as-range-separator between two 4-digit years (e.g.
        //    "1850-1900"). Safe to interpret as a range because no valid
        //    ISO year-month has a 4-digit month. We require both parts to
        //    be 4 digits so we don't mangle legitimate year-month inputs
        //    like "1850-03".
        Matcher hr = HYPHEN_RANGE_YY.matcher(s);
        if (hr.matches()) {
            s = hr.group(1) + ".." + hr.group(2);
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

    /** Matches two 4-digit years separated by a hyphen (a likely range). */
    private static final Pattern HYPHEN_RANGE_YY =
        Pattern.compile("^(\\d{4})-(\\d{4})$");

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

        // --- Range: A..B --------------------------------------------------
        Matcher m = RANGE.matcher(osm);
        if (m.matches()) {
            String start = m.group(1);
            String end = m.group(2);
            String bc = m.group(3) == null ? "" : m.group(3);

            String startEdtf = toEdtf(start + bc).orElse(null);
            if (startEdtf != null) {
                startEdtf = lowerBoundOf(startEdtf);
            }
            String endEdtf = toEdtf(end + bc).orElse(null);
            if (endEdtf != null) {
                endEdtf = upperBoundOf(endEdtf);
            }

            if (startEdtf != null && !startEdtf.isEmpty()
                && endEdtf != null && !endEdtf.isEmpty()) {
                // Slash notation — see header comment on toEdtf().
                return Optional.of(startEdtf + "/" + endEdtf);
            }
            return Optional.empty();
        }

        // --- Plain or approximate year (optionally with month/day, BCE) ---
        m = SIMPLE_YEAR.matcher(osm);
        if (m.matches()) {
            String prefixQual = m.group(1);          // ~, ?, or %
            String year       = m.group(2);          // may start with "-"
            String monthDay   = m.group(3) == null ? "" : m.group(3);
            String suffixQual = m.group(4);          // ? or % (~ reserved for prefix only)
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
                year = "-" + padYear(y);
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
            String circa = m.group(1) == null ? "" : m.group(1);
            int decade = Integer.parseInt(m.group(2));
            String bc = m.group(3);

            if (bc == null) {
                // EDTF unspecified-digit form: e.g. 185X
                //
                // Note: we deliberately keep the XX / X shorthand for CE decades
                // and centuries even though BCE uses explicit ranges below. This
                // means the validator's output format differs by era — CE stays
                // compact and readable (185X, 18XX), while BCE gets a range
                // (-1859/-1850, -0599/-0500). We accept the inconsistency because
                // the XX shorthand is clear and idiomatic for CE dates, and the
                // majority of OHM tags are CE; switching CE to ranges purely for
                // symmetry would make common output noisier for no real gain.
                return Optional.of(padDecade(decade) + "X" + circa);
            }
            // BCE decade: emit a rounded range rather than the astronomically
            // "correct" off-by-one bounds used by the upstream JS. For example
            // 1850s BCE → -1859/-1850 rather than -1858/-1849.
            //
            // Same reasoning as the BCE century branch: EDTF astronomical year
            // numbering includes a year 0, so "1850s BCE" (1859–1850 BCE in
            // human terms) maps astronomically to -1858 through -1849 — an
            // off-by-one shift that is technically defensible but confusing at
            // a glance. Round boundaries match reader intuition and probable
            // author intent. Deliberate divergence from JS parity.
            int startYear = decade * 10 + 9;   // e.g. 1850s → 1859
            int endYear = decade * 10;         // e.g. 1850s → 1850
            return Optional.of("-" + padYear(startYear) + circa
                               + "/-" + padYear(endYear) + circa);
        }

        // --- Century: CNN -------------------------------------------------
        m = CENTURY.matcher(osm);
        if (m.matches()) {
            String circa = m.group(1) == null ? "" : m.group(1);
            int century = Integer.parseInt(m.group(2));
            String bc = m.group(3);

            if (bc == null) {
                // EDTF: 19th century → 18XX
                return Optional.of(padCentury(century - 1) + "XX" + circa);
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
            return Optional.of("-" + padYear(startYear) + circa
                               + "/-" + padYear(endYear) + circa);
        }

        // --- early/mid/late decade ----------------------------------------
        m = THIRD_DECADE.matcher(osm);
        if (m.matches()) {
            String third = m.group(1);
            int decade = Integer.parseInt(m.group(2));
            boolean bc = m.group(3) != null;
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
            String third = m.group(1);
            int century = Integer.parseInt(m.group(2)) - 1;
            boolean bc = m.group(3) != null;
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
        m = BEFORE.matcher(osm);
        if (m.matches()) {
            // Slash notation for open-ended intervals, matching the range branch.
            // "/1900" = interval ending at 1900 with unknown start.
            return Optional.of("/" + m.group(1));
        }
        m = AFTER.matcher(osm);
        if (m.matches()) {
            // Slash notation: "1850/" = interval starting at 1850 with unknown end.
            return Optional.of(m.group(1) + "/");
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
    public static boolean looksLikeValidEdtf(String value) {
        if (value == null || value.isEmpty()) return false;
        // Plain ISO date
        if (isIsoCalendarDate(value)) return true;
        // Unspecified-digit forms (e.g. 18XX, 185X) with optional qualifier
        if (value.matches("^-?\\d{2,3}X{1,2}[~?%]?$")) return true;
        // Bracket interval: [A..B], [..B], [A..]
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            int dotdot = inner.indexOf("..");
            if (dotdot >= 0) {
                String left = inner.substring(0, dotdot);
                String right = inner.substring(dotdot + 2);
                boolean leftOk = left.isEmpty() || looksLikeValidEdtfPart(left);
                boolean rightOk = right.isEmpty() || looksLikeValidEdtfPart(right);
                return leftOk && rightOk;
            }
            return false;
        }
        // Slash interval — recurse on the pieces (empty side is OK for open-ended)
        int slash = value.indexOf('/');
        if (slash >= 0) {
            String left = value.substring(0, slash);
            String right = value.substring(slash + 1);
            boolean leftOk = left.isEmpty() || looksLikeValidEdtfPart(left);
            boolean rightOk = right.isEmpty() || looksLikeValidEdtfPart(right);
            return leftOk && rightOk;
        }
        // Qualified single values (e.g. 1850~, 1850-03-15?)
        if (value.endsWith("~") || value.endsWith("?") || value.endsWith("%")) {
            return looksLikeValidEdtfPart(value.substring(0, value.length() - 1));
        }
        return false;
    }

    private static boolean looksLikeValidEdtfPart(String value) {
        if (isIsoCalendarDate(value)) return true;
        if (value.matches("^-?\\d{2,3}X{1,2}[~?%]?$")) return true;
        // EDTF season / sub-year code: YYYY-NN where NN in {21..41}
        if (EDTF_SEASON_CODE.matcher(value).matches()) return true;
        if (value.endsWith("~") || value.endsWith("?") || value.endsWith("%")) {
            return isIsoCalendarDate(value.substring(0, value.length() - 1));
        }
        return false;
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
     * @return the bound, or empty if EDTF is empty / malformed
     */
    public static Optional<String> lowerBoundIso(String edtf) {
        if (edtf == null || edtf.isEmpty()) return Optional.empty();
        // Bracket interval: [A..B], [..B], [A..]
        if (edtf.startsWith("[") && edtf.endsWith("]")) {
            String inner = edtf.substring(1, edtf.length() - 1);
            int dotdot = inner.indexOf("..");
            if (dotdot >= 0) {
                String left = inner.substring(0, dotdot);
                String right = inner.substring(dotdot + 2);
                if (!left.isEmpty()) {
                    return stripQualifiersToIso(expandUnspecifiedLower(left));
                }
                // Open-left: preserve the one year present.
                if (!right.isEmpty()) {
                    return stripQualifiersToIso(expandUnspecifiedLower(right));
                }
            }
            return Optional.empty();
        }
        int slash = edtf.indexOf('/');
        if (slash >= 0) {
            String left = edtf.substring(0, slash);
            String right = edtf.substring(slash + 1);
            if (!left.isEmpty()) {
                return stripQualifiersToIso(expandUnspecifiedLower(left));
            }
            // Open-left interval like /1900: preserve the one year present.
            if (!right.isEmpty()) {
                return stripQualifiersToIso(expandUnspecifiedLower(right));
            }
            return Optional.empty();
        }
        return stripQualifiersToIso(expandUnspecifiedLower(edtf));
    }

    /**
     * Extracts the upper bound of an EDTF expression as an ISO calendar date
     * suitable for the OHM time-slider's {@code end_date} tag. Mirror of
     * {@link #lowerBoundIso(String)}.
     */
    public static Optional<String> upperBoundIso(String edtf) {
        if (edtf == null || edtf.isEmpty()) return Optional.empty();
        // Bracket interval: [A..B], [..B], [A..]
        if (edtf.startsWith("[") && edtf.endsWith("]")) {
            String inner = edtf.substring(1, edtf.length() - 1);
            int dotdot = inner.indexOf("..");
            if (dotdot >= 0) {
                String left = inner.substring(0, dotdot);
                String right = inner.substring(dotdot + 2);
                if (!right.isEmpty()) {
                    return stripQualifiersToIso(expandUnspecifiedUpper(right));
                }
                // Open-right: preserve the one year present.
                if (!left.isEmpty()) {
                    return stripQualifiersToIso(expandUnspecifiedUpper(left));
                }
            }
            return Optional.empty();
        }
        int slash = edtf.indexOf('/');
        if (slash >= 0) {
            String left = edtf.substring(0, slash);
            String right = edtf.substring(slash + 1);
            if (!right.isEmpty()) {
                return stripQualifiersToIso(expandUnspecifiedUpper(right));
            }
            // Open-right interval like 1850/: preserve the one year present.
            if (!left.isEmpty()) {
                return stripQualifiersToIso(expandUnspecifiedUpper(left));
            }
            return Optional.empty();
        }
        return stripQualifiersToIso(expandUnspecifiedUpper(edtf));
    }

    /** Expand an X-digit EDTF form to its lowest concrete year (e.g. 18XX → 1800, 185X → 1850). */
    private static String expandUnspecifiedLower(String edtf) {
        String stripped = edtf.replaceAll("[~?%]$", "");
        if (stripped.matches("^-?\\d{2,3}X{1,2}$")) {
            return stripped.replace('X', '0');
        }
        return edtf;
    }

    /** Expand an X-digit EDTF form to its highest concrete year (e.g. 18XX → 1899, 185X → 1859). */
    private static String expandUnspecifiedUpper(String edtf) {
        String stripped = edtf.replaceAll("[~?%]$", "");
        if (stripped.matches("^-?\\d{2,3}X{1,2}$")) {
            return stripped.replace('X', '9');
        }
        return edtf;
    }

    /**
     * Remove EDTF qualifiers and verify the result is a plain ISO calendar date.
     *
     * <p>EDTF season codes ({@code YYYY-NN} where {@code NN} is 21..41) are
     * reduced to the bare year {@code YYYY}, since the OHM time-slider can't
     * interpret season codes directly. This is intentionally lossy — the
     * :edtf sibling keeps the full precision for consumers that understand
     * seasons — but it gives the base tag a valid ISO date.
     */
    private static Optional<String> stripQualifiersToIso(String edtf) {
        String stripped = edtf.replaceAll("[~?%]$", "");
        // Season code: reduce YYYY-NN to YYYY for the base tag.
        Matcher seasonMatcher = EDTF_SEASON_CODE.matcher(stripped);
        if (seasonMatcher.matches()) {
            return Optional.of(seasonMatcher.group(1));
        }
        if (isIsoCalendarDate(stripped)) {
            return Optional.of(stripped);
        }
        return Optional.empty();
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

    private static int[] offsetsForDecadeThird(String third) {
        switch (third) {
            case "early": return new int[]{0, 3};
            case "mid":   return new int[]{3, 7};
            case "late":  return new int[]{7, 9};
            default: throw new IllegalArgumentException(third);
        }
    }

    private static int[] offsetsForCenturyThird(String third) {
        switch (third) {
            case "early": return new int[]{0, 30};
            case "mid":   return new int[]{30, 70};
            case "late":  return new int[]{70, 99};
            default: throw new IllegalArgumentException(third);
        }
    }
}
