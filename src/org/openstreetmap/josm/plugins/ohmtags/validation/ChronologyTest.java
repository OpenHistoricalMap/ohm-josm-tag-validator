// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.ohmtags.validation;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.ohmtags.validation.DateTagTest.DatePrecision;
import org.openstreetmap.josm.plugins.ohmtags.validation.DateTagTest.ParsedDate;

/**
 * Validates structural consistency of {@code type=chronology} relations:
 * member dates fit within the parent's range, no overlaps or gaps between
 * consecutive members, no duplicate-predecessor members, and the boundary-
 * chronology member-type constraint.
 *
 * <p>Findings attach to the parent chronology relation (and offending
 * members where applicable). All rule codes here live in the 4234–4239,
 * 4243, 4254 range — see the {@code CODE_CHRONOLOGY_*} constants below.
 */
public class ChronologyTest extends Test {

    // --- Error codes (chronology-relation structural checks) -----------------
    // All findings attach to the parent chronology relation; offending member
    // ids are listed in the description text. See checkChronologyConsistency.
    protected static final int CODE_CHRONOLOGY_OUTSIDE_PARENT = 4234;
    protected static final int CODE_CHRONOLOGY_OVERLAP = 4235;
    protected static final int CODE_CHRONOLOGY_GAP = 4236;
    protected static final int CODE_CHRONOLOGY_MISSING_DATE = 4237;
    protected static final int CODE_CHRONOLOGY_DUPLICATE = 4238;
    protected static final int CODE_CHRONOLOGY_MEMBER_NO_DATES = 4239;
    protected static final int CODE_BOUNDARY_CHRONOLOGY_NON_RELATION = 4243;
    protected static final int CODE_CHRONOLOGY_EMPTY = 4254;

    /**
     * Matches keys that are part of the OHM date family — anything ending in
     * {@code _date} or containing {@code _date:} as a subkey separator. Used
     * by the chronology duplicate-predecessor rule (4238) to filter date-
     * related tags out of equality comparisons.
     */
    private static final Pattern DATE_RELATED_KEY =
        Pattern.compile("_date(?::|$)");

    public ChronologyTest() {
        super(tr("OHM chronologies"),
              tr("Checks structural consistency of type=chronology relations "
                 + "(member dates within parent range, no overlaps/gaps, no duplicates)."));
    }

