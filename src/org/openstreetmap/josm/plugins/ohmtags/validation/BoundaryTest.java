// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.ohmtags.validation;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeMembersCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.INode;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.paint.relations.Multipolygon;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.Geometry.PolygonIntersection;

/**
 * Validates boundary geometry hygiene on OHM features:
 * <ul>
 *   <li>Rule 4330: boundary-member nodes carrying POI-style tags.</li>
 *   <li>Rule 4331: a {@code waterway=*} way that is a member of a
 *       {@code type=boundary} relation.</li>
 *   <li>Rule 4332: members of a boundary relation that form closed
 *       rings but aren't listed in topological order.</li>
 *   <li>Rule 4334: a node shared between a boundary way and a
 *       non-boundary way.</li>
 *   <li>Rule 4335: same-class polygons overlapping in time AND space.</li>
 * </ul>
 */
public class BoundaryTest extends Test {

    // --- Error codes ---------------------------------------------------------
    protected static final int CODE_BOUNDARY_NODE_TAGS = 4330;
    protected static final int CODE_BOUNDARY_WATERWAY = 4331;
    protected static final int CODE_BOUNDARY_NOT_SORTED = 4332;
    protected static final int CODE_BOUNDARY_NODE_JOINED_NON_BOUNDARY = 4334;
    protected static final int CODE_POLYGON_OVERLAP_TIME_SPACE = 4335;

    public BoundaryTest() {
        super(tr("OHM boundaries"),
              tr("Checks boundary geometry hygiene (node tags, waterway-as-boundary, "
                 + "member ordering, joined-to-non-boundary, same-class polygon overlap)."));
    }

    /**
     * Collected polygon candidates for rule 4335 (same-class polygon
     * overlap in time + space). Populated during visit() calls; the
     * pairwise check runs in {@link #endTest()}.
     */
    private final List<PolygonCandidate> polygonCandidates = new ArrayList<>();

    @Override
    public void startTest(ProgressMonitor pm) {
        super.startTest(pm);
        polygonCandidates.clear();
    }

    @Override
    public void endTest() {
        runPolygonOverlapPairwiseCheck();
        polygonCandidates.clear();
        super.endTest();
    }

    @Override
    public void visit(Node n) {
        checkBoundaryNodeForNonAllowedTags(n);
        checkBoundaryNodeJoinedToNonBoundary(n);
    }

    @Override
    public void visit(Way w) {
        checkBoundaryMemberWaterway(w);
        collectPolygonCandidate(w);
    }

    @Override
    public void visit(Relation r) {
        checkBoundaryRelationSorted(r);
        collectPolygonCandidate(r);
    }

    /**
     * Rule 4330: nodes that participate in a boundary relation's geometry
     * (i.e., they're nodes of a way that's a member of a {@code type=boundary}
     * relation) should not carry POI-style tags — those tags belong on a
     * separate node at the same location, not on the boundary's geometry.
     *
     * <p>"Allowed on the boundary node" means date-family tags
     * ({@code start_date}, {@code end_date}, and any of their {@code :edtf},
     * {@code :raw}, {@code :source}, … qualifiers) and source-family tags
     * ({@code source}, {@code source:*}, {@code attribute:source},
     * {@code attribute:source:*}). Everything else (name, place, historic,
     * wikidata, etc.) triggers the warning.
     *
     * <p>Autofix: clones the node into a new node at the same coordinates
     * carrying all of the original's tags; strips the non-date/non-source
     * tags from the original. The original keeps its relation memberships
     * and roles (and stays in the boundary way). The new node has no
     * relation memberships — it's a fresh standalone POI.
     */
    private void checkBoundaryNodeForNonAllowedTags(Node n) {
        if (!n.hasKeys()) return;
        if (findBoundaryWayContaining(n) == null) return;

        List<String> nonAllowedKeys = new ArrayList<>();
        for (String key : n.keySet()) {
            if (!isDateOrSourceKey(key)) {
                nonAllowedKeys.add(key);
            }
        }
        if (nonAllowedKeys.isEmpty()) return;

        Node clone = new Node(n, true);
        List<Command> cmds = new ArrayList<>();
        cmds.add(new AddCommand(n.getDataSet(), clone));
        for (String key : nonAllowedKeys) {
            cmds.add(new ChangePropertyCommand(Arrays.asList(n), key, null));
        }
        Command fix = new SequenceCommand(
            tr("Move tags off boundary node to a new node at the same location"),
            cmds);

        errors.add(TestError.builder(this, Severity.WARNING, CODE_BOUNDARY_NODE_TAGS)
            .message(tr("[ohm] Boundary geometry - node has non-date/non-source tags; autofix by moving tags to a new node"),
                     marktr("Boundary-member node has {0} tag(s) unrelated to date or source ({1}). "
                        + "Move all tags to a new node at the same location, leaving only "
                        + "date/source tags here?"),
                        Integer.toString(nonAllowedKeys.size()),
                        String.join(", ", nonAllowedKeys))
            .primitives(n)
            .fix(() -> fix)
            .build());
    }

