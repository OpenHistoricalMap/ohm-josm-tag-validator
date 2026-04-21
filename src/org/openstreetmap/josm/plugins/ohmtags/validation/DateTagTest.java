// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.ohmtags.validation;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.ohmtags.DateNormalizer;

/**
 * Validates OHM date tags and offers autofixes to produce the three-tag triple:
 * {@code <key>} (ISO 8601 calendar date for the OHM time-slider),
 * {@code <key>:edtf} (structured EDTF form), and
 * {@code <key>:raw} (original human-entered value, as a review scaffold).
 * Julian-calendar conversions preserve the original in {@code <key>:note}
 * instead, per wiki convention.
 *
 * <p>Primary checked keys: {@code start_date}, {@code end_date}. The generic
 * {@code :edtf} validator checks any key ending in {@code :edtf}.
 *
 * <h3>Rule summary</h3>
 *
 * <p><b>Missing {@code start_date}.</b> Features without a {@code start_date}
 * and without any {@code natural=*} tag are flagged as warnings with no autofix.
 * The message directs users to add a reasoned {@code start_date:edtf} range
 * plus {@code start_date:source} annotation. Severity is warning, not error,
 * to match iD.
 *
 * <p><b>Missing {@code end_date}.</b> Never flagged.
 *
 * <p><b>Calendar validity.</b> Full ISO dates that pass basic range checks
 * ({@code 1 <= MM <= 12}, {@code 1 <= DD <= 31}) but aren't real calendar
 * dates (Feb 30, June 31, Feb 29 on non-leap years, etc.) are reported as
 * errors with no autofix — the user has to determine the intended date.
 *
 * <p><b>Far-future dates.</b> Only dates more than ten years beyond today
 * are flagged, with an autofix offering deletion. Near-future dates (today
 * to today+10 years) are silent, to accommodate planned demolitions and
 * similar legitimate uses.
 *
 * <p><b>EDTF season codes.</b> Natural-language season expressions
 * ({@code fall of 1814}, {@code autumn 1920}, {@code winter of 1940}) are
 * normalized to EDTF season codes ({@code 1814-23}, etc.). The base tag is
 * reduced to the bare year, matching the conservative lower/upper-bound
 * convention used for other vague inputs.
 *
 * <p><b>Ambiguous {@code YY00s} input.</b> Values like {@code 1800s}, which
 * could mean the decade (1800–1809) or the century (1800–1899), produce two
 * separate warnings — one offering the decade interpretation, one offering
 * the century interpretation — in place of the normal normalization warning.
 *
 * <p><b>Start/end equality and backslash patterns</b> (see
 * {@link #checkStartEndEqualityAndBackslash}):
 * <ul>
 *   <li><b>Rule A:</b> tagcleanupbot rollback — last editor is
 *       {@code tagcleanupbot} AND {@code start_date:edtf} equals
 *       {@code "\" + <end_date value>} exactly. Warn with autofix to
 *       delete both {@code start_date} and {@code start_date:edtf}.</li>
 *   <li><b>Rule B:</b> {@code start_date == end_date}, no backslash in
 *       {@code :edtf}, non-bot last editor. Warn with no fix (could be a
 *       legitimate single-day event).</li>
 *   <li><b>Rule C:</b> backslash + end_date exact signature but non-bot
 *       last editor. Warn with narrower fix: delete {@code :edtf} only.</li>
 *   <li><b>Rule D1:</b> backslash in {@code :edtf}, remainder is a prefix
 *       of {@code end_date} but not the full value. Warn, no fix.</li>
 *   <li><b>Rule D2:</b> backslash in {@code :edtf}, remainder not related
 *       to {@code end_date}. Warn with fix to strip backslash and
 *       renormalize.</li>
 * </ul>
 *
 * <p><b>Generic {@code :edtf} validator.</b> For any key ending in
 * {@code :edtf} (other than the {@code start_date:edtf} /
 * {@code end_date:edtf} pair handled above), validate the EDTF value:
 * <ul>
 *   <li>Valid: no warning.</li>
 *   <li>Invalid but normalizable: warning with autofix that transforms
 *       {@code :edtf} and preserves the original in {@code :edtf:raw}. The
 *       base tag is not touched.</li>
 *   <li>Invalid and unnormalizable: error, no autofix.</li>
 *   <li>If base tag also exists, a separate error notes the
 *       base ↔ {@code :edtf} mismatch cannot be auto-resolved.</li>
 * </ul>
 *
 * <p><b>Primary flow when {@code :raw} is present.</b> {@code :raw} is treated
 * as the source of truth. If the base tag and {@code :edtf} match what the
 * normalizer would produce from {@code :raw}, nothing is flagged. If they
 * don't match, the last editor of the primitive determines the fix:
 * <ul>
 *   <li>Last editor is {@code tagcleanupbot}: autofix rewrites base and
 *       {@code :edtf} from {@code :raw}, assuming the bot made a mistake that
 *       should be corrected by the current normalizer.</li>
 *   <li>Any other last editor: warn with an autofix to delete {@code :raw}.
 *       The reasoning is that any human edit invalidates the bot-written
 *       scaffold; deleting {@code :raw} leaves the human-corrected base and
 *       {@code :edtf} as canonical.</li>
 * </ul>
 *
 * <p><b>Known risk with the "delete {@code :raw}" fix.</b> If a human
 * deliberately edited {@code :raw} (e.g. correcting a typo in the original),
 * deleting that edit loses their correction. We accept this risk because
 * {@code :raw} is documented as a machine-generated scaffold that humans
 * shouldn't edit directly; the correct workflow is to edit base and
 * {@code :edtf}, which then triggers the cleanup path above. Also, JOSM's
 * last-editor field is primitive-level, not per-tag, so we can't distinguish
 * a human who edited the date tags from a human who edited an unrelated tag
 * on the same primitive.
 *
 * <p><b>Primary flow when {@code :raw} is absent.</b> Behavior depends on
 * whether {@code :edtf} and base are present:
 * <ul>
 *   <li>Both base and {@code :edtf} absent: skip (nothing to check).</li>
 *   <li>{@code :edtf} present and valid, base absent: warn with autofix
 *       adding a base tag derived from {@code :edtf}. No {@code :raw} is
 *       written — there was no original human input to preserve.</li>
 *   <li>{@code :edtf} present and valid, base present and matches: nothing.</li>
 *   <li>{@code :edtf} present and valid, base present and is a refinement
 *       (finer precision, within bounds): warn, no fix (separately flagged
 *       so the user can confirm the authoritative value).</li>
 *   <li>{@code :edtf} present and valid, base present but doesn't match
 *       and isn't a refinement: warn, no fix (human conflict — don't guess).</li>
 *   <li>{@code :edtf} present but invalid and normalizable: warn with
 *       autofix (transforms {@code :edtf}, preserves original in
 *       {@code :edtf:raw}).</li>
 *   <li>{@code :edtf} present but invalid and unnormalizable: warn, no fix.</li>
 *   <li>{@code :edtf} invalid with base present: an additional "cannot be
 *       reconciled" warning fires alongside the above.</li>
 *   <li>{@code :edtf} absent, base matches ISO: nothing.</li>
 *   <li>{@code :edtf} absent, base is parseable by the normalizer but not
 *       ISO: warn with autofix writing the full triple (move base to
 *       {@code :raw}, write derived base and {@code :edtf}).</li>
 *   <li>{@code :edtf} absent, base is a Julian-calendar marker
 *       ({@code j:YYYY-MM-DD}) or Julian day number ({@code jd:NNNNNNN}):
 *       convert to Gregorian, write to base, annotate in {@code :note}.
 *       No {@code :edtf} is written (EDTF has no standard form for these).</li>
 *   <li>{@code :edtf} absent, base is unparseable: error, no fix.</li>
 * </ul>
 *
 * <p><b>Conservative bound convention.</b> When a vague value (decade,
 * century, season, range) produces a base tag, the plugin uses the lower
 * bound for {@code start_date} and the upper bound for {@code end_date}.
 * This is deliberately asymmetric: it matches how the OHM renderer
 * interprets year-precision ISO values ({@code start_date=1850} = Jan 1;
 * {@code end_date=1850} = Dec 31). Midpoint conventions are not used.
 */
public class DateTagTest extends Test {