    @Override
    public void visit(Relation r) {
        if ("chronology".equals(r.get("type"))) {
            checkChronologyConsistency(r);
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
        // Rule 4254: empty chronology relation. JOSM core has a generic
        // empty-relation warning, but chronologies are OHM-special — flag
        // them with a domain-specific message that points at the typical
        // remedies (add members or delete the relation).
        if (r.getMembers().isEmpty()) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_EMPTY)
                .message(tr("[ohm] Chronology - relation has no members; unfixable, please review"),
                         marktr("Chronology relation has no members. Add the constituent "
                            + "features as members, or delete this relation if it''s no "
                            + "longer needed."))
                .primitives(r)
                .build());
            return;
        }

        List<MemberInfo> infos = new ArrayList<>();
        for (RelationMember rm : r.getMembers()) {
            OsmPrimitive m = rm.getMember();
            if (m == null) continue;
            ParsedDate s = DateTagTest.parseStrictBaseDate(m.get("start_date"));
            ParsedDate e = DateTagTest.parseStrictBaseDate(m.get("end_date"));
            infos.add(new MemberInfo(m, s, e));
        }
        if (infos.isEmpty()) return;

        MemberInfo youngest = identifyYoungest(infos);

        // Pre-build the per-relation "recompute parent dates from member
        // envelope" fix once; both parent-range (4234) and boundary-gap (4236)
        // emissions share it, so applying the fix from any one finding
        // resolves the others on the next validation pass.
        Command parentDatesFix = buildChronologyParentDatesFix(r, infos, youngest);

        checkChronologyParentRange(r, infos, parentDatesFix);
        checkChronologyMissingTags(r, infos, youngest);
        checkChronologyOverlap(r, infos, youngest);
        checkChronologyGap(r, infos, youngest);
        checkChronologyBoundaryGap(r, infos, youngest, parentDatesFix);
        checkChronologyDuplicatePredecessor(r, infos);
        checkBoundaryChronologyMemberTypes(r);
    }

    /**
     * Build a command that resets the parent relation's {@code start_date}
     * and {@code end_date} to the member envelope (min start, max end, or
     * remove end if the youngest member has no end_date). Returns null if
     * no fix is applicable (no members with parseable dates).
     */
    private Command buildChronologyParentDatesFix(Relation r,
                                                  List<MemberInfo> infos,
                                                  MemberInfo youngest) {
        // Find oldest member by start lower bound; latest by end upper bound.
        MemberInfo oldest = null;
        MemberInfo latest = null;
        boolean youngestHasNoEnd = youngest != null && youngest.end == null;
        for (MemberInfo mi : infos) {
            if (mi.start != null) {
                if (oldest == null
                    || mi.start.lowerBound().isBefore(oldest.start.lowerBound())) {
                    oldest = mi;
                }
            }
            if (mi.end != null) {
                if (latest == null
                    || mi.end.upperBound().isAfter(latest.end.upperBound())) {
                    latest = mi;
                }
            }
        }
        if (oldest == null && latest == null && !youngestHasNoEnd) return null;

        List<Command> cmds = new ArrayList<>();
        if (oldest != null) {
            String desiredStart = oldest.start.raw;
            String currentStart = r.get("start_date");
            if (!Objects.equals(desiredStart, currentStart)) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(r),
                                                   "start_date", desiredStart));
            }
        }
        if (youngestHasNoEnd) {
            // Youngest member is still-extant → parent should also be open-ended.
            if (r.get("end_date") != null) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(r),
                                                   "end_date", null));
            }
        } else if (latest != null) {
            String desiredEnd = latest.end.raw;
            String currentEnd = r.get("end_date");
            if (!Objects.equals(desiredEnd, currentEnd)) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(r),
                                                   "end_date", desiredEnd));
            }
        }
        if (cmds.isEmpty()) return null;
        return new SequenceCommand(
            tr("Recompute chronology parent dates from member envelope"),
            cmds);
    }

    /**
     * Rule 4243: a boundary chronology (a {@code type=chronology} relation
     * whose members include 2 or more {@code type=boundary} relations) must
     * not include non-relation members. Non-relation members on a boundary
     * chronology indicate the contributor mistakenly attached ways or
     * nodes directly instead of attaching them to one of the member
     * boundary relations.
     *
     * <p>The "2 or more boundary relations" threshold is the boundary-
     * chronology signature per OHM convention (issue #21). Single-boundary
     * chronologies aren't flagged because a chronology with only one
     * boundary member is a degenerate case the editor probably hasn't
     * finished assembling.
     */
    private void checkBoundaryChronologyMemberTypes(Relation r) {
        int boundaryRelCount = 0;
        for (RelationMember rm : r.getMembers()) {
            OsmPrimitive m = rm.getMember();
            if (m instanceof Relation && "boundary".equals(m.get("type"))) {
                boundaryRelCount++;
            }
        }
        if (boundaryRelCount < 2) return;

        List<OsmPrimitive> nonRelationMembers = new ArrayList<>();
        for (RelationMember rm : r.getMembers()) {
            OsmPrimitive m = rm.getMember();
            if (m == null) continue;
            if (!(m instanceof Relation)) {
                nonRelationMembers.add(m);
            }
        }
        if (nonRelationMembers.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (OsmPrimitive m : nonRelationMembers) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(formatPrim(m));
        }

        List<OsmPrimitive> primitives = new ArrayList<>();
        primitives.add(r);
        primitives.addAll(nonRelationMembers);

        errors.add(TestError.builder(this, Severity.ERROR, CODE_BOUNDARY_CHRONOLOGY_NON_RELATION)
            .message(tr("[ohm] Chronology - boundary chronology has non-relation members; unfixable, please review"),
                     marktr("Boundary chronology relation {0} has non-relation member(s): {1}. "
                        + "Boundary chronologies should only contain relation members "
                        + "(typically other type=boundary relations representing the "
                        + "entity at different periods in time)."),
                        formatPrim(r), sb.toString())
            .primitives(primitives)
            .build());
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

    private void checkChronologyParentRange(Relation r, List<MemberInfo> infos,
                                            Command parentDatesFix) {
        ParsedDate parentStart = DateTagTest.parseStrictBaseDate(r.get("start_date"));
        ParsedDate parentEnd = DateTagTest.parseStrictBaseDate(r.get("end_date"));
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

            TestError.Builder b = TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_OUTSIDE_PARENT)
                .message(tr("[ohm] Chronology - member date range outside parent chronology range; autofix by recomputing parent dates from member envelope"),
                         marktr("Member {0} outside parent range {1}: {2}."),
                            formatPrim(mi.prim), parentRange, sb.toString())
                .primitives(Arrays.asList(r, mi.prim));
            if (parentDatesFix != null) {
                b = b.fix(() -> parentDatesFix);
            }
            errors.add(b.build());
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
                             marktr("Member {0} of chronology relation {1} has neither "
                                + "start_date nor end_date."),
                                formatPrim(mi.prim), formatPrim(r))
                    .primitives(mi.prim)
                    .build());
                continue;
            }

            if (mi.start == null) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_MISSING_DATE)
                    .message(tr("[ohm] Chronology - member missing required date tag; unfixable, please review"),
                             marktr("Member {0} of chronology relation {1} is missing start_date."),
                                formatPrim(mi.prim), formatPrim(r))
                    .primitives(mi.prim)
                    .build());
            }
            if (mi.end == null && mi != youngest) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_MISSING_DATE)
                    .message(tr("[ohm] Chronology - member missing required date tag; unfixable, please review"),
                             marktr("Member {0} of chronology relation {1} is missing end_date "
                                + "and is not the youngest member (only the youngest may "
                                + "omit end_date)."),
                                formatPrim(mi.prim), formatPrim(r))
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
                             marktr("Members {0} ({1}..{2}) and {3} ({4}..{5}) have "
                                + "overlapping date ranges in chronology relation {6}."),
                                formatPrim(a.prim), a.start.raw,
                                a.end == null ? "(no end)" : a.end.raw,
                                formatPrim(b.prim), b.start.raw,
                                b.end == null ? "(no end)" : b.end.raw,
                                formatPrim(r))
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
                         marktr("Member {0} (ends {1}) and member {2} (starts {3}) leave a "
                            + "{4} {5} gap in chronology relation {6}."),
                            formatPrim(prev.prim), prev.end.raw,
                            formatPrim(next.prim), next.start.raw,
                            missing,
                            coarserPrecisionName(prev.end.precision, next.start.precision),
                            formatPrim(r))
                .primitives(Arrays.asList(prev.prim, next.prim))
                .build());
        }
    }

    /**
     * Rule 4236 boundary variant: gap between the chronology relation's
     * own date range and its oldest/latest member. Fires when the parent's
     * {@code start_date} is more than 1 unit (at the coarser shared precision)
     * before the oldest member's {@code start_date}, or when the parent's
     * {@code end_date} is more than 1 unit after the latest member's
     * {@code end_date}. Issue #22.
     *
     * <p>The existing {@link #checkChronologyGap} rule only catches gaps
     * between consecutive sorted members. This method covers the
     * gap-at-the-edges case the user reported in #22.
     *
     * <p>Skips the trailing-gap check entirely when any member is
     * open-ended (no {@code end_date}), since open-ended coverage extends
     * to infinity and no trailing gap is possible.
     */
    private void checkChronologyBoundaryGap(Relation r, List<MemberInfo> infos,
                                            MemberInfo youngest,
                                            Command parentDatesFix) {
        ParsedDate parentStart = DateTagTest.parseStrictBaseDate(r.get("start_date"));
        ParsedDate parentEnd = DateTagTest.parseStrictBaseDate(r.get("end_date"));
        if (parentStart == null && parentEnd == null) return;

        // Same eligibility as overlap/gap: needs a start; non-youngest
        // missing-end members are reported by 4237 and skipped here.
        List<MemberInfo> withStart = new ArrayList<>();
        for (MemberInfo mi : infos) {
            if (mi.start != null && (mi.end != null || mi == youngest)) {
                withStart.add(mi);
            }
        }
        if (withStart.isEmpty()) return;

        // --- Leading gap: parent.start vs oldest member's start. ---
        if (parentStart != null) {
            MemberInfo oldest = null;
            for (MemberInfo mi : withStart) {
                if (oldest == null
                    || mi.start.lowerBound().isBefore(oldest.start.lowerBound())) {
                    oldest = mi;
                }
            }
            // Skip when oldest start is at or before parent start (4234
            // handles "before parent" as outside-parent-range).
            if (!touchesAtMatchingPrecision(parentStart, oldest.start)
                && oldest.start.lowerBound().isAfter(parentStart.upperBound())) {
                int missing = missingUnitsAtCoarserPrecision(parentStart, oldest.start);
                if (missing > 1) {
                    TestError.Builder b = TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_GAP)
                        .message(tr("[ohm] Chronology - gap between parent start & oldest member; autofix by recomputing parent dates from member envelope"),
                                 marktr("Chronology relation {0} starts at {1} but oldest member {2} "
                                    + "starts at {3}, leaving a {4} {5} gap at the start of the chronology."),
                                    formatPrim(r), parentStart.raw,
                                    formatPrim(oldest.prim), oldest.start.raw,
                                    missing,
                                    coarserPrecisionName(parentStart.precision, oldest.start.precision))
                        .primitives(Arrays.asList(r, oldest.prim));
                    if (parentDatesFix != null) {
                        b = b.fix(() -> parentDatesFix);
                    }
                    errors.add(b.build());
                }
            }
        }

        // --- Trailing gap: parent.end vs latest member's end. ---
        if (parentEnd != null) {
            // If any eligible member is open-ended, coverage extends to
            // MAX and no trailing gap is possible.
            boolean anyOpenEnded = false;
            MemberInfo latest = null;
            for (MemberInfo mi : withStart) {
                if (mi.end == null) {
                    anyOpenEnded = true;
                    break;
                }
                if (latest == null
                    || mi.end.upperBound().isAfter(latest.end.upperBound())) {
                    latest = mi;
                }
            }
            if (!anyOpenEnded && latest != null
                && !touchesAtMatchingPrecision(latest.end, parentEnd)
                && latest.end.upperBound().isBefore(parentEnd.lowerBound())) {
                int missing = missingUnitsAtCoarserPrecision(latest.end, parentEnd);
                if (missing > 1) {
                    TestError.Builder bb = TestError.builder(this, Severity.WARNING, CODE_CHRONOLOGY_GAP)
                        .message(tr("[ohm] Chronology - gap between latest member end & parent end; autofix by recomputing parent dates from member envelope"),
                                 marktr("Latest member {0} (in chronology relation {1}) ends at {2} but "
                                    + "the chronology''s end_date is {3}, leaving a {4} {5} gap at the end of the chronology."),
                                    formatPrim(latest.prim), formatPrim(r), latest.end.raw,
                                    parentEnd.raw,
                                    missing,
                                    coarserPrecisionName(latest.end.precision, parentEnd.precision))
                        .primitives(Arrays.asList(r, latest.prim));
                    if (parentDatesFix != null) {
                        bb = bb.fix(() -> parentDatesFix);
                    }
                    errors.add(bb
                        .build());
                }
            }
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
            // Geometry must also match. Any geometric change (different node
            // ids, moved coordinates, different relation member structure)
            // means the chronology split was justified — skip.
            if (!sameGeometry(prev.prim, curr.prim)) continue;

            errors.add(TestError.builder(this, Severity.ERROR, CODE_CHRONOLOGY_DUPLICATE)
                .message(tr("[ohm] Chronology - member duplicate to its predecessor; unfixable, please review"),
                         marktr("Member {0} ({1}..{2}) is identical to its predecessor {3} "
                            + "({4}..{5}) in every tag except date-related fields. If "
                            + "the entity did not change, do not split it into separate "
                            + "chronology members."),
                            formatPrim(curr.prim), curr.start.raw,
                            curr.end == null ? "(no end)" : curr.end.raw,
                            formatPrim(prev.prim), prev.start.raw,
                            prev.end == null ? "(no end)" : prev.end.raw)
                .primitives(Arrays.asList(prev.prim, curr.prim))
                .build());
        }
    }

    /**
     * True iff {@code a} and {@code b} have identical geometry. Used by
     * Rule 4238 to suppress the duplicate finding when an entity's shape
     * legitimately changed between successive chronology members.
     *
     * <p>Comparison is recursive and coordinate-based, not identity-based:
     * two ways with different node ids but identical node coordinates count
     * as the same geometry. Incomplete primitives (no downloaded data)
     * cannot be compared, so the function returns {@code false} for them —
     * 4238 will not fire when we can't be sure the geometry matches.
     */
    private static boolean sameGeometry(OsmPrimitive a, OsmPrimitive b) {
        if (a == null || b == null) return false;
        if (a.isIncomplete() || b.isIncomplete()) return false;
        if (a == b) return true;
        if (a.getType() != b.getType()) return false;

        if (a instanceof Node) {
            Node na = (Node) a;
            Node nb = (Node) b;
            return na.getCoor() != null && nb.getCoor() != null
                && na.getCoor().equals(nb.getCoor());
        }
        if (a instanceof Way) {
            Way wa = (Way) a;
            Way wb = (Way) b;
            if (wa.getNodesCount() != wb.getNodesCount()) return false;
            for (int i = 0; i < wa.getNodesCount(); i++) {
                if (!sameGeometry(wa.getNode(i), wb.getNode(i))) return false;
            }
            return true;
        }
        if (a instanceof Relation) {
            Relation ra = (Relation) a;
            Relation rb = (Relation) b;
            if (ra.getMembersCount() != rb.getMembersCount()) return false;
            for (int i = 0; i < ra.getMembersCount(); i++) {
                RelationMember ma = ra.getMember(i);
                RelationMember mb = rb.getMember(i);
                if (!ma.getRole().equals(mb.getRole())) return false;
                if (!sameGeometry(ma.getMember(), mb.getMember())) return false;
            }
            return true;
        }
        return false;
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