    /**
     * Rule 4331: a way that carries a {@code waterway=*} tag should not also
     * be a geometry member of a {@code type=boundary} relation. The
     * waterway-as-boundary pattern conflates two distinct identities — the
     * waterway has its own tags, history, and relation memberships, while
     * the boundary's geometry should be neutral shared geometry.
     *
     * <p>Autofix: creates a new way at the same coordinates as the original
     * but with its own cloned nodes (so the new way is geometrically
     * coincident but topologically independent from the waterway). Copies
     * only the original's source-family tags onto the new way. Replaces
     * the original with the new way in every {@code type=boundary} relation
     * it's a member of (preserving the role). The original keeps its
     * {@code waterway} and other tags, all its original nodes, and any
     * non-boundary relation memberships.
     *
     * <p>Endpoint preservation: at each end of the waterway way, if the
     * endpoint node is also used by another way in any of the boundary
     * relations being processed, that adjacent boundary way is updated to
     * use the cloned endpoint instead of the original. This keeps the
     * boundary's topology intact (adjacent boundary segments still share
     * an endpoint with the new boundary way at that location) without
     * any node being shared between the waterway and the boundary geometry.
     */
    private void checkBoundaryMemberWaterway(Way w) {
        if (!w.hasKey("waterway")) return;

        List<Relation> boundaryRelations = new ArrayList<>();
        for (OsmPrimitive ref : w.getReferrers()) {
            if (ref instanceof Relation
                && "boundary".equals(ref.get("type"))) {
                boundaryRelations.add((Relation) ref);
            }
        }
        if (boundaryRelations.isEmpty()) return;
        Set<Relation> boundarySet = new HashSet<>(boundaryRelations);

        List<Command> cmds = new ArrayList<>();
        List<Node> originalNodes = w.getNodes();
        List<Node> newWayNodes = new ArrayList<>();

        // Map original endpoint Node → its clone. Used after cloning to
        // reroute adjacent boundary ways onto the clone.
        Map<Node, Node> endpointClones = new IdentityHashMap<>();

        for (int i = 0; i < originalNodes.size(); i++) {
            Node original = originalNodes.get(i);
            Node clone = new Node(original, true);
            cmds.add(new AddCommand(w.getDataSet(), clone));
            newWayNodes.add(clone);
            boolean isEndpoint = (i == 0 || i == originalNodes.size() - 1);
            if (isEndpoint) {
                endpointClones.put(original, clone);
            }
        }

        // For each endpoint, find other ways that share it AND are members of
        // any boundary relation we're processing. Reroute those ways' node
        // lists onto the clone so the boundary topology is preserved without
        // sharing nodes with the original waterway.
        Set<Way> rerouted = new HashSet<>();
        for (Map.Entry<Node, Node> entry : endpointClones.entrySet()) {
            Node originalEndpoint = entry.getKey();
            Node clonedEndpoint = entry.getValue();
            for (OsmPrimitive ref : originalEndpoint.getReferrers()) {
                if (!(ref instanceof Way)) continue;
                Way other = (Way) ref;
                if (other == w) continue;
                if (rerouted.contains(other)) continue;
                if (!isInAnyOf(other, boundarySet)) continue;
                List<Node> updated = new ArrayList<>();
                for (Node n : other.getNodes()) {
                    Node mapped = endpointClones.get(n);
                    updated.add(mapped != null ? mapped : n);
                }
                cmds.add(new ChangeNodesCommand(other, updated));
                rerouted.add(other);
            }
        }

        // Build the new boundary way with cloned nodes and source-only tags.
        Way newWay = new Way();
        newWay.setNodes(newWayNodes);
        for (String key : w.keySet()) {
            if (isSourceKey(key)) {
                newWay.put(key, w.get(key));
            }
        }
        cmds.add(new AddCommand(w.getDataSet(), newWay));

        // Replace original way with new way in boundary relations.
        for (Relation r : boundaryRelations) {
            List<RelationMember> newMembers = new ArrayList<>();
            for (RelationMember m : r.getMembers()) {
                if (m.isWay() && m.getWay() == w) {
                    newMembers.add(new RelationMember(m.getRole(), newWay));
                } else {
                    newMembers.add(m);
                }
            }
            cmds.add(new ChangeMembersCommand(r, newMembers));
        }
        Command fix = new SequenceCommand(
            tr("Replace waterway in boundary with a coincident boundary-only way"),
            cmds);

        errors.add(TestError.builder(this, Severity.WARNING, CODE_BOUNDARY_WATERWAY)
            .message(tr("[ohm] Boundary geometry - waterway way is a boundary member; autofix by creating a coincident boundary way"),
                     marktr("waterway={0} way is a member of {1,choice,1#boundary relation|1<{1,number,integer} boundary relations}. "
                        + "Boundary geometry should be separate from waterway identity. "
                        + "Create a new way at the same coordinates (with its own cloned nodes "
                        + "and only source tags) to replace this one in the boundary?"),
                        w.get("waterway"), boundaryRelations.size())
            .primitives(w)
            .fix(() -> fix)
            .build());
    }