    // --- Error codes ---------------------------------------------------------
    protected static final int CODE_MISSING_START_DATE = 4200;
    protected static final int CODE_UNPARSEABLE = 4201;
    protected static final int CODE_NEEDS_NORMALIZATION = 4202;
    protected static final int CODE_AMBIGUOUS_DECADE = 4203;
    protected static final int CODE_AMBIGUOUS_CENTURY = 4204;
    protected static final int CODE_RAW_MISMATCH_BOT = 4205;
    protected static final int CODE_RAW_MISMATCH_HUMAN = 4206;
    protected static final int CODE_RAW_UNPARSEABLE = 4207;
    protected static final int CODE_EDTF_INVALID_NO_BASE = 4208;
    // 4209 (CODE_EDTF_INVALID_WITH_BASE) retired: invalid-:edtf with base now
    //   fires CODE_EDTF_INVALID_NO_BASE (unified unfixable message) plus
    //   CODE_ANY_EDTF_BASE_MISMATCH_UNRESOLVABLE as companions.
    protected static final int CODE_EDTF_BASE_MISMATCH = 4210;
    protected static final int CODE_EDTF_MISSING_BASE = 4211;
    protected static final int CODE_SUSPICIOUS_YEAR_START = 4212;
    protected static final int CODE_SUSPICIOUS_YEAR_END = 4213;
    protected static final int CODE_SUSPICIOUS_OFF_BY_ONE = 4214;
    protected static final int CODE_START_AFTER_END = 4215;
    protected static final int CODE_FUTURE_DATE = 4216;
    protected static final int CODE_INVALID_MONTH = 4217;
    protected static final int CODE_INVALID_DAY = 4218;
    protected static final int CODE_AMBIGUOUS_NEGATIVE = 4219;
    protected static final int CODE_AMBIGUOUS_TRAILING_HYPHEN = 4220;
    protected static final int CODE_PRESENT_MARKER = 4221;
    // New error codes for this revision.
    protected static final int CODE_CALENDAR_INVALID = 4222;
    protected static final int CODE_BOT_ROLLBACK = 4223;              // Rule A
    protected static final int CODE_START_END_EQUAL = 4224;           // Rule B
    protected static final int CODE_BACKSLASH_END_DATE_PATTERN = 4225; // Rule C
    protected static final int CODE_BACKSLASH_TRUNCATED = 4226;       // Rule D1
    // 4227 (CODE_BACKSLASH_INVALID_EDTF) retired: Rule D2 now fires the
    //   unified "Invalid *_date:edtf" fixable/unfixable messages.
    protected static final int CODE_ANY_EDTF_INVALID_FIXABLE = 4228;
    // 4229 (CODE_ANY_EDTF_INVALID_UNFIXABLE) retired: merged with
    //   CODE_EDTF_INVALID_NO_BASE under the unified unfixable message.
    protected static final int CODE_ANY_EDTF_BASE_MISMATCH_UNRESOLVABLE = 4230;
    protected static final int CODE_PRESENT_START_DATE = 4231;
    protected static final int CODE_MORE_SPECIFIC_BASE = 4232;
    protected static final int CODE_JULIAN_CONVERSION = 4233;

    /** Matches a full ISO date in {@code YYYY-MM-DD} form (astronomical, may be negative). */
    private static final Pattern FULL_ISO_DATE =
        Pattern.compile("^(-?\\d{4})-(\\d{2})-(\\d{2})$");

    /** Matches a bare negative year like {@code -1920} with no month/day suffix. */
    private static final Pattern BARE_NEGATIVE_YEAR =
        Pattern.compile("^-\\d{3,4}$");

    /**
     * Matches date-like values ending in a trailing hyphen: {@code 2021-},
     * {@code 2021-03-}, {@code -500-}. Ambiguous in a way that {@code ..}
     * is not — could be a typo, an incomplete input, or a range intent.
     */
    private static final Pattern TRAILING_HYPHEN =
        Pattern.compile("^-?\\d{3,4}(?:-\\d{1,2})?-$");

    /** Matches an ISO year-month ({@code YYYY-MM}), astronomical (may be negative). */
    private static final Pattern ISO_YEAR_MONTH =
        Pattern.compile("^(-?\\d{4})-(\\d{2})$");

    /** Base-date keys we validate. */
    private static final List<String> BASE_KEYS = Arrays.asList("start_date", "end_date");

    /** Matches values like {@code 1800s}, {@code ~1900s} — potentially a century misread as a decade. */
    private static final Pattern AMBIGUOUS_CENTURY_DECADE =
        Pattern.compile("^~?\\d+00s( BCE?)?$");

    /** The bot username trusted to have authored correct {@code :raw} values. */
    private static final String TRUSTED_BOT_USER = "tagcleanupbot";

    public DateTagTest() {
        super(tr("OHM date tags"),
              tr("Checks that start_date / end_date tags form a consistent "
                 + "triple with :edtf and :raw siblings for the OHM time-slider."));
    }

    @Override
    public void visit(org.openstreetmap.josm.data.osm.Node n) {
        checkPrimitive(n);
    }

    @Override
    public void visit(org.openstreetmap.josm.data.osm.Way w) {
        checkPrimitive(w);
    }

    @Override
    public void visit(org.openstreetmap.josm.data.osm.Relation r) {
        checkPrimitive(r);
    }

