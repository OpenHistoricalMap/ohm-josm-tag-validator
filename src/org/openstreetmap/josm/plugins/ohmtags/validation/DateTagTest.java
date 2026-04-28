// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.ohmtags.validation;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
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
    //   fires CODE_EDTF_INVALID_NO_BASE (unified unfixable message).
    protected static final int CODE_EDTF_BASE_MISMATCH = 4210;
    protected static final int CODE_EDTF_MISSING_BASE = 4211;
    protected static final int CODE_SUSPICIOUS_YEAR_START = 4212;
    protected static final int CODE_SUSPICIOUS_YEAR_END = 4213;
    protected static final int CODE_SUSPICIOUS_OFF_BY_ONE = 4214;
    protected static final int CODE_START_AFTER_END = 4215;
    protected static final int CODE_FUTURE_DATE = 4216;
    protected static final int CODE_INVALID_MONTH = 4217;
    protected static final int CODE_INVALID_DAY = 4218;
    // 4219 (CODE_AMBIGUOUS_NEGATIVE) retired: forum feedback (2026-04-21)
    //   established that negative astronomical years are legitimate OHM
    //   notation; the rule's false-positive rate outweighed signal value.
    //   The BARE_NEGATIVE_YEAR pattern is retained — checkDateFamily still
    //   uses it to suppress the unparseable-warning path on bare negatives.
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
    // 4230 (CODE_ANY_EDTF_BASE_MISMATCH_UNRESOLVABLE) retired: redundant
    //   companion to the unified invalid-:edtf messages (4208/4228); user
    //   already gets an actionable warning without it.
    protected static final int CODE_PRESENT_START_DATE = 4231;
    protected static final int CODE_MORE_SPECIFIC_BASE = 4232;
    protected static final int CODE_JULIAN_CONVERSION = 4233;
    // Chronology-relation structural checks. All findings attach only to the
    // parent chronology relation; offending member ids are listed in the
    // description text. See checkChronologyConsistency.
    protected static final int CODE_CHRONOLOGY_OUTSIDE_PARENT = 4234;
    protected static final int CODE_CHRONOLOGY_OVERLAP = 4235;
    protected static final int CODE_CHRONOLOGY_GAP = 4236;
    protected static final int CODE_CHRONOLOGY_MISSING_DATE = 4237;
    protected static final int CODE_CHRONOLOGY_DUPLICATE = 4238;
    protected static final int CODE_CHRONOLOGY_MEMBER_NO_DATES = 4239;

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

    /**
     * Matches a strict ISO year ({@code YYYY} or {@code -YYYY}). Used by the
     * chronology consistency check when comparing member date ranges — only
     * strict {@code start_date}/{@code end_date} forms participate.
     */
    private static final Pattern ISO_STRICT_YEAR =
        Pattern.compile("^(-?\\d{1,4})$");

    /**
     * Matches keys that are part of the OHM date family — anything ending in
     * {@code _date} or containing {@code _date:} as a subkey separator. Used
     * by the chronology duplicate-predecessor rule (4238) to filter date-
     * related tags out of equality comparisons.
     */
    private static final Pattern DATE_RELATED_KEY =
        Pattern.compile("_date(?::|$)");

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
        if ("chronology".equals(r.get("type"))) {
            checkChronologyConsistency(r);
        }
    }

    private void checkPrimitive(OsmPrimitive p) {
        // Rule 4200: man-made features should have start_date.
        //
        // Severity is WARNING, not ERROR, matching iD's validator. The OHM
        // wiki says "should" (not "must") for this tag; speculative start
        // dates are worse than missing ones, so this is a nudge to research
        // rather than a blocker to save.
        //
        // The trigger is a positive allowlist (see isManMade): the primitive
        // must carry at least one tag that explicitly marks it as built or
        // established. The previous negative test ("any tag except natural")
        // misfired badly on boundary relations whose member ways carry
        // boundary=* but no independent date — see issue #4 (forum: 3344
        // warnings on r/2828412 / British Empire 1921-1922).
        if (p.get("start_date") == null && isManMade(p)) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_MISSING_START_DATE)
                .message(tr("[ohm] Suspicious date - man-made object without start_date; unfixable, please review"),
                         tr("Please make every effort to attempt a reasonable range "
                          + "for the `start_date:edtf` tag and provide an explanation "
                          + "in the `start_date:source` tag."))
                .primitives(p)
                .build());
        }

        for (String baseKey : BASE_KEYS) {
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

    /**
     * Keys whose presence (any value) marks the primitive as built /
     * established and therefore in scope for the missing-{@code start_date}
     * warning. Curated to match OSM/OHM tagging conventions for features
     * that have a discrete creation date.
     */
    private static final Set<String> MANMADE_KEYS = new HashSet<>(Arrays.asList(
        "building", "building:part",
        "highway", "railway", "aeroway", "aerialway",
        "bridge", "tunnel",
        "man_made", "power", "pipeline",
        "amenity", "shop", "office", "craft",
        "tourism", "historic", "military", "emergency",
        "public_transport", "telecom",
        "leisure", "barrier"
    ));

    /**
     * Keys that mark the primitive as built / established <em>unless</em>
     * the value is in the per-key denylist. Used for tags that span both
     * man-made and natural domains (e.g. {@code waterway=canal} vs.
     * {@code waterway=river}).
     */
    private static final Map<String, Set<String>> MANMADE_DENYLIST = makeDenylist();

    private static Map<String, Set<String>> makeDenylist() {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("landuse", new HashSet<>(Arrays.asList(
            "forest", "meadow", "grass", "wood", "scrub", "heath")));
        m.put("waterway", new HashSet<>(Arrays.asList(
            "river", "stream", "brook", "riverbank", "tidal_channel", "wadi")));
        m.put("place", new HashSet<>(Arrays.asList(
            "island", "islet", "archipelago", "peninsula", "cape")));
        return m;
    }

    /**
     * True if the primitive carries at least one tag from the man-made
     * allowlist. See {@link #MANMADE_KEYS} and {@link #MANMADE_DENYLIST}.
     *
     * <p>Two relation-only signals on top of the per-key sets:
     * <ul>
     *   <li>{@code boundary=*} on a {@link Relation} (the political entity
     *       has a discrete date; member ways/nodes are excluded so we don't
     *       fire on every line segment of a boundary).</li>
     *   <li>{@code type=route} on a {@link Relation} (bus, train, hiking,
     *       ferry routes have an operating-start date).</li>
     * </ul>
     *
     * <p>An {@code addr:*} key (any value) is a positive signal too — an
     * address implies a built / locatable feature.
     */
    private static boolean isManMade(OsmPrimitive p) {
        boolean isRelation = p instanceof Relation;
        for (String key : p.keySet()) {
            if (MANMADE_KEYS.contains(key)) return true;
            if (key.startsWith("addr:")) return true;
            Set<String> deny = MANMADE_DENYLIST.get(key);
            if (deny != null && !deny.contains(p.get(key))) return true;
            if (isRelation) {
                if ("boundary".equals(key)) return true;
                if ("type".equals(key) && "route".equals(p.get(key))) return true;
            }
        }
        return false;
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
     * Detect values like {@code YYYY-01-01} and {@code YYYY-12-31}, which are
     * commonly artifacts of data imports that forced a precise date where
     * only the year was known. Two kinds of suspicion:
     *
     * <p><b>False precision at year boundaries.</b> A {@code start_date}
     * of {@code YYYY-01-01} or {@code end_date} of {@code YYYY-12-31} is
     * <i>possibly</i> an artifact, but Jan 1 / Dec 31 are also legitimate
     * dates for laws taking effect, fiscal-year boundaries, and many other
     * real events. Forum feedback (2026-04-21) flagged auto-removal as too
     * aggressive, so these are now no-fix warnings — the user must apply
     * any year-trim manually if it's appropriate.
     *
     * <p><b>Off-by-one at year boundaries.</b> A {@code start_date} of
     * {@code YYYY-12-31} likely means "started at the beginning of year
     * {@code YYYY+1}" and should probably be {@code YYYY+1}. Similarly an
     * {@code end_date} of {@code YYYY-01-01} likely means "ended at the
     * end of year {@code YYYY-1}". This is a clearer typo signal than the
     * false-precision case, so it keeps its autofix (offers the shifted
     * year).
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
            // Possible false precision: start of year. We do not autofix
            // because Jan 1 is also a legitimate date for many real
            // events (laws taking effect, fiscal year boundaries, etc.);
            // forum feedback flagged the auto-removal as too aggressive.
            String yearStr = padAstronomicalYear(year);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SUSPICIOUS_YEAR_START)
                .message(tr("[ohm] Suspicious date - 01-01 start_date; unfixable, please review"),
                         tr("{0}={1}: if the exact day is unknown, change to {0}={2}.",
                            baseKey, value, yearStr))
                .primitives(p)
                .build());
        } else if (isEnd && isDec31) {
            // Possible false precision: end of year. Same reasoning as
            // above — Dec 31 is a real date too often to autofix.
            String yearStr = padAstronomicalYear(year);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SUSPICIOUS_YEAR_END)
                .message(tr("[ohm] Suspicious date - 12-31 end_date; unfixable, please review"),
                         tr("{0}={1}: if the exact day is unknown, change to {0}={2}.",
                            baseKey, value, yearStr))
                .primitives(p)
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
                errors.add(TestError.builder(this, Severity.ERROR, CODE_INVALID_MONTH)
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
                errors.add(TestError.builder(this, Severity.ERROR, CODE_INVALID_DAY)
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
                errors.add(TestError.builder(this, Severity.ERROR, CODE_CALENDAR_INVALID)
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
                errors.add(TestError.builder(this, Severity.ERROR, CODE_INVALID_MONTH)
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
                errors.add(TestError.builder(this, Severity.ERROR,
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
                errors.add(TestError.builder(this, Severity.ERROR,
                                             CODE_ANY_EDTF_INVALID_FIXABLE)
                    .message(tr("[ohm] Invalid date - *_date:edtf; fixable, please review suggestion"),
                             tr("start_date:edtf={0}: strip leading backslash to {1}?",
                                startEdtf, backslashRemainder))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            } else {
                // Can't even salvage. Fire the unified unfixable warning.
                errors.add(TestError.builder(this, Severity.ERROR,
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
                errors.add(TestError.builder(this, Severity.ERROR,
                                             CODE_ANY_EDTF_INVALID_FIXABLE)
                    .message(tr("[ohm] Invalid date - *_date:edtf; fixable, please review suggestion"),
                             tr("{0}={1} is not valid EDTF. Normalize to {2} and "
                              + "preserve original in {3}?",
                                key, value, newEdtf, rawSibling))
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            } else {
                errors.add(TestError.builder(this, Severity.ERROR,
                                             CODE_EDTF_INVALID_NO_BASE)
                    .message(tr("[ohm] Invalid date - *_date:edtf; unfixable, please review"),
                             tr("{0}={1} is not valid EDTF and cannot be normalized. "
                              + "Manual review needed.",
                                key, value))
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
                errors.add(TestError.builder(this, Severity.ERROR, CODE_PRESENT_MARKER)
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
                errors.add(TestError.builder(this, Severity.ERROR, CODE_PRESENT_START_DATE)
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
                errors.add(TestError.builder(this, Severity.ERROR, CODE_RAW_UNPARSEABLE)
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
            errors.add(TestError.builder(this, Severity.ERROR, CODE_JULIAN_CONVERSION)
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
            errors.add(TestError.builder(this, Severity.ERROR, CODE_NEEDS_NORMALIZATION)
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
            errors.add(TestError.builder(this, Severity.ERROR, CODE_NEEDS_NORMALIZATION)
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
        errors.add(TestError.builder(this, Severity.ERROR, CODE_UNPARSEABLE)
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

    // ===== Chronology consistency ============================================

    /** Precision of a strictly-parsed {@code start_date}/{@code end_date}. */
    private enum DatePrecision { YEAR, MONTH, DAY }

    /** A {@code start_date} or {@code end_date} value parsed strictly. */
    private static final class ParsedDate {
        final int year;
        final int month;            // 1-12; 1 if YEAR-only
        final int day;              // 1-31; 1 if not DAY-precision
        final DatePrecision precision;
        final String raw;           // original tag value (for touch-equality)

        ParsedDate(int year, int month, int day, DatePrecision precision, String raw) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.precision = precision;
            this.raw = raw;
        }

        /** Inclusive lower bound of the range this value represents. */
        LocalDate lowerBound() {
            switch (precision) {
                case YEAR:  return LocalDate.of(year, 1, 1);
                case MONTH: return LocalDate.of(year, month, 1);
                case DAY:   return LocalDate.of(year, month, day);
                default:    throw new IllegalStateException();
            }
        }

        /** Inclusive upper bound (e.g., year-only YYYY → Dec 31). */
        LocalDate upperBound() {
            switch (precision) {
                case YEAR:
                    return LocalDate.of(year, 12, 31);
                case MONTH: {
                    LocalDate first = LocalDate.of(year, month, 1);
                    return first.withDayOfMonth(first.lengthOfMonth());
                }
                case DAY:
                    return LocalDate.of(year, month, day);
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /** Per-member state used by chronology rules. */
    private static final class MemberInfo {
        final OsmPrimitive prim;
        final ParsedDate start;     // null if missing or unparseable strictly
        final ParsedDate end;       // null if missing or unparseable strictly

        MemberInfo(OsmPrimitive prim, ParsedDate start, ParsedDate end) {
            this.prim = prim;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Strict parse of {@code YYYY}, {@code YYYY-MM}, or {@code YYYY-MM-DD}.
     * Returns null on anything else (EDTF, Julian, "present", BC strings, etc.).
     * The chronology rules deliberately use only these three forms — see plan.
     */
    private static ParsedDate parseStrictBaseDate(String s) {
        if (s == null) return null;
        Matcher m = FULL_ISO_DATE.matcher(s);
        if (m.matches()) {
            try {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int d = Integer.parseInt(m.group(3));
                LocalDate.of(y, mo, d);
                return new ParsedDate(y, mo, d, DatePrecision.DAY, s);
            } catch (NumberFormatException | java.time.DateTimeException e) {
                return null;
            }
        }
        m = ISO_YEAR_MONTH.matcher(s);
        if (m.matches()) {
            try {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                LocalDate.of(y, mo, 1);
                return new ParsedDate(y, mo, 1, DatePrecision.MONTH, s);
            } catch (NumberFormatException | java.time.DateTimeException e) {
                return null;
            }
        }
        m = ISO_STRICT_YEAR.matcher(s);
        if (m.matches()) {
            try {
                int y = Integer.parseInt(m.group(1));
                LocalDate.of(y, 1, 1);
                return new ParsedDate(y, 1, 1, DatePrecision.YEAR, s);
            } catch (NumberFormatException | java.time.DateTimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Validates a {@code type=chronology} relation against four invariants:
     * (4234) member ranges within the parent's range; (4235) no member-pair
     * date-range overlap; (4236) no gap between consecutive members beyond
     * one unit at the coarser shared precision; (4237) every member has a
     * {@code start_date}, and every non-youngest member also has an
     * {@code end_date}.
     *
     * <p>Comparisons use only strict {@code start_date}/{@code end_date}
     * (no {@code :edtf} or {@code :raw}). All findings attach to the parent
     * relation; offending member ids appear in the description text.
     */
    private void checkChronologyConsistency(Relation r) {
        List<MemberInfo> infos = new ArrayList<>();
        for (RelationMember rm : r.getMembers()) {
            OsmPrimitive m = rm.getMember();
            if (m == null) continue;
            ParsedDate s = parseStrictBaseDate(m.get("start_date"));
            ParsedDate e = parseStrictBaseDate(m.get("end_date"));
            infos.add(new MemberInfo(m, s, e));
        }
        if (infos.isEmpty()) return;

        MemberInfo youngest = identifyYoungest(infos);

        checkChronologyParentRange(r, infos);
        checkChronologyMissingTags(r, infos, youngest);
        checkChronologyOverlap(r, infos, youngest);
        checkChronologyGap(r, infos, youngest);
        checkChronologyDuplicatePredecessor(r, infos);
    }

    /**
     * Youngest member: latest parseable {@code start_date}; ties broken by
     * latest {@code end_date}, with absent {@code end_date} winning (open-
     * ended interpreted as still-current). Members without a parseable
     * {@code start_date} cannot be the youngest.
     */
    private static MemberInfo identifyYoungest(List<MemberInfo> infos) {
        MemberInfo best = null;
        for (MemberInfo mi : infos) {
            if (mi.start == null) continue;
            if (best == null) { best = mi; continue; }
            int cmp = mi.start.lowerBound().compareTo(best.start.lowerBound());
            if (cmp > 0) {
                best = mi;
            } else if (cmp == 0) {
                LocalDate miEnd = mi.end == null ? LocalDate.MAX : mi.end.upperBound();
                LocalDate beEnd = best.end == null ? LocalDate.MAX : best.end.upperBound();
                if (miEnd.isAfter(beEnd)) best = mi;
            }
        }
        return best;
    }

    private void checkChronologyParentRange(Relation r, List<MemberInfo> infos) {
        ParsedDate parentStart = parseStrictBaseDate(r.get("start_date"));
        ParsedDate parentEnd = parseStrictBaseDate(r.get("end_date"));
        if (parentStart == null && parentEnd == null) return;

        String parentRange = "[" + (parentStart == null ? "(no start)" : parentStart.raw)
                           + ".." + (parentEnd == null ? "(no end)" : parentEnd.raw) + "]";

        for (MemberInfo mi : infos) {
            StringBuilder sb = new StringBuilder();
            if (mi.start != null && parentStart != null
                && mi.start.lowerBound().isBefore(parentStart.lowerBound())) {
                sb.append("start_date=").append(mi.start.raw)
                  .append(" before parent start ").append(parentStart.raw);
            }
            if (mi.end != null && parentEnd != null
                && mi.end.upperBound().isAfter(parentEnd.upperBound())) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("end_date=").append(mi.end.raw)
                  .append(" after parent end ").append(parentEnd.raw);
            }
            if (sb.length() == 0) continue;

            errors.add(TestError.builder(this, Severity.ERROR, CODE_CHRONOLOGY_OUTSIDE_PARENT)
                .message(tr("[ohm] Chronology - member date range outside parent chronology range; unfixable, please review"),
                         tr("Member {0} outside parent range {1}: {2}.",
                            formatPrim(mi.prim), parentRange, sb.toString()))
                .primitives(Arrays.asList(r, mi.prim))
                .build());
        }
    }

    private void checkChronologyMissingTags(Relation r, List<MemberInfo> infos,
                                            MemberInfo youngest) {
        for (MemberInfo mi : infos) {
            // Both date tags entirely absent: emit the consolidated 4239
            // instead of two separate "missing start" / "missing end" 4237s.
            // This is the typical incomplete-proxy case and a few one-tag
            // findings would only add noise.
            boolean noStartTag = mi.prim.get("start_date") == null;
            boolean noEndTag = mi.prim.get("end_date") == null;
            if (noStartTag && noEndTag) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_MEMBER_NO_DATES)
                    .message(tr("[ohm] Chronology - member without dates; unfixable, please review"),
                             tr("Member {0} of chronology relation {1} has neither "
                                + "start_date nor end_date.",
                                formatPrim(mi.prim), formatPrim(r)))
                    .primitives(mi.prim)
                    .build());
                continue;
            }

            if (mi.start == null) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_MISSING_DATE)
                    .message(tr("[ohm] Chronology - member missing required date tag; unfixable, please review"),
                             tr("Member {0} of chronology relation {1} is missing start_date.",
                                formatPrim(mi.prim), formatPrim(r)))
                    .primitives(mi.prim)
                    .build());
            }
            if (mi.end == null && mi != youngest) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_MISSING_DATE)
                    .message(tr("[ohm] Chronology - member missing required date tag; unfixable, please review"),
                             tr("Member {0} of chronology relation {1} is missing end_date "
                                + "and is not the youngest member (only the youngest may "
                                + "omit end_date).",
                                formatPrim(mi.prim), formatPrim(r)))
                    .primitives(mi.prim)
                    .build());
            }
        }
    }

    private void checkChronologyOverlap(Relation r, List<MemberInfo> infos,
                                        MemberInfo youngest) {
        // Eligible: needs a start. End may be missing only for the youngest,
        // which is interpreted as open-ended (range extends to MAX). Non-
        // youngest members missing end_date are reported by 4237 and skipped
        // here to avoid double-firing.
        List<MemberInfo> withStart = new ArrayList<>();
        for (MemberInfo mi : infos) {
            if (mi.start != null && (mi.end != null || mi == youngest)) {
                withStart.add(mi);
            }
        }

        for (int i = 0; i < withStart.size(); i++) {
            MemberInfo a = withStart.get(i);
            for (int j = i + 1; j < withStart.size(); j++) {
                MemberInfo b = withStart.get(j);
                if (touchesAtMatchingPrecision(a.end, b.start)
                    || touchesAtMatchingPrecision(b.end, a.start)) {
                    continue;
                }
                LocalDate aLow = a.start.lowerBound();
                LocalDate aHigh = a.end == null ? LocalDate.MAX : a.end.upperBound();
                LocalDate bLow = b.start.lowerBound();
                LocalDate bHigh = b.end == null ? LocalDate.MAX : b.end.upperBound();
                LocalDate intersectLo = aLow.isAfter(bLow) ? aLow : bLow;
                LocalDate intersectHi = aHigh.isBefore(bHigh) ? aHigh : bHigh;
                if (intersectLo.isAfter(intersectHi)) continue;

                errors.add(TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_OVERLAP)
                    .message(tr("[ohm] Chronology - member date range overlap; unfixable, please review"),
                             tr("Members {0} ({1}..{2}) and {3} ({4}..{5}) have "
                                + "overlapping date ranges in chronology relation {6}.",
                                formatPrim(a.prim), a.start.raw,
                                a.end == null ? "(no end)" : a.end.raw,
                                formatPrim(b.prim), b.start.raw,
                                b.end == null ? "(no end)" : b.end.raw,
                                formatPrim(r)))
                    .primitives(Arrays.asList(a.prim, b.prim))
                    .build());
            }
        }
    }

    private void checkChronologyGap(Relation r, List<MemberInfo> infos,
                                    MemberInfo youngest) {
        // Same eligibility as overlap: non-youngest missing-end members are
        // skipped (4237 reports them).
        List<MemberInfo> withStart = new ArrayList<>();
        for (MemberInfo mi : infos) {
            if (mi.start != null && (mi.end != null || mi == youngest)) {
                withStart.add(mi);
            }
        }
        if (withStart.size() < 2) return;

        withStart.sort((x, y) -> {
            int c = x.start.lowerBound().compareTo(y.start.lowerBound());
            if (c != 0) return c;
            LocalDate xe = x.end == null ? LocalDate.MAX : x.end.upperBound();
            LocalDate ye = y.end == null ? LocalDate.MAX : y.end.upperBound();
            return xe.compareTo(ye);
        });

        for (int i = 0; i + 1 < withStart.size(); i++) {
            MemberInfo prev = withStart.get(i);
            MemberInfo next = withStart.get(i + 1);
            if (prev.end == null) continue;             // open-ended on right
            if (touchesAtMatchingPrecision(prev.end, next.start)) continue;
            LocalDate prevHi = prev.end.upperBound();
            LocalDate nextLo = next.start.lowerBound();
            if (!nextLo.isAfter(prevHi)) continue;      // overlap; not a gap
            int missing = missingUnitsAtCoarserPrecision(prev.end, next.start);
            if (missing <= 1) continue;

            errors.add(TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_GAP)
                .message(tr("[ohm] Chronology - gap between member date ranges; unfixable, please review"),
                         tr("Member {0} (ends {1}) and member {2} (starts {3}) leave a "
                            + "{4} {5} gap in chronology relation {6}.",
                            formatPrim(prev.prim), prev.end.raw,
                            formatPrim(next.prim), next.start.raw,
                            missing,
                            coarserPrecisionName(prev.end.precision, next.start.precision),
                            formatPrim(r)))
                .primitives(Arrays.asList(prev.prim, next.prim))
                .build());
        }
    }

    /**
     * Rule 4238: a member of a chronology is identical to its predecessor in
     * every tag except date-related ones (anything matching {@link #DATE_RELATED_KEY}).
     * If the entity didn't change between successive periods, the chronology
     * shouldn't split it into separate members. Applies to nodes, ways, and
     * relations alike. Predecessor is the prior member by sorted start_date.
     */
    private void checkChronologyDuplicatePredecessor(Relation r, List<MemberInfo> infos) {
        List<MemberInfo> withStart = new ArrayList<>();
        for (MemberInfo mi : infos) {
            if (mi.start != null) withStart.add(mi);
        }
        if (withStart.size() < 2) return;

        withStart.sort((x, y) -> {
            int c = x.start.lowerBound().compareTo(y.start.lowerBound());
            if (c != 0) return c;
            LocalDate xe = x.end == null ? LocalDate.MAX : x.end.upperBound();
            LocalDate ye = y.end == null ? LocalDate.MAX : y.end.upperBound();
            return xe.compareTo(ye);
        });

        for (int i = 0; i + 1 < withStart.size(); i++) {
            MemberInfo prev = withStart.get(i);
            MemberInfo curr = withStart.get(i + 1);
            Map<String, String> prevTags = nonDateTags(prev.prim);
            Map<String, String> currTags = nonDateTags(curr.prim);
            // No identifying info on either side — nothing to compare against.
            if (prevTags.isEmpty() && currTags.isEmpty()) continue;
            if (!prevTags.equals(currTags)) continue;

            errors.add(TestError.builder(this, Severity.ERROR, CODE_CHRONOLOGY_DUPLICATE)
                .message(tr("[ohm] Chronology - member duplicate to its predecessor; unfixable, please review"),
                         tr("Member {0} ({1}..{2}) is identical to its predecessor {3} "
                            + "({4}..{5}) in every tag except date-related fields. If "
                            + "the entity did not change, do not split it into separate "
                            + "chronology members.",
                            formatPrim(curr.prim), curr.start.raw,
                            curr.end == null ? "(no end)" : curr.end.raw,
                            formatPrim(prev.prim), prev.start.raw,
                            prev.end == null ? "(no end)" : prev.end.raw))
                .primitives(Arrays.asList(prev.prim, curr.prim))
                .build());
        }
    }

    /**
     * Returns the primitive's tags excluding date-related keys (anything
     * matching {@link #DATE_RELATED_KEY}). Used by Rule 4238.
     */
    private static Map<String, String> nonDateTags(OsmPrimitive p) {
        Map<String, String> result = new HashMap<>();
        for (String key : p.keySet()) {
            if (!DATE_RELATED_KEY.matcher(key).find()) {
                result.put(key, p.get(key));
            }
        }
        return result;
    }

    /**
     * True iff {@code prevEnd} and {@code nextStart} are at the same precision
     * and have the same raw tag value. Treated as adjacency (canonical OHM
     * successor pattern), not overlap and not a gap.
     */
    private static boolean touchesAtMatchingPrecision(ParsedDate prevEnd, ParsedDate nextStart) {
        if (prevEnd == null || nextStart == null) return false;
        if (prevEnd.precision != nextStart.precision) return false;
        return prevEnd.raw.equals(nextStart.raw);
    }

    /**
     * Number of complete units strictly between {@code prevEnd} and
     * {@code nextStart} at the coarser of the two precisions. Adjacent
     * boundaries (year 1850 → year 1851) yield 0; one missing year, 1.
     */
    private static int missingUnitsAtCoarserPrecision(ParsedDate prevEnd, ParsedDate nextStart) {
        DatePrecision coarser = (prevEnd.precision.ordinal() < nextStart.precision.ordinal())
            ? prevEnd.precision : nextStart.precision;
        switch (coarser) {
            case YEAR:
                return nextStart.year - prevEnd.year - 1;
            case MONTH: {
                int prevMonths = prevEnd.year * 12 + prevEnd.month - 1;
                int nextMonths = nextStart.year * 12 + nextStart.month - 1;
                return nextMonths - prevMonths - 1;
            }
            case DAY: {
                LocalDate p = prevEnd.upperBound();
                LocalDate n = nextStart.lowerBound();
                return (int) ChronoUnit.DAYS.between(p, n) - 1;
            }
            default:
                return 0;
        }
    }

    private static String coarserPrecisionName(DatePrecision a, DatePrecision b) {
        DatePrecision coarser = (a.ordinal() < b.ordinal()) ? a : b;
        switch (coarser) {
            case YEAR:  return "year(s)";
            case MONTH: return "month(s)";
            case DAY:   return "day(s)";
            default:    return "unit(s)";
        }
    }

    /** Compact "n/123", "w/456", "r/789" form for description text. */
    private static String formatPrim(OsmPrimitive p) {
        return p.getType().getAPIName().substring(0, 1) + "/" + p.getId();
    }
}