    /**
     * Rule 4332: members of a {@code type=boundary} relation that form
     * closed rings should be listed in topological order — each consecutive
     * way member of a role-group should share an endpoint with the next,
     * and each ring should close back on itself. Direction within each ring
     * is not significant.
     *
     * <p>Scope: only fires when each role-group's ways actually form closed
     * rings (every endpoint node appears exactly twice across the group).
     * Open chains, missing segments, and other geometry problems are left
     * for the core JOSM validator to catch.
     *
     * <p>Inner rings (and any role) are evaluated on a per-role-group basis.
     * Within a role-group, ways may form multiple disjoint rings; each ring
     * is detected from the current ordering.
     *
     * <p>Autofix: reorders the way members within each unsorted role-group
     * so consecutive members share an endpoint and each ring closes. Non-way
     * members keep their positions in the member list; way-members of other
     * (already-sorted) role-groups keep their positions too.
     */
    private void checkBoundaryRelationSorted(Relation r) {
        if (!"boundary".equals(r.get("type"))) return;

        Map<String, List<Way>> byRole = new LinkedHashMap<>();
        for (RelationMember m : r.getMembers()) {
            if (!m.isWay() || m.getWay() == null) continue;
            byRole.computeIfAbsent(m.getRole(), k -> new ArrayList<>()).add(m.getWay());
        }

        List<String> unsortedRoles = new ArrayList<>();
        Map<String, List<Way>> sortedByRole = new HashMap<>();
        for (Map.Entry<String, List<Way>> entry : byRole.entrySet()) {
            List<Way> group = entry.getValue();
            if (group.size() < 2) continue;
            if (!canFormClosedRings(group)) continue;
            if (isGroupTopologicallySorted(group)) continue;
            List<Way> sorted = sortGroupIntoRings(group);
            if (sorted == null || sorted.size() != group.size()) continue;
            unsortedRoles.add(entry.getKey());
            sortedByRole.put(entry.getKey(), sorted);
        }
        if (unsortedRoles.isEmpty()) return;

        // Build the new member list: replace way-members of unsorted role-groups
        // with the next way from that group's sorted sequence, in their original
        // slots. Non-way members and members of sorted groups keep their slots.
        Map<String, Iterator<Way>> iters = new HashMap<>();
        for (Map.Entry<String, List<Way>> e : sortedByRole.entrySet()) {
            iters.put(e.getKey(), e.getValue().iterator());
        }
        List<RelationMember> newMembers = new ArrayList<>();
        for (RelationMember m : r.getMembers()) {
            Iterator<Way> it = m.isWay() ? iters.get(m.getRole()) : null;
            if (it != null && it.hasNext()) {
                newMembers.add(new RelationMember(m.getRole(), it.next()));
            } else {
                newMembers.add(m);
            }
        }
        Command fix = new ChangeMembersCommand(r, newMembers);

        String rolesDisplay = String.join(", ",
            unsortedRoles.stream()
                .map(s -> s.isEmpty() ? "(empty)" : s)
                .toArray(String[]::new));
        errors.add(TestError.builder(this, Severity.WARNING, CODE_BOUNDARY_NOT_SORTED)
            .message(tr("[ohm] Boundary geometry - members not in topological order; autofix by sorting"),
                     marktr("Closed boundary ring(s) for role(s) {0} are not in topological order. "
                        + "Sort the way members so consecutive members share an endpoint?"),
                        rolesDisplay)
            .primitives(r)
            .fix(() -> fix)
            .build());
    }