    private void checkPrimitive(OsmPrimitive p) {
        // Rule: man-made features should have start_date.
        // Only flag features that have some other identifying tag — don't
        // complain about every untagged node (those are covered by other
        // validators and would create too much noise).
        //
        // Severity is WARNING, not ERROR, matching iD's validator. The OHM
        // wiki says "should" (not "must") for this tag; speculative start
        // dates are worse than missing ones, so this is a nudge to research
        // rather than a blocker to save.
        if (p.get("start_date") == null && !hasNaturalTag(p) && p.hasKeys()) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_MISSING_START_DATE)
                .message(tr("[ohm] Suspicious date - man-made object without start_date; unfixable, please review"),
                         tr("Please make every effort to attempt a reasonable range "
                          + "for the `start_date:edtf` tag and provide an explanation "
                          + "in the `start_date:source` tag."))
                .primitives(p)
                .build());
        }

        for (String baseKey : BASE_KEYS) {
            checkAmbiguousNegativeYear(p, baseKey);
            checkAmbiguousTrailingHyphen(p, baseKey);
            checkDateFamily(p, baseKey);
            checkMoreSpecificBase(p, baseKey);
            checkSuspiciousYearBoundary(p, baseKey);
            checkInvalidComponents(p, baseKey);
            checkFutureEndDate(p, baseKey);
        }
        checkStartAfterEnd(p);
        checkStartEndEqualityAndBackslash(p);
        checkAllEdtfKeys(p);
    }

    /** True if the primitive has any {@code natural=*} tag. */
    private static boolean hasNaturalTag(OsmPrimitive p) {
        return p.get("natural") != null;
    }

    /**
     * Flag values like {@code 2021-} or {@code 2021-03-} — date-like strings
     * ending in a bare hyphen — as ambiguous.
     *
     * <p>Could be a typo (stray trailing {@code -}), an incomplete input
     * (user was still typing), or an attempt at an open-ended range that
     * should have been written as {@code 2021/} or {@code 2021..}. We
     * can't guess, so we warn without an autofix.
     *
     * <p>Does not fire if a corroborating sibling ({@code :raw} or
     * {@code :edtf}) is present.
     */
    private void checkAmbiguousTrailingHyphen(OsmPrimitive p, String baseKey) {
        String value = p.get(baseKey);
        if (value == null) return;
        if (!TRAILING_HYPHEN.matcher(value).matches()) return;

        if (p.get(baseKey + ":raw") != null) return;
        if (p.get(baseKey + ":edtf") != null) return;

        String trimmed = value.substring(0, value.length() - 1);
        errors.add(TestError.builder(this, Severity.WARNING, CODE_AMBIGUOUS_TRAILING_HYPHEN)
            .message(tr("[ohm] Ambiguous date - trailing hyphen in date; unfixable, please review"),
                     tr("{0}={1}: could be a typo for {2}, an incomplete input, "
                        + "or an open-ended range {2}/. Manual review needed.",
                        baseKey, value, trimmed))
            .primitives(p)
            .build());
    }


    /**
     * Flag bare negative-year values like {@code -1920} as ambiguous.
     *
     * <p>Technically {@code -1920} is valid EDTF / astronomical ISO for
     * "1921 BCE" — but when a human types it into a base tag, the intent
     * is usually one of:
     * <ul>
     *   <li>BCE year N (astronomical 1-N)</li>
     *   <li>A typo: they meant {@code 1920} and a stray {@code -} crept in</li>
     *   <li>Open-ended range: they meant {@code before 1920} or {@code /1920}</li>
     *   <li>Part of a range that's missing the other side</li>
     * </ul>
     *
     * <p>We can't guess which, so we warn without an autofix. The user
     * resolves manually.
     *
     * <p>Not flagged if there's a corroborating sibling tag ({@code :raw}
     * or {@code :edtf}) — presence of those implies the author or a prior
     * tool intentionally set this as BCE.
     */
    private void checkAmbiguousNegativeYear(OsmPrimitive p, String baseKey) {
        String value = p.get(baseKey);
        if (value == null) return;
        if (!BARE_NEGATIVE_YEAR.matcher(value).matches()) return;

        // If there's a :raw or :edtf sibling, the BCE intent is presumably
        // corroborated — don't flag.
        if (p.get(baseKey + ":raw") != null) return;
        if (p.get(baseKey + ":edtf") != null) return;

        errors.add(TestError.builder(this, Severity.WARNING, CODE_AMBIGUOUS_NEGATIVE)
            .message(tr("[ohm] Ambiguous date - negative-year date; unfixable, please review"),
                     tr("{0}={1}: could be astronomical BCE, a typo for {2}, "
                        + "a range like {3}, or part of a range missing its other side. "
                        + "Manual review needed.",
                        baseKey, value,
                        value.substring(1),   // suggested positive
                        "/" + value.substring(1))) // suggested open-ended
            .primitives(p)
            .build());
    }

    /**
     * Detect values like {@code YYYY-01-01} and {@code YYYY-12-31}, which are
     * commonly artifacts of data imports that forced a precise date where
     * only the year was known. Two kinds of suspicion:
     *
     * <p><b>False precision at year boundaries.</b> A {@code start_date}
     * of {@code YYYY-01-01} or {@code end_date} of {@code YYYY-12-31} is
     * suspicious because the exact day of year-start / year-end is almost
     * always an artifact. Offers to trim to {@code YYYY}.
     *
     * <p><b>Off-by-one at year boundaries.</b> A {@code start_date} of
     * {@code YYYY-12-31} likely means "started at the beginning of year
     * {@code YYYY+1}" and should probably be {@code YYYY+1}. Similarly an
     * {@code end_date} of {@code YYYY-01-01} likely means "ended at the
     * end of year {@code YYYY-1}". Offers the shifted year.
     *
     * <p>This especially applies to BCE dates (astronomical negative years),
     * where the false precision is obvious — nobody knows the exact day a
     * feature was built in antiquity.
     *
     * <p>Only the base tag is checked; {@code :edtf} and {@code :raw} carry
     * different semantics and aren't subject to this suspicion.
     */
    private void checkSuspiciousYearBoundary(OsmPrimitive p, String baseKey) {
        String value = p.get(baseKey);
        if (value == null) return;

        Matcher m = FULL_ISO_DATE.matcher(value);
        if (!m.matches()) return;

        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));

        boolean isStart = "start_date".equals(baseKey);
        boolean isEnd = "end_date".equals(baseKey);
        boolean isJan1 = month == 1 && day == 1;
        boolean isDec31 = month == 12 && day == 31;

        if (isStart && isJan1) {
            // False precision: start of year.
            String yearStr = padAstronomicalYear(year);
            Command fix = new ChangePropertyCommand(Arrays.asList(p), baseKey, yearStr);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SUSPICIOUS_YEAR_START)
                .message(tr("[ohm] Suspicious date - 01-01 start_date; autofix by removing -01-01"),
                         tr("{0}={1} \u2192 {0}={2}", baseKey, value, yearStr))
                .primitives(p)
                .fix(() -> fix)
                .build());
        } else if (isEnd && isDec31) {
            // False precision: end of year.
            String yearStr = padAstronomicalYear(year);
            Command fix = new ChangePropertyCommand(Arrays.asList(p), baseKey, yearStr);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SUSPICIOUS_YEAR_END)
                .message(tr("[ohm] Suspicious date - 12-31 end_date; autofix by removing -12-31"),
                         tr("{0}={1} \u2192 {0}={2}", baseKey, value, yearStr))
                .primitives(p)
                .fix(() -> fix)
                .build());
        } else if (isStart && isDec31) {
            // Off-by-one: start on last day of year N almost certainly means year N+1.
            // Astronomical arithmetic: for BCE dates (negative), this still works
            // mechanically — e.g. -0050-12-31 → -0049 (1 year later astronomically,
            // which is 49 BCE, i.e. 1 year later in BCE labeling too since both
            // representations share the year-0 convention here).
            String shiftedYear = padAstronomicalYear(year + 1);
            Command fix = new ChangePropertyCommand(Arrays.asList(p), baseKey, shiftedYear);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SUSPICIOUS_OFF_BY_ONE)
                .message(tr("[ohm] Suspicious date - 12-31 start_date; autofix by removing -12-31"),
                         tr("{0}={1} likely means the beginning of year {2}. \u2192 {0}={2}",
                            baseKey, value, shiftedYear))
                .primitives(p)
                .fix(() -> fix)
                .build());
        } else if (isEnd && isJan1) {
            // Off-by-one: end on first day of year N almost certainly means year N-1.
            String shiftedYear = padAstronomicalYear(year - 1);
            Command fix = new ChangePropertyCommand(Arrays.asList(p), baseKey, shiftedYear);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SUSPICIOUS_OFF_BY_ONE)
                .message(tr("[ohm] Suspicious date - 01-01 end_date; autofix by removing -01-01"),
                         tr("{0}={1} likely means the end of year {2}. \u2192 {0}={2}",
                            baseKey, value, shiftedYear))
                .primitives(p)
                .fix(() -> fix)
                .build());
        }
    }

    /** Format an astronomical year for ISO output, preserving BCE negatives. */
    private static String padAstronomicalYear(int year) {
        if (year < 0) {
            return "-" + String.format("%04d", -year);
        }
        return String.format("%04d", year);
    }

    /**
     * Flag month values greater than 12, day values greater than 31, and
     * day-in-month violations (Feb 30, June 31, Feb 29 on non-leap years).
     *
     * <p>Range-invalid cases (month > 12, day > 31): offer to trim. The user
     * made a typo in the MM or DD; keep whatever prefix is still valid.
     * <ul>
     *   <li>Invalid month: trim to {@code YYYY} (the day doesn't matter
     *       if the month is wrong — we can't know which month was meant).</li>
     *   <li>Valid month but invalid day: trim to {@code YYYY-MM}.</li>
     * </ul>
     *
     * <p>Calendar-invalid cases (Feb 30, June 31, Feb 29 on non-leap years):
     * ERROR, no autofix. The user may have meant one of several possibilities
     * (previous-day, next-day, typo in month, typo in day) and we can't
     * guess which. This is different from range-invalid cases where the
     * digit is plainly out of bounds.
     */
    private void checkInvalidComponents(OsmPrimitive p, String baseKey) {
        String value = p.get(baseKey);
        if (value == null) return;

        // YYYY-MM-DD
        Matcher m = FULL_ISO_DATE.matcher(value);
        if (m.matches()) {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            if (month < 1 || month > 12) {
                String trimmed = m.group(1);
                Command fix = new ChangePropertyCommand(Arrays.asList(p), baseKey, trimmed);
                errors.add(TestError.builder(this, Severity.WARNING, CODE_INVALID_MONTH)
                    .message(tr("[ohm] Invalid date - invalid month in start_date or end_date; autofix to YYYY"),
                             tr("{0}={1}: month {2} is out of range. Trim to {3}?",
                                baseKey, value, month, trimmed))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
                return;
            }
            if (day < 1 || day > 31) {
                String trimmed = m.group(1) + "-" + m.group(2);
                Command fix = new ChangePropertyCommand(Arrays.asList(p), baseKey, trimmed);
                errors.add(TestError.builder(this, Severity.WARNING, CODE_INVALID_DAY)
                    .message(tr("[ohm] Invalid date - invalid day in start_date or end_date; autofix to YYYY-MM"),
                             tr("{0}={1}: day {2} is out of range. Trim to {3}?",
                                baseKey, value, day, trimmed))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
                return;
            }
            // Day-in-month check: month and day are in basic range, but is
            // this particular day actually valid for this particular month
            // and year? Use Java's LocalDate to do the leap-year arithmetic.
            // LocalDate.of throws DateTimeException on invalid dates.
            if (!isValidDayForMonth(year, month, day)) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_CALENDAR_INVALID)
                    .message(tr("[ohm] Invalid date - month/day mismatch; too many days in the month in start_date or end_date; unfixable, please review"),
                             tr("{0}={1}: {2}-{3}-{4} is not a real calendar date "
                              + "(e.g. Feb 30, June 31, or Feb 29 on a non-leap year). "
                              + "Manual review needed.",
                                baseKey, value,
                                m.group(1), m.group(2), m.group(3)))
                    .primitives(p)
                    .build());
                return;
            }
            return;
        }

        // YYYY-MM (no day to check)
        m = ISO_YEAR_MONTH.matcher(value);
        if (m.matches()) {
            int month = Integer.parseInt(m.group(2));
            if (month < 1 || month > 12) {
                String trimmed = m.group(1);
                Command fix = new ChangePropertyCommand(Arrays.asList(p), baseKey, trimmed);
                errors.add(TestError.builder(this, Severity.WARNING, CODE_INVALID_MONTH)
                    .message(tr("[ohm] Invalid date - invalid month in start_date or end_date; autofix to YYYY"),
                             tr("{0}={1}: month {2} is out of range. Trim to {3}?",
                                baseKey, value, month, trimmed))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            }
        }
    }

    /**
     * True if the given year/month/day triple is a real calendar date.
     * Uses Java's LocalDate to get leap-year and days-in-month right.
     * Handles astronomical years (including year 0 and negatives).
     */
    private static boolean isValidDayForMonth(int year, int month, int day) {
        try {
            LocalDate.of(year, month, day);
            return true;
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    /**
     * Flag dates that are more than ten years in the future.
     *
     * <p>Dates between today and today + 10 years are silent — OHM often
     * records planned/anticipated future events (scheduled demolitions,
     * recently-planted trees with projected lifespans, etc.) and near-future
     * values are legitimate. Only dates well beyond that window (more
     * than 10 years out) are almost always typos or stale data entry,
     * so we warn and offer deletion.
     *
     * <p>"Future" is relative to the machine clock at validation time.
     * Running the same validation at different moments can produce
     * different results for dates close to the ten-year boundary — a minor
     * inconsistency we accept as the cost of a simple check.
     */
    private void checkFutureEndDate(OsmPrimitive p, String baseKey) {
        String value = p.get(baseKey);
        if (value == null) return;
        if (!DateNormalizer.isIsoCalendarDate(value)) return;

        // Don't flag BCE dates as "future" (they obviously aren't).
        if (value.startsWith("-")) return;

        LocalDate today = LocalDate.now();
        LocalDate parsed = tryParseIsoAsLocalDate(value);
        if (parsed == null) return;

        LocalDate threshold = today.plusYears(10);
        if (parsed.isAfter(threshold)) {
            Command fix = new ChangePropertyCommand(Arrays.asList(p), baseKey, null);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_FUTURE_DATE)
                .message(tr("[ohm] Suspicious date - >10 year into the future; autofix as removed"),
                         tr("{0}={1} is more than ten years in the future. Likely a typo; delete the key?",
                            baseKey, value))
                .primitives(p)
                .fix(() -> fix)
                .build());
        }
    }

    /**
     * Parse an ISO calendar date (YYYY, YYYY-MM, or YYYY-MM-DD) into a
     * {@link LocalDate}. Returns null on failure. For year-only or
     * year-month inputs, fills in the first day of that period so that
     * comparison with "today" gives a sensible "in the future" answer
     * (e.g. {@code 2030} → {@code 2030-01-01}, which is in the future
     * through all of year 2029).
     */
    private static LocalDate tryParseIsoAsLocalDate(String iso) {
        try {
            if (iso.length() == 4) {
                return LocalDate.of(Integer.parseInt(iso), 1, 1);
            }
            if (iso.length() == 7) {
                return LocalDate.of(Integer.parseInt(iso.substring(0, 4)),
                                    Integer.parseInt(iso.substring(5, 7)), 1);
            }
            return LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Flag primitives where {@code start_date > end_date}, offering to swap.
     *
     * <p>Only fires when both tags are valid ISO calendar dates. Missing
     * or non-ISO values are skipped because we can't reliably compare.
     *
     * <p>Lexicographic string comparison of ISO dates is correct for all
     * year lengths including BCE: {@code -0599} lexicographically precedes
     * {@code 1850} (the {@code -} character sorts before digits), which
     * matches semantic ordering.
     */
    private void checkStartAfterEnd(OsmPrimitive p) {
        String start = p.get("start_date");
        String end = p.get("end_date");
        if (start == null || end == null) return;
        if (!DateNormalizer.isIsoCalendarDate(start)) return;
        if (!DateNormalizer.isIsoCalendarDate(end)) return;

        if (start.compareTo(end) <= 0) return; // OK

        // Build swap fix as a sequence of two ChangePropertyCommands.
        List<Command> cmds = new ArrayList<>();
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), "start_date", end));
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), "end_date", start));
        Command fix = new SequenceCommand(tr("Swap start_date and end_date"), cmds);

        errors.add(TestError.builder(this, Severity.WARNING, CODE_START_AFTER_END)
            .message(tr("[ohm] Suspicious date - start_date > end_date; autofix by swapping these"),
                     tr("start_date={0}, end_date={1}. Swap?", start, end))
            .primitives(p)
            .fix(() -> fix)
            .build());
    }

    /**
     * Implements Rules A, B, C, D1, and D2 governing the relationship
     * between {@code start_date}, {@code end_date}, and {@code start_date:edtf}
     * with respect to the tagcleanupbot rollback pattern and human-authored
     * equal-date pitfalls.
     *
     * <p><b>Rule A (tagcleanupbot rollback).</b> The bot wrote a
     * {@code start_date:edtf} value of the form {@code "\" + <end_date value>}
     * (literal backslash followed by exactly the end_date value). If the
     * last editor is the bot AND {@code :edtf} has this exact shape, it's
     * bot-authored invalid pattern: warn, offer to delete both {@code start_date}
     * and {@code start_date:edtf} to restore the pre-bot state (no
     * {@code start_date}, just {@code end_date}).
     *
     * <p><b>Rule B (equal start/end, no bot signature).</b> If
     * {@code start_date == end_date}, no backslash in {@code :edtf}, and
     * the last editor is not the bot, the feature probably has mistakenly
     * equal dates — but could legitimately be a single-day event (a
     * treaty signing, a battle). Warn without autofix so the user can
     * decide.
     *
     * <p><b>Rule C (backslash + end_date exact, not bot).</b> Same as
     * Rule A's bot signature but with a non-bot last editor. This can
     * happen when someone made an unrelated edit to a bot-touched feature
     * (JOSM tracks last-editor per-primitive, not per-tag). Warn and
     * offer a narrower fix: delete {@code :edtf} only, leave
     * {@code start_date} alone.
     *
     * <p><b>Rule D1 (backslash + end_date prefix).</b> {@code :edtf} starts
     * with {@code \} and the remainder is a prefix of {@code end_date}
     * but not the full value (e.g. {@code \1944} when
     * {@code end_date=1944-08-30}). Suggests a bot variant that truncated
     * its input. Warn with no fix.
     *
     * <p><b>Rule D2 (backslash + unrelated content).</b> {@code :edtf}
     * starts with {@code \} but the remainder isn't a prefix of
     * {@code end_date} (or {@code end_date} is absent). Warn with a fix
     * that strips the backslash and re-normalizes. The "pattern inclusive
     * of end_date" warning is NOT emitted in this case — the content
     * isn't related to end_date, so the pattern message would be
     * misleading.
     */
    private void checkStartEndEqualityAndBackslash(OsmPrimitive p) {
        String start = p.get("start_date");
        String end = p.get("end_date");
        String startEdtf = p.get("start_date:edtf");

        boolean botIsLastEditor = lastEditorIsTrustedBot(p);
        boolean edtfStartsWithBackslash = startEdtf != null && startEdtf.startsWith("\\");
        String backslashRemainder = edtfStartsWithBackslash
            ? startEdtf.substring(1)
            : null;

        // "Exact bot signature" = :edtf equals "\" + end_date value, literally.
        boolean exactBotSignature = edtfStartsWithBackslash
            && end != null
            && end.equals(backslashRemainder);

        // --- Rule A: last editor = bot, exact signature ---
        // Fires before Rule B/C to avoid double-warning.
        if (botIsLastEditor && exactBotSignature) {
            List<Command> cmds = new ArrayList<>();
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "start_date", null));
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "start_date:edtf", null));
            Command fix = new SequenceCommand(tr("Revert tagcleanupbot start_date"), cmds);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_BOT_ROLLBACK)
                .message(tr("[ohm] Suspicious date - start_date:edtf=\\[end_date]; autofix to delete tags"),
                         tr("start_date:edtf={0} matches end_date={1} and was written "
                          + "by tagcleanupbot. Rolling back deletes both start_date and "
                          + "start_date:edtf, restoring the pre-bot state where start_date "
                          + "was genuinely unknown.",
                            startEdtf, end))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // --- Rule C: exact signature, not bot last editor ---
        if (exactBotSignature && !botIsLastEditor) {
            Command fix = new ChangePropertyCommand(Arrays.asList(p),
                                                    "start_date:edtf", null);
            errors.add(TestError.builder(this, Severity.WARNING,
                                         CODE_BACKSLASH_END_DATE_PATTERN)
                .message(tr("[ohm] Suspicious date - start_date = end_date with backslash pattern; autofix by deleting start_date:edtf"),
                         tr("The start_date and end_date values are equal and should "
                          + "only be that way for an object that existed only for a day. "
                          + "Delete start_date:edtf?"))
                .primitives(p)
                .fix(() -> fix)
                .build());
            // Don't also fire Rule B for the same feature.
            return;
        }

        // --- Rule D1: backslash + prefix-of-end_date, not exact ---
        if (edtfStartsWithBackslash && end != null
            && !backslashRemainder.equals(end)
            && !backslashRemainder.isEmpty()
            && end.startsWith(backslashRemainder)) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_BACKSLASH_TRUNCATED)
                .message(tr("[ohm] Suspicious date - start_date:edtf range extends after end_date; unfixable, please review"),
                         tr("start_date:edtf={0}: possible `start_date:edtf = pattern` "
                          + "inclusive of end_date={1}. Manual review needed.",
                            startEdtf, end))
                .primitives(p)
                .build());
            // Don't also fire Rule B for the same feature.
            return;
        }

        // --- Rule D2: backslash + something else ---
        // Emits only the invalid-EDTF warning with a fix. No "pattern inclusive
        // of end_date" message, per advisor guidance — it would be misleading
        // when the content doesn't actually relate to end_date.
        //
        // The messages used here are the same unified titles as checkAllEdtfKeys
        // (invalid :edtf fixable / unfixable), so the user sees consistent
        // wording regardless of which code path detected the problem.
        if (edtfStartsWithBackslash) {
            // Try to normalize the post-backslash remainder.
            Optional<String> normalized = backslashRemainder.isEmpty()
                ? Optional.empty()
                : DateNormalizer.toEdtf(backslashRemainder);
            String edtfRawKey = "start_date:edtf:raw";
            if (normalized.isPresent()) {
                List<Command> cmds = new ArrayList<>();
                if (p.get(edtfRawKey) == null) {
                    cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                       edtfRawKey, startEdtf));
                }
                cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                   "start_date:edtf",
                                                   normalized.get()));
                Command fix = new SequenceCommand(
                    tr("Normalize start_date:edtf"), cmds);
                errors.add(TestError.builder(this, Severity.WARNING,
                                             CODE_ANY_EDTF_INVALID_FIXABLE)
                    .message(tr("[ohm] Invalid date - *_date:edtf; fixable, please review suggestion"),
                             tr("start_date:edtf={0}: strip leading backslash and "
                              + "re-normalize to {1}?",
                                startEdtf, normalized.get()))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            } else if (DateNormalizer.looksLikeValidEdtf(backslashRemainder)) {
                // Remainder is already valid EDTF (e.g. "\~1850" where "~1850"
                // is valid). Just strip the backslash.
                List<Command> cmds = new ArrayList<>();
                if (p.get(edtfRawKey) == null) {
                    cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                       edtfRawKey, startEdtf));
                }
                cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                   "start_date:edtf",
                                                   backslashRemainder));
                Command fix = new SequenceCommand(
                    tr("Normalize start_date:edtf"), cmds);
                errors.add(TestError.builder(this, Severity.WARNING,
                                             CODE_ANY_EDTF_INVALID_FIXABLE)
                    .message(tr("[ohm] Invalid date - *_date:edtf; fixable, please review suggestion"),
                             tr("start_date:edtf={0}: strip leading backslash to {1}?",
                                startEdtf, backslashRemainder))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            } else {
                // Can't even salvage. Fire the unified unfixable warning.
                errors.add(TestError.builder(this, Severity.WARNING,
                                             CODE_EDTF_INVALID_NO_BASE)
                    .message(tr("[ohm] Invalid date - *_date:edtf; unfixable, please review"),
                             tr("start_date:edtf={0}: leading backslash is invalid "
                              + "EDTF and the remainder cannot be normalized. "
                              + "Manual review needed.",
                                startEdtf))
                    .primitives(p)
                    .build());
            }
            return;
        }

        // --- Rule B: equal start/end, no backslash, not bot ---
        if (start != null && end != null && start.equals(end) && !botIsLastEditor) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_START_END_EQUAL)
                .message(tr("[ohm] Suspicious date - start_date = end_date; unfixable, please review"),
                         tr("The start_date and end_date values are equal and should "
                          + "only be that way for an object that existed only for a day."))
                .primitives(p)
                .build());
        }
    }

    /**
     * Validate EDTF for any key ending in {@code :edtf}. Broader in scope than
     * {@link #checkDateFamily(OsmPrimitive, String)} which only checks
     * {@code start_date:edtf} and {@code end_date:edtf} — this catches
     * user-invented keys like {@code birth_date:edtf}, {@code opening_date:edtf},
     * etc.
     *
     * <p>Behavior:
     * <ul>
     *   <li>Valid EDTF: no warning.</li>
     *   <li>Invalid but normalizable via the OHM shorthand path: warning
     *       with autofix that transforms the {@code :edtf} value and
     *       preserves the original in {@code :edtf:raw}. The base tag
     *       (stripping {@code :edtf} from the key) is NOT touched — other
     *       rules handle base ↔ edtf reconciliation.</li>
     *   <li>Invalid and unnormalizable: error, no autofix.</li>
     *   <li>Invalid but base tag exists and is valid: in addition to the
     *       transformation warning (or error), emit a separate error noting
     *       the base ↔ edtf mismatch cannot be resolved.</li>
     * </ul>
     *
     * <p>For {@code start_date:edtf} and {@code end_date:edtf} specifically,
     * <p>This method handles invalid-{@code :edtf} detection for ALL
     * {@code :edtf} keys uniformly, including {@code start_date:edtf} and
     * {@code end_date:edtf}. The main {@code checkDateFamily} path
     * deliberately skips invalid-{@code :edtf} branches to avoid
     * double-warning.
     */
    private void checkAllEdtfKeys(OsmPrimitive p) {
        for (String key : p.keySet()) {
            if (!key.endsWith(":edtf")) continue;
            // The :edtf:raw sibling is a scaffold, not an :edtf key itself.
            if (key.endsWith(":edtf:raw")) continue;

            String value = p.get(key);
            if (value == null || value.isEmpty()) continue;
            if (DateNormalizer.looksLikeValidEdtf(value)) continue; // OK.
            // Values with a leading backslash are handled by the Rule A/C/D*
            // path (checkStartEndEqualityAndBackslash) for start_date:edtf,
            // which emits the same unified messages. Skip here to avoid
            // double-firing. For other :edtf keys (e.g. birth_date:edtf),
            // no such rule exists — we still catch them here.
            if (value.startsWith("\\") && key.equals("start_date:edtf")) continue;

            String baseKey = key.substring(0, key.length() - ":edtf".length());
            String baseValue = p.get(baseKey);
            String rawSibling = key + ":raw";

            // Try to normalize the invalid :edtf value.
            Optional<String> normalized = DateNormalizer.toEdtf(value);
            boolean fixable = normalized.isPresent()
                && DateNormalizer.looksLikeValidEdtf(normalized.get());

            if (fixable) {
                String newEdtf = normalized.get();
                List<Command> cmds = new ArrayList<>();
                if (p.get(rawSibling) == null) {
                    cmds.add(new ChangePropertyCommand(Arrays.asList(p), rawSibling, value));
                }
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), key, newEdtf));
                Command fix = new SequenceCommand(tr("Normalize {0}", key), cmds);
                errors.add(TestError.builder(this, Severity.WARNING,
                                             CODE_ANY_EDTF_INVALID_FIXABLE)
                    .message(tr("[ohm] Invalid date - *_date:edtf; fixable, please review suggestion"),
                             tr("{0}={1} is not valid EDTF. Normalize to {2} and "
                              + "preserve original in {3}?",
                                key, value, newEdtf, rawSibling))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            } else {
                errors.add(TestError.builder(this, Severity.WARNING,
                                             CODE_EDTF_INVALID_NO_BASE)
                    .message(tr("[ohm] Invalid date - *_date:edtf; unfixable, please review"),
                             tr("{0}={1} is not valid EDTF and cannot be normalized. "
                              + "Manual review needed.",
                                key, value))
                    .primitives(p)
                    .build());
            }

            // If base tag is also present, emit a separate mismatch warning —
            // we can't reconcile base with an invalid :edtf, so the user needs
            // to decide which one is authoritative.
            if (baseValue != null && !baseValue.isEmpty()) {
                errors.add(TestError.builder(this, Severity.WARNING,
                                             CODE_ANY_EDTF_BASE_MISMATCH_UNRESOLVABLE)
                    .message(tr("[ohm] Date mismatch - *_date does not match *_date:edtf; unfixable, please review"),
                             tr("{0}={1} is invalid, so it cannot be compared to "
                              + "{2}={3}. After normalizing {0}, verify that {2} "
                              + "still agrees.",
                                key, value, baseKey, baseValue))
                    .primitives(p)
                    .build());
            }
        }
    }

    /** True if the primitive's last editor is the trusted cleanup bot. */
    private static boolean lastEditorIsTrustedBot(OsmPrimitive p) {
        return p.getUser() != null
            && TRUSTED_BOT_USER.equals(p.getUser().getName());
    }

    /**
     * Validate the full family of tags for one base key ({@code start_date}
     * or {@code end_date}). Dispatches to branches based on which siblings
     * are present.
     */
    private void checkDateFamily(OsmPrimitive p, String baseKey) {
        String base = p.get(baseKey);
        String edtf = p.get(baseKey + ":edtf");
        String raw = p.get(baseKey + ":raw");

        // --- "present" magic value ---
        // If base (or raw) is the literal word "present" (case-insensitive),
        // it means "no end date / ongoing." This has special handling:
        //   - If raw is already "present" and base/edtf are absent: the
        //     intended state. Skip all further checks silently.
        //   - If raw is "present" but base/edtf are still set: the user
        //     partially reverted the fix; still skip (don't re-normalize
        //     "present" through the raw path, it would fail).
        //   - If base is literally "present": warn and offer the fix.
        if (raw != null && "present".equalsIgnoreCase(raw)) {
            return; // Intentional "ongoing" state; don't flag.
        }
        if (base != null && "present".equalsIgnoreCase(base)) {
            if ("end_date".equals(baseKey)) {
                // end_date=present: legitimate "ongoing" intent, offer to
                // convert to the canonical empty end_date + :raw=present form.
                List<Command> cmds = new ArrayList<>();
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey, null));
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey + ":edtf", null));
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey + ":raw", base));
                Command fix = new SequenceCommand(tr("Mark {0} as ongoing", baseKey), cmds);
                errors.add(TestError.builder(this, Severity.WARNING, CODE_PRESENT_MARKER)
                    .message(tr("[ohm] Invalid date - end_date=present; autofix to no end_date"),
                             tr("{0}={1} means an ongoing feature. Clear base and :edtf, mark with :raw={1}?",
                                baseKey, base))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            } else {
                // start_date=present: not a meaningful date value. Delete start_date and :edtf.
                List<Command> cmds = new ArrayList<>();
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey, null));
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey + ":edtf", null));
                Command fix = new SequenceCommand(tr("Delete {0}", baseKey), cmds);
                errors.add(TestError.builder(this, Severity.WARNING, CODE_PRESENT_START_DATE)
                    .message(tr("[ohm] Invalid date - start_date=present; autofix to no start_date"),
                             tr("{0}={1}: ''present'' describes an ongoing state, not a start point. "
                              + "''present'' is only valid as end_date. "
                              + "Delete {0} and {0}:edtf?",
                                baseKey, base))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            }
            return;
        }

        // If the base value already triggered an "ambiguous, no fix" warning
        // elsewhere (bare negative year, trailing hyphen), don't also report
        // it as unparseable here — the ambiguity warning is more informative.
        if (base != null
            && (BARE_NEGATIVE_YEAR.matcher(base).matches()
                || TRAILING_HYPHEN.matcher(base).matches())
            && raw == null
            && edtf == null) {
            return;
        }

        // Ambiguous YY00s input — report on whichever tag carries the ambiguous
        // value (prefer base, then raw).
        String ambiguousSource = null;
        String ambiguousKey = null;
        if (base != null && AMBIGUOUS_CENTURY_DECADE.matcher(base).matches()) {
            ambiguousSource = base;
            ambiguousKey = baseKey;
        } else if (raw != null && AMBIGUOUS_CENTURY_DECADE.matcher(raw).matches()) {
            ambiguousSource = raw;
            ambiguousKey = baseKey + ":raw";
        }
        if (ambiguousSource != null) {
            // Emit the two ambiguity warnings and skip the normal flow —
            // one of the two ambiguity fixes covers what the normalization
            // warning would have offered, so suppressing the normal flow
            // avoids a redundant third warning.
            emitAmbiguityWarnings(p, baseKey, ambiguousKey, ambiguousSource);
            return;
        }

        if (raw != null) {
            checkWithRaw(p, baseKey, base, edtf, raw);
        } else {
            checkWithoutRaw(p, baseKey, base, edtf);
        }
    }

    /**
     * Emit the two ambiguity warnings for a {@code YY00s} input — one
     * offering the decade interpretation, one offering the century.
     */
    private void emitAmbiguityWarnings(OsmPrimitive p, String baseKey,
                                       String sourceKey, String sourceValue) {
        // Parse out ~, BCE/BC, and the YY00 numeric part from e.g. "~1800s BCE".
        String working = sourceValue;
        String bceSuffix = "";
        if (working.endsWith(" BCE")) {
            bceSuffix = " BCE";
            working = working.substring(0, working.length() - 4);
        } else if (working.endsWith(" BC")) {
            bceSuffix = " BC";
            working = working.substring(0, working.length() - 3);
        }
        boolean circa = working.startsWith("~");
        if (circa) working = working.substring(1);
        // working is now like "1800s"; drop trailing 's' to get "1800"
        String yy00 = working.substring(0, working.length() - 1);

        // --- Decade interpretation ---
        String decadeInput = (circa ? "~" : "") + yy00 + "s" + bceSuffix;
        Optional<String> decadeEdtf = DateNormalizer.toEdtf(decadeInput);
        if (decadeEdtf.isPresent()) {
            Optional<String> decadeBase = "start_date".equals(baseKey)
                ? DateNormalizer.lowerBoundIso(decadeEdtf.get())
                : DateNormalizer.upperBoundIso(decadeEdtf.get());
            Command fix = buildTripleFix(p, baseKey, decadeBase.orElse(null),
                                         decadeEdtf.get(), sourceValue);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_AMBIGUOUS_DECADE)
                .message(tr("[ohm] Ambiguous date - unclear century/decade date; autofix as decade"),
                         tr("{0}={1} as a decade: {0}={2}, :edtf={3}",
                            sourceKey, sourceValue,
                            decadeBase.orElse("?"), decadeEdtf.get()))
                .primitives(p)
                .fix(() -> fix)
                .build());
        }

        // --- Century interpretation ---
        // Century N corresponds to YY00s where YY = N-1. So 1800s → C19.
        int yy = Integer.parseInt(yy00.substring(0, yy00.length() - 2));
        int centuryNumber = yy + 1;
        String centuryInput = (circa ? "~" : "") + "C" + centuryNumber + bceSuffix;
        Optional<String> centuryEdtf = DateNormalizer.toEdtf(centuryInput);
        if (centuryEdtf.isPresent()) {
            Optional<String> centuryBase = "start_date".equals(baseKey)
                ? DateNormalizer.lowerBoundIso(centuryEdtf.get())
                : DateNormalizer.upperBoundIso(centuryEdtf.get());
            Command fix = buildTripleFix(p, baseKey, centuryBase.orElse(null),
                                         centuryEdtf.get(), sourceValue);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_AMBIGUOUS_CENTURY)
                .message(tr("[ohm] Ambiguous date - unclear century/decade date; autofix as century"),
                         tr("{0}={1} as a century: {0}={2}, :edtf={3}",
                            sourceKey, sourceValue,
                            centuryBase.orElse("?"), centuryEdtf.get()))
                .primitives(p)
                .fix(() -> fix)
                .build());
        }
    }

    /**
     * Check the date family when {@code :raw} is present. {@code :raw} is
     * treated as source of truth; we compute what base and {@code :edtf}
     * should be from {@code :raw} and compare.
     */
    private void checkWithRaw(OsmPrimitive p, String baseKey,
                              String base, String edtf, String raw) {
        Optional<String> expectedEdtfOpt = DateNormalizer.toEdtf(raw);
        if (expectedEdtfOpt.isEmpty()) {
            // :raw is unparseable. Only warn if there's also no salvage route
            // through base or :edtf — otherwise let those paths handle it.
            // "Salvage route" means either already valid, or normalizable.
            boolean baseSalvageable = base != null
                && (DateNormalizer.isIsoCalendarDate(base)
                    || DateNormalizer.toEdtf(base).isPresent());
            boolean edtfSalvageable = edtf != null
                && (DateNormalizer.looksLikeValidEdtf(edtf)
                    || DateNormalizer.toEdtf(edtf).isPresent());
            if (!baseSalvageable && !edtfSalvageable) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_RAW_UNPARSEABLE)
                    .message(tr("[ohm] Invalid date - Unparseable data preserved in *_date:raw tag, "
                              + "no valid *_date:edtf or *_date tags; unfixable, please review."),
                             tr("{0}:raw={1} cannot be normalized, and neither "
                              + "{0} nor {0}:edtf provides a salvageable date.",
                                baseKey, raw))
                    .primitives(p)
                    .build());
            }
            return;
        }
        String expectedEdtf = expectedEdtfOpt.get();
        Optional<String> expectedBaseOpt = "start_date".equals(baseKey)
            ? DateNormalizer.lowerBoundIso(expectedEdtf)
            : DateNormalizer.upperBoundIso(expectedEdtf);
        String expectedBase = expectedBaseOpt.orElse(null);

        boolean baseOk = Objects.equals(base, expectedBase);
        boolean edtfOk = Objects.equals(edtf, expectedEdtf);

        if (baseOk && edtfOk) {
            return; // Consistent triple; nothing to do.
        }

        // If base is a precision refinement of :edtf (more specific, within
        // :edtf's bounds), that's not an inconsistency for message 29 — it's
        // a separate case covered by checkMoreSpecificBase, which fires
        // independently. Skip message 29 in that scenario.
        if (!baseOk && edtfOk
            && base != null && edtf != null
            && isBaseMoreSpecificWithinBounds(base, edtf)) {
            return;
        }

        if (lastEditorIsTrustedBot(p)) {
            // Trust the raw value, assume the bot made a mistake; autofix
            // rewrites base and :edtf from :raw.
            Command fix = buildBaseAndEdtfFix(p, baseKey, expectedBase, expectedEdtf);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_RAW_MISMATCH_BOT)
                .message(tr("[ohm] Suspicious date - *_date:raw exists, but no *_date{:edtf}; autofix to reconstruct *_date and/or *_date:edtf"),
                         tr("{0}:raw={1} implies {0}={2}, {0}:edtf={3}.",
                            baseKey, raw,
                            expectedBase == null ? "(absent)" : expectedBase,
                            expectedEdtf))
                .primitives(p)
                .fix(() -> fix)
                .build());
        } else {
            // Human edit somewhere; offer to delete :raw so the human-edited
            // base/:edtf become canonical.
            Command fix = new ChangePropertyCommand(Arrays.asList(p),
                                                    baseKey + ":raw", null);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_RAW_MISMATCH_HUMAN)
                .message(tr("[ohm] Date mismatch - across date tags; autofix by deleting :raw"),
                         tr("{0} and {0}:edtf don''t match {0}:raw={1}. "
                            + "Delete the machine-generated :raw tag?",
                            baseKey, raw))
                .primitives(p)
                .fix(() -> fix)
                .build());
        }
    }

    /**
     * Check the date family when {@code :raw} is absent. Dispatches further
     * based on whether {@code :edtf} and/or base are present.
     */
    private void checkWithoutRaw(OsmPrimitive p, String baseKey,
                                 String base, String edtf) {
        if (edtf != null) {
            checkEdtfWithoutRaw(p, baseKey, base, edtf);
        } else if (base != null) {
            checkBaseOnly(p, baseKey, base);
        }
        // Both absent: nothing to do. (Rule A already handled missing
        // start_date for the whole primitive.)
    }

    /**
     * {@code :raw} absent, {@code :edtf} present.
     *
     * <p>Invalid-{@code :edtf} cases are handled by {@link #checkAllEdtfKeys},
     * which fires uniformly for any {@code :edtf} key. This method only
     * handles the valid-{@code :edtf} branches here.
     */
    private void checkEdtfWithoutRaw(OsmPrimitive p, String baseKey,
                                     String base, String edtf) {
        if (!DateNormalizer.looksLikeValidEdtf(edtf)) {
            // Let checkAllEdtfKeys handle the invalid-:edtf case.
            return;
        }

        // :edtf is valid — derive expected base.
        Optional<String> expectedBaseOpt = "start_date".equals(baseKey)
            ? DateNormalizer.lowerBoundIso(edtf)
            : DateNormalizer.upperBoundIso(edtf);
        String expectedBase = expectedBaseOpt.orElse(null);

        if (base == null) {
            // Case 1: suggest adding the base tag (no :raw written — no
            // original human input to preserve).
            if (expectedBase != null) {
                Command fix = new ChangePropertyCommand(Arrays.asList(p),
                                                        baseKey, expectedBase);
                errors.add(TestError.builder(this, Severity.WARNING, CODE_EDTF_MISSING_BASE)
                    .message(tr("[ohm] Date mismatch - *_date:edtf & no *_date tag; autofix *_date based on *_date:edtf"),
                             tr("{0}:edtf={1} implies {0}={2}.",
                                baseKey, edtf, expectedBase))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            }
            return;
        }

        if (base.equals(expectedBase)) {
            return; // Case 2: consistent.
        }

        // If base is a refinement (more specific, within bounds), that's
        // not covered by this message — checkMoreSpecificBase handles it
        // as a separate warning. Skip here to avoid firing both.
        if (isBaseMoreSpecificWithinBounds(base, edtf)) {
            return;
        }

        // Case 3: base and :edtf disagree, no :raw to reconcile against.
        errors.add(TestError.builder(this, Severity.WARNING, CODE_EDTF_BASE_MISMATCH)
            .message(tr("[ohm] Date mismatch - *_date does not match *_date:edtf; unfixable, please review"),
                     tr("{0}={1} but {0}:edtf={2} implies {0}={3}. "
                        + "Manual review needed.",
                        baseKey, base, edtf, expectedBase))
            .primitives(p)
            .build());
    }

    /**
     * Fires when base has finer precision than {@code :edtf} and falls
     * within {@code :edtf}'s bounds — e.g. {@code start_date=1890-03-15},
     * {@code start_date:edtf=1890~}. This is semantically legitimate (a
     * refinement), but isn't aligned with the convention of deriving base
     * from {@code :edtf}'s lower/upper bound, so we flag for review.
     *
     * <p>Fires regardless of whether {@code :raw} exists, regardless of
     * last editor. Has no autofix — determining the authoritative value
     * is a human judgment call.
     */
    private void checkMoreSpecificBase(OsmPrimitive p, String baseKey) {
        String base = p.get(baseKey);
        String edtf = p.get(baseKey + ":edtf");
        if (base == null || edtf == null) return;
        if (!DateNormalizer.looksLikeValidEdtf(edtf)) return;
        if (!isBaseMoreSpecificWithinBounds(base, edtf)) return;

        errors.add(TestError.builder(this, Severity.WARNING,
                                     CODE_MORE_SPECIFIC_BASE)
            .message(tr("[ohm] Date mismatch - *_date more precise than *_date:edtf; autofix *_date:edtf=*_date"),
                     tr("{0}={1} is more specific than {0}:edtf={2}. "
                      + "Manual review needed: confirm which value is authoritative.",
                        baseKey, base, edtf))
            .primitives(p)
            .build());
    }

    /**
     * True when {@code base} is a finer-precision ISO date than {@code edtf}
     * (YYYY-MM or YYYY-MM-DD when edtf is YYYY; YYYY-MM-DD when edtf is YYYY-MM)
     * AND {@code base} falls within {@code edtf}'s lower/upper bounds.
     *
     * <p>Used by both {@link #checkMoreSpecificBase} (which fires on this
     * state) and {@link #checkWithRaw} (which suppresses the mismatch
     * message in this state).
     */
    private static boolean isBaseMoreSpecificWithinBounds(String base, String edtf) {
        // base must be valid ISO calendar form (YYYY, YYYY-MM, or YYYY-MM-DD).
        if (!DateNormalizer.isIsoCalendarDate(base)) return false;
        int basePrecision = isoPrecision(base);
        if (basePrecision < 0) return false;

        // Need an EDTF precision to compare against.
        int edtfPrecision = edtfPrecision(edtf);
        if (edtfPrecision < 0) return false;

        if (basePrecision <= edtfPrecision) return false; // not more specific

        // Check bounds inclusion.
        Optional<String> lower = DateNormalizer.lowerBoundIso(edtf);
        Optional<String> upper = DateNormalizer.upperBoundIso(edtf);
        if (lower.isEmpty() || upper.isEmpty()) return false;

        // Compare ISO dates lexicographically (safe for fixed-width YYYY-MM-DD).
        // Pad base to full YYYY-MM-DD for robust comparison.
        String basePadded = padIsoToDay(base);
        String lowerPadded = padIsoToDay(lower.get());
        String upperPadded = padIsoToDayUpper(upper.get());
        return basePadded.compareTo(lowerPadded) >= 0
            && basePadded.compareTo(upperPadded) <= 0;
    }

    /** Returns 1 for YYYY, 2 for YYYY-MM, 3 for YYYY-MM-DD, -1 otherwise. */
    private static int isoPrecision(String iso) {
        if (iso == null) return -1;
        // YYYY (positive or negative, but simple length-based detection)
        if (iso.matches("-?\\d{4,}")) return 1;
        if (iso.matches("-?\\d{4,}-\\d{2}")) return 2;
        if (iso.matches("-?\\d{4,}-\\d{2}-\\d{2}")) return 3;
        return -1;
    }

    /**
     * Returns precision of an EDTF value: 1 for year-level, 2 for month-level,
     * 3 for day-level. -1 if we can't determine, or if it's an interval
     * (intervals don't have a single precision). Qualifiers like {@code ~},
     * {@code ?}, {@code %} are stripped.
     */
    private static int edtfPrecision(String edtf) {
        if (edtf == null || edtf.isEmpty()) return -1;
        // Intervals and sets don't have a single precision level.
        if (edtf.contains("/") || edtf.contains("..") || edtf.contains("[")
            || edtf.contains("{")) return -1;

        // Strip trailing qualifiers (~, ?, %).
        String v = edtf;
        while (!v.isEmpty()) {
            char c = v.charAt(v.length() - 1);
            if (c == '~' || c == '?' || c == '%') v = v.substring(0, v.length() - 1);
            else break;
        }
        return isoPrecision(v);
    }

    /** Pads a YYYY or YYYY-MM to YYYY-MM-DD at the lower bound (01-01 etc). */
    private static String padIsoToDay(String iso) {
        int p = isoPrecision(iso);
        if (p == 3) return iso;
        if (p == 2) return iso + "-01";
        if (p == 1) return iso + "-01-01";
        return iso; // shouldn't happen in practice
    }

    /** Pads a YYYY or YYYY-MM to YYYY-MM-DD at the upper bound. */
    private static String padIsoToDayUpper(String iso) {
        int p = isoPrecision(iso);
        if (p == 3) return iso;
        if (p == 2) {
            // Last day of that month — cheap approximation: use LocalDate
            // to get the actual last day.
            try {
                java.time.YearMonth ym = java.time.YearMonth.parse(iso);
                return iso + "-" + String.format("%02d", ym.lengthOfMonth());
            } catch (java.time.format.DateTimeParseException e) {
                return iso + "-31";
            }
        }
        if (p == 1) return iso + "-12-31";
        return iso;
    }

    /**
     * {@code :raw} and {@code :edtf} both absent — only a base tag. Check
     * whether it's already valid ISO; if not, offer to normalize.
     *
     * <p>Four possible paths:
     * <ol>
     *   <li>Julian-calendar date ({@code j:YYYY-MM-DD}) or Julian day number
     *       ({@code jd:NNNNNNN}) — convert to Gregorian ISO for base, preserve
     *       original in {@code :raw}, skip {@code :edtf} (EDTF doesn't support
     *       these forms).</li>
     *   <li>Already valid ISO — no action.</li>
     *   <li>Parseable by the OHM-shorthand normalizer (e.g. {@code ~1850},
     *       {@code C19}) — fix writes the triple with the original going to
     *       {@code :raw}.</li>
     *   <li>Valid EDTF but not OHM shorthand (e.g. {@code 200X},
     *       {@code [1850..1900]}) — promote to {@code :edtf} as-is,
     *       derive a base from its bounds. No {@code :raw} written,
     *       because clean EDTF input is not human shorthand.</li>
     * </ol>
     */
    private void checkBaseOnly(OsmPrimitive p, String baseKey, String base) {
        // Path 0: Julian calendar or Julian day number.
        Optional<String> julianGregorian = DateNormalizer.tryConvertJulian(base);
        if (julianGregorian.isPresent()) {
            String gregorian = julianGregorian.get();
            List<Command> cmds = new ArrayList<>();
            // Per OHM wiki convention, calendar-conversion annotation lives in
            // :note, not :raw. The wiki's start_date page explicitly asks for
            // start_date:note=* to explain non-trivial calendar conversions.
            // :raw is reserved for machine-generated scaffold of shorthand
            // normalization; it shouldn't carry semantic notes.
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey + ":note",
                tr("Converted from {0}", base)));
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey, gregorian));
            // Deliberately no :edtf — EDTF has no standard form for Julian dates.
            Command fix = new SequenceCommand(tr("Convert Julian date for {0}", baseKey), cmds);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_JULIAN_CONVERSION)
                .message(tr("[ohm] Invalid date - Julian date; fixable, please review Gregorian conversion"),
                         tr("{0}={1} \u2192 {0}={2} (Gregorian), {0}:note added",
                            baseKey, base, gregorian))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        if (DateNormalizer.isIsoCalendarDate(base)) {
            return; // Already valid; no :edtf / :raw needed.
        }

        // Path 2: try OHM-shorthand normalization first.
        Optional<String> edtfOpt = DateNormalizer.toEdtf(base);
        if (edtfOpt.isPresent()) {
            String derivedEdtf = edtfOpt.get();
            Optional<String> derivedBaseOpt = "start_date".equals(baseKey)
                ? DateNormalizer.lowerBoundIso(derivedEdtf)
                : DateNormalizer.upperBoundIso(derivedEdtf);
            String derivedBase = derivedBaseOpt.orElse(null);

            Command fix = buildTripleFix(p, baseKey, derivedBase, derivedEdtf, base);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_NEEDS_NORMALIZATION)
                .message(tr("[ohm] Invalid date - *_date; fixable, please review suggestion"),
                         tr("{0}={1} \u2192 {0}={2}, {0}:edtf={3}, {0}:raw={1}",
                            baseKey, base,
                            derivedBase == null ? "(absent)" : derivedBase,
                            derivedEdtf))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // Path 3: preprocessing produced something we can work with, but no
        // OHM shorthand regex matched. Two sub-cases:
        //
        //   3a. The original input was already valid EDTF (e.g. "200X",
        //       "[1850..1900]"). Just misplaced — promote to :edtf, derive
        //       base. No :raw, because the input was already canonical EDTF.
        //
        //   3b. Preprocessing produced valid EDTF as the result of a semantic
        //       transformation (e.g. "first half of 1943" → "1943-01/1943-06",
        //       "between 1915 and 1920" → "1915..1920" → "1915/1920"). The
        //       original is *not* EDTF; the transformation is material.
        //       Preserve original in :raw, write triple.
        String cleaned = DateNormalizer.preprocess(base);
        if (cleaned != null && DateNormalizer.looksLikeValidEdtf(cleaned)) {
            Optional<String> passthroughBaseOpt = "start_date".equals(baseKey)
                ? DateNormalizer.lowerBoundIso(cleaned)
                : DateNormalizer.upperBoundIso(cleaned);
            String passthroughBase = passthroughBaseOpt.orElse(null);

            // Sub-case 3a vs 3b: if the ORIGINAL value looksLikeValidEdtf,
            // preprocessing was cosmetic (whitespace, etc.) and no :raw is
            // needed. Otherwise it was semantic and :raw preserves the original.
            boolean originalWasEdtf = DateNormalizer.looksLikeValidEdtf(base);

            Command fix;
            String messageTitle;
            if (originalWasEdtf) {
                fix = buildBaseAndEdtfFix(p, baseKey, passthroughBase, cleaned);
                messageTitle = tr("[ohm] Invalid date - *_date contains a readable EDTF date; fixable, please review suggestion");
            } else {
                fix = buildTripleFix(p, baseKey, passthroughBase, cleaned, base);
                messageTitle = tr("[ohm] Invalid date - *_date; fixable, please review suggestion");
            }
            errors.add(TestError.builder(this, Severity.WARNING, CODE_NEEDS_NORMALIZATION)
                .message(messageTitle,
                         tr("{0}={1} \u2192 {0}={2}, {0}:edtf={3}",
                            baseKey, base,
                            passthroughBase == null ? "(absent)" : passthroughBase,
                            cleaned))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // Path 1-false: unparseable by any route.
        errors.add(TestError.builder(this, Severity.WARNING, CODE_UNPARSEABLE)
            .message(tr("[ohm] Invalid date - *_date cannot be read; unfixable, please review"),
                     tr("{0}={1} cannot be normalized.", baseKey, base))
            .primitives(p)
            .build());
    }

    // --- Fix builders --------------------------------------------------------

    /**
     * Build a fix that writes the full triple: sets base and {@code :edtf},
     * moves the original value into {@code :raw}. If {@code newBase} is
     * null, the base tag is removed (unbounded-bound case).
     */
    private Command buildTripleFix(OsmPrimitive p, String baseKey,
                                   String newBase, String newEdtf, String rawValue) {
        List<Command> cmds = new ArrayList<>();
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey + ":raw", rawValue));
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey + ":edtf", newEdtf));
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey, newBase));
        return new SequenceCommand(tr("Normalize {0}", baseKey), cmds);
    }

    /**
     * Build a fix that writes only base and {@code :edtf}. Used for the
     * bot-mismatch case where {@code :raw} is already set and shouldn't be
     * touched.
     */
    private Command buildBaseAndEdtfFix(OsmPrimitive p, String baseKey,
                                        String newBase, String newEdtf) {
        List<Command> cmds = new ArrayList<>();
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey + ":edtf", newEdtf));
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), baseKey, newBase));
        return new SequenceCommand(tr("Sync {0} and :edtf to :raw", baseKey), cmds);
    }
}