    /**
     * True if every endpoint node (first/last of any way) appears exactly
     * twice across all ways in {@code group} — a necessary condition for
     * the group to form one or more closed rings.
     */
    private static boolean canFormClosedRings(List<Way> group) {
        Map<Node, Integer> endpointCount = new HashMap<>();
        for (Way w : group) {
            if (w.getNodesCount() < 2) return false;
            // Self-closed way contributes two slots at the same node, fine.
            endpointCount.merge(w.firstNode(), 1, Integer::sum);
            endpointCount.merge(w.lastNode(), 1, Integer::sum);
        }
        for (int c : endpointCount.values()) {
            if (c != 2) return false;
        }
        return true;
    }

    /**
     * True if the ways in {@code group} are listed in an order that forms
     * one or more closed rings, where consecutive ways share an endpoint
     * and each ring closes back to its starting node. Direction-agnostic.
     */
    private static boolean isGroupTopologicallySorted(List<Way> group) {
        int n = group.size();
        int i = 0;
        while (i < n) {
            Way start = group.get(i);
            if (start.getNodesCount() < 2) return false;
            // Self-closed standalone ring of 1.
            if (start.firstNode() == start.lastNode()) {
                i++;
                continue;
            }
            if (i + 1 >= n) return false;
            Way next = group.get(i + 1);
            if (next.getNodesCount() < 2) return false;
            Node nf = next.firstNode(), nl = next.lastNode();
            Node sf = start.firstNode(), sl = start.lastNode();
            Node entry, exit;
            if (sf == nf || sf == nl) {
                entry = sl; exit = sf;
            } else if (sl == nf || sl == nl) {
                entry = sf; exit = sl;
            } else {
                return false;
            }
            int j = i + 1;
            while (j < n) {
                Way w = group.get(j);
                if (w.getNodesCount() < 2) return false;
                Node wf = w.firstNode(), wl = w.lastNode();
                if (wf == exit) {
                    exit = wl;
                } else if (wl == exit) {
                    exit = wf;
                } else {
                    return false;
                }
                j++;
                if (exit == entry) break;
            }
            if (exit != entry) return false;
            i = j;
        }
        return true;
    }

    /**
     * Greedily sort the ways in {@code group} into ring order. Each iteration
     * picks the next unvisited way and traces a closed ring by hopping shared
     * endpoints. Returns {@code null} if a ring can't be closed (caller
     * should have verified {@link #canFormClosedRings} first).
     */
    private static List<Way> sortGroupIntoRings(List<Way> group) {
        Set<Way> remaining = new LinkedHashSet<>(group);
        List<Way> sorted = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Way start = remaining.iterator().next();
            remaining.remove(start);
            sorted.add(start);
            if (start.getNodesCount() < 2) return null;
            if (start.firstNode() == start.lastNode()) continue;
            Node ringStart = start.firstNode();
            Node cursor = start.lastNode();
            while (cursor != ringStart) {
                Way next = null;
                for (Way w : remaining) {
                    if (w.firstNode() == cursor) {
                        next = w;
                        cursor = w.lastNode();
                        break;
                    }
                    if (w.lastNode() == cursor) {
                        next = w;
                        cursor = w.firstNode();
                        break;
                    }
                }
                if (next == null) return null;
                remaining.remove(next);
                sorted.add(next);
            }
        }
        return sorted;
    }

    /** True if {@code w} is a member of any relation in {@code rs}. */
    private static boolean isInAnyOf(Way w, Set<Relation> rs) {
        for (OsmPrimitive ref : w.getReferrers()) {
            if (ref instanceof Relation && rs.contains(ref)) return true;
        }
        return false;
    }

    /** True if {@code key} is a source-family tag. */
    private static boolean isSourceKey(String key) {
        return key.equals("source") || key.startsWith("source:")
            || key.equals("attribute:source") || key.startsWith("attribute:source:");
    }

    /**
     * Rule 4334: a node that's a member of both a boundary-relation way
     * AND a non-boundary way shouldn't share node identity across those
     * two roles — boundary geometry should be separate from whatever
     * other features (waterway, highway, building edge, etc.) happen to
     * pass through the same coordinate.
     *
     * <p>Autofix: clone the node onto the boundary side. The clone takes
     * the boundary-way memberships; the original keeps its non-boundary
     * memberships. Mirror of rule 4331's endpoint-reroute logic.
     */
    private void checkBoundaryNodeJoinedToNonBoundary(Node n) {
        List<Way> boundaryWays = new ArrayList<>();
        List<Way> nonBoundaryWays = new ArrayList<>();
        for (OsmPrimitive ref : n.getReferrers()) {
            if (!(ref instanceof Way)) continue;
            Way w = (Way) ref;
            boolean inBoundary = false;
            for (OsmPrimitive wref : w.getReferrers()) {
                if (wref instanceof Relation
                    && "boundary".equals(wref.get("type"))) {
                    inBoundary = true;
                    break;
                }
            }
            if (inBoundary) {
                boundaryWays.add(w);
            } else {
                nonBoundaryWays.add(w);
            }
        }
        if (boundaryWays.isEmpty() || nonBoundaryWays.isEmpty()) return;

        // Clone the node (cleared metadata, same coordinates), then reroute
        // each boundary way to use the clone instead of the original.
        Node clone = new Node(n, true);
        List<Command> cmds = new ArrayList<>();
        cmds.add(new AddCommand(n.getDataSet(), clone));
        for (Way w : boundaryWays) {
            List<Node> updated = new ArrayList<>();
            for (Node node : w.getNodes()) {
                updated.add(node == n ? clone : node);
            }
            cmds.add(new ChangeNodesCommand(w, updated));
        }
        Command fix = new SequenceCommand(
            tr("Clone boundary node and detach boundary ways from the original"),
            cmds);

        errors.add(TestError.builder(this, Severity.WARNING, CODE_BOUNDARY_NODE_JOINED_NON_BOUNDARY)
            .message(tr("[ohm] Boundary geometry - node joined to both boundary and non-boundary ways; autofix by cloning"),
                     marktr("Node is shared between {0,choice,1#1 boundary way|1<{0,number,integer} boundary ways} "
                        + "and {1,choice,1#1 non-boundary way|1<{1,number,integer} non-boundary ways}. "
                        + "Boundary geometry should be separate from the other features passing through this point. "
                        + "Clone the node onto the boundary side?"),
                        boundaryWays.size(), nonBoundaryWays.size())
            .primitives(n)
            .fix(() -> fix)
            .build());
    }

    /**
     * Return any way that contains {@code n} as a node AND is a member of a
     * {@code type=boundary} relation, or {@code null} if no such way exists.
     */
    private static Way findBoundaryWayContaining(Node n) {
        for (OsmPrimitive ref : n.getReferrers()) {
            if (!(ref instanceof Way)) continue;
            Way w = (Way) ref;
            for (OsmPrimitive wref : w.getReferrers()) {
                if (wref instanceof Relation
                    && "boundary".equals(wref.get("type"))) {
                    return w;
                }
            }
        }
        return null;
    }

    /**
     * True if {@code key} is a date- or source-family tag (allowed to remain
     * on a boundary node).
     */
    private static boolean isDateOrSourceKey(String key) {
        return key.equals("start_date") || key.equals("end_date")
            || key.startsWith("start_date:") || key.startsWith("end_date:")
            || key.equals("source") || key.startsWith("source:")
            || key.equals("attribute:source") || key.startsWith("attribute:source:");
    }

    // ============================================================
    // Rule 4335: same-class polygon overlap in time and space.
    // ============================================================

    /** Year extraction at the head of an ISO calendar date string. */
    private static final Pattern POLYGON_YEAR_HEAD = Pattern.compile("^(-?\\d{1,4})(?:-|$)");

    /**
     * One polygon candidate for rule 4335. Built once during the {@code visit}
     * pass, then compared pairwise against other candidates with the same
     * {@code groupKey} in {@link #endTest}.
     */
    private static final class PolygonCandidate {
        final OsmPrimitive primitive;
        final String groupKey;          // "building" or "admin_level=N"
        final int startYear;            // lower-bound year
        final int endYear;              // upper-bound year; MAX_VALUE if open-ended
        final BBox bbox;
        final List<List<Node>> rings;   // outer rings (each a closed list of nodes); inners ignored for MVP

        PolygonCandidate(OsmPrimitive primitive, String groupKey,
                         int startYear, int endYear,
                         BBox bbox, List<List<Node>> rings) {
            this.primitive = primitive;
            this.groupKey = groupKey;
            this.startYear = startYear;
            this.endYear = endYear;
            this.bbox = bbox;
            this.rings = rings;
        }
    }

    /**
     * If {@code p} is eligible (building, or admin boundary), build and
     * register a {@link PolygonCandidate}. Skips primitives with
     * {@code natural=*} or without a parseable {@code start_date}.
     */
    private void collectPolygonCandidate(OsmPrimitive p) {
        if (p.get("natural") != null) return;
        String groupKey = polygonGroupKey(p);
        if (groupKey == null) return;

        Integer sy = parsePolygonYear(p.get("start_date"));
        if (sy == null) return;  // need a parseable start to compare time
        Integer ey = parsePolygonYear(p.get("end_date"));
        int endYear = ey != null ? ey : Integer.MAX_VALUE;

        List<List<Node>> rings = polygonRings(p);
        if (rings == null || rings.isEmpty()) return;

        BBox bbox = new BBox();
        for (List<Node> ring : rings) {
            for (Node n : ring) {
                if (n.getCoor() != null) bbox.add(n.getCoor());
            }
        }
        if (!bbox.isValid()) return;

        polygonCandidates.add(new PolygonCandidate(
            p, groupKey, sy, endYear, bbox, rings));
    }

    /**
     * Group key for the polygon-overlap rule. Returns {@code null} when the
     * primitive isn't a comparison candidate (e.g. a way without
     * {@code building=*}; an admin boundary missing {@code admin_level}).
     */
    private static String polygonGroupKey(OsmPrimitive p) {
        if (p.get("building") != null) return "building";
        if (p instanceof Relation) {
            Relation r = (Relation) p;
            if ("boundary".equals(r.get("type"))
                && "administrative".equals(r.get("boundary"))) {
                String level = r.get("admin_level");
                if (level != null && !level.isEmpty()) {
                    return "admin_level=" + level;
                }
            }
        }
        return null;
    }

    private static Integer parsePolygonYear(String dateValue) {
        if (dateValue == null || dateValue.isEmpty()) return null;
        Matcher m = POLYGON_YEAR_HEAD.matcher(dateValue);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Build the outer ring(s) of a polygon. For closed ways, returns a single
     * list containing the way's nodes. For multipolygon / boundary relations,
     * returns the assembled outer rings via JOSM's {@code Multipolygon}.
     * Returns {@code null} if the primitive isn't a polygon shape.
     */
    private static List<List<Node>> polygonRings(OsmPrimitive p) {
        if (p instanceof Way) {
            Way w = (Way) p;
            if (!w.isClosed() || w.getNodesCount() < 4) return null;
            List<List<Node>> rings = new ArrayList<>();
            rings.add(new ArrayList<>(w.getNodes()));
            return rings;
        }
        if (p instanceof Relation) {
            Relation r = (Relation) p;
            // Multipolygon needs type=multipolygon OR type=boundary to assemble.
            if (!("multipolygon".equals(r.get("type"))
                  || "boundary".equals(r.get("type")))) return null;
            try {
                Multipolygon mp = new Multipolygon(r);
                List<List<Node>> rings = new ArrayList<>();
                for (Multipolygon.PolyData poly : mp.getOuterPolygons()) {
                    List<Node> ring = poly.getNodes();
                    if (ring != null && ring.size() >= 4) {
                        rings.add(new ArrayList<>(ring));
                    }
                }
                return rings.isEmpty() ? null : rings;
            } catch (RuntimeException e) {
                return null;  // malformed multipolygon — skip
            }
        }
        return null;
    }

    /**
     * After all visits, iterate candidate pairs by group, applying the
     * bbox → time → geometry filter cascade. For each pair that overlaps in
     * all three respects, emit one warning per pair (attached to both
     * primitives).
     */
    private void runPolygonOverlapPairwiseCheck() {
        // Group by group key.
        Map<String, List<PolygonCandidate>> groups = new LinkedHashMap<>();
        for (PolygonCandidate c : polygonCandidates) {
            groups.computeIfAbsent(c.groupKey, k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<PolygonCandidate>> entry : groups.entrySet()) {
            List<PolygonCandidate> group = entry.getValue();
            // Sort by primitive id for deterministic emission order
            // (JOSM visit order isn't guaranteed stable across runs).
            group.sort((x, y) -> Long.compare(x.primitive.getUniqueId(), y.primitive.getUniqueId()));
            int n = group.size();
            for (int i = 0; i < n; i++) {
                PolygonCandidate a = group.get(i);
                for (int j = i + 1; j < n; j++) {
                    PolygonCandidate b = group.get(j);
                    if (!a.bbox.intersects(b.bbox)) continue;
                    if (!polygonTimeOverlap(a, b)) continue;
                    if (!polygonGeometryOverlap(a, b)) continue;
                    emitPolygonOverlap(a, b);
                }
            }
        }
    }

    /**
     * True if {@code a} and {@code b} overlap in time at year precision.
     * Inclusive on both ends: year-touching counts as overlap because the
     * two features are physical objects, not legal entities. A year-only
     * date like {@code 1920} covers all of 1920-01-01..1920-12-31, so
     * {@code A.end_date=1920} and {@code B.start_date=1920} means both
     * occupied the same physical space at some moment within 1920. This
     * deliberately diverges from the chronology convention (rule 4230),
     * where year-touching is read as a clean handoff between successive
     * legal entities. Open-ended end ({@code MAX_VALUE}) is treated as
     * "still extant" per the polygon-overlap spec.
     */
    private static boolean polygonTimeOverlap(PolygonCandidate a, PolygonCandidate b) {
        return a.startYear <= b.endYear && b.startYear <= a.endYear;
    }

    /**
     * True if any of {@code a}'s outer rings has a non-empty geometric
     * intersection with any of {@code b}'s outer rings. Containment counts
     * as overlap per Jeff's spec.
     */
    private static boolean polygonGeometryOverlap(PolygonCandidate a, PolygonCandidate b) {
        for (List<Node> aring : a.rings) {
            for (List<Node> bring : b.rings) {
                PolygonIntersection result =
                    Geometry.polygonIntersection(toINodes(aring), toINodes(bring));
                if (result != PolygonIntersection.OUTSIDE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<INode> toINodes(List<Node> nodes) {
        List<INode> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) out.add(n);
        return out;
    }

    private void emitPolygonOverlap(PolygonCandidate a, PolygonCandidate b) {
        String aRange = formatYearRange(a.startYear, a.endYear);
        String bRange = formatYearRange(b.startYear, b.endYear);
        errors.add(TestError.builder(this, Severity.WARNING, CODE_POLYGON_OVERLAP_TIME_SPACE)
            .message(tr("[ohm] Suspicious polygon - same-class polygons overlap in time AND space; unfixable, please review"),
                     marktr("{0} ({1}) and {2} ({3}) — both {4} — overlap geometrically and have intersecting date ranges. "
                        + "Same-class polygons should not occupy the same space at the same time. "
                        + "Reconcile dates, fix geometry, or split into distinct features."),
                        a.primitive.getDisplayType() + " " + a.primitive.getId(),
                        aRange,
                        b.primitive.getDisplayType() + " " + b.primitive.getId(),
                        bRange,
                        a.groupKey)
            .primitives(Arrays.asList(a.primitive, b.primitive))
            .build());
    }

    private static String formatYearRange(int start, int end) {
        if (end == Integer.MAX_VALUE) return start + "..present";
        if (start == end) return Integer.toString(start);
        return start + ".." + end;
    }
}
