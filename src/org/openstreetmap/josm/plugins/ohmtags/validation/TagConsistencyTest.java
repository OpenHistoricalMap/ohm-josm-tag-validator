// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.ohmtags.validation;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
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

/**
 * Validates tag-consistency concerns on OHM features: names, sources, and
 * external-identifier references (Wikidata, Wikipedia).
 *
 * <h3>Rules</h3>
 *
 * <p><b>Name consistency.</b>
 * <ul>
 *   <li>A feature with name-family keys ({@code name:XX}, {@code alt_name},
 *       {@code old_name}, {@code short_name}, {@code official_name}, and
 *       their {@code :XX} variants) but no plain {@code name} tag is warned.
 *       OSM/OHM convention is for the plain {@code name} to be populated
 *       as the canonical display name.</li>
 *   <li>Any name-family value containing parentheses is warned;
 *       parenthesized content (commonly dates or disambiguators) is
 *       discouraged in OSM/OHM names. No autofix — removing content
 *       requires human judgment.</li>
 * </ul>
 *
 * <p><b>Wikidata.</b>
 * <ul>
 *   <li>Named features without a {@code wikidata} tag are flagged as
 *       errors only when they also carry a notability signal: any
 *       {@code wikipedia=*}, {@code historic=*},
 *       {@code boundary=administrative}, a notable value of
 *       {@code place} / {@code tourism} / {@code amenity} /
 *       {@code building} / {@code military}, OR if the primitive is a
 *       relation. Random named ways/nodes are no longer warned.</li>
 *   <li>If {@code wikipedia=*} is present, an autofix is offered that
 *       performs a runtime Wikidata API lookup to derive the QID. The
 *       lookup happens lazily when the user clicks Fix; if it fails
 *       (network error, missing article, no QID), the fix is a no-op.</li>
 * </ul>
 *
 * <p><b>Historic.</b>
 * <ul>
 *   <li>Any feature carrying {@code historic=*} is warned, prompting
 *       the editor to confirm the entity has actually passed into
 *       history before applying the tag (4319). Unfixable.</li>
 * </ul>
 *
 * <p><b>Source.</b> The slot contract (loosened in v0.5):
 * <ul>
 *   <li>{@code source} (and numeric variants {@code source:N}) — URL or
 *       text. Both placements are valid.</li>
 *   <li>{@code source:name} (and {@code source:N:name}) — text only.</li>
 *   <li>{@code source:url} (and {@code source:N:url}) — URL only.</li>
 * </ul>
 *
 * <p>Concretely:
 * <ul>
 *   <li>Named features without any {@code source*} tag are warned — named
 *       features should document the provenance of their geometry and
 *       metadata.</li>
 *   <li>{@code source} / {@code source:N} set to the literal value
 *       {@code Wikipedia} or {@code Wikidata} (any case) is warned —
 *       these aren't acceptable primary geometry sources regardless of
 *       whether companion {@code wikipedia=*} / {@code wikidata=*} tags
 *       exist.</li>
 *   <li>Any {@code source[:N]?[:url]?} key set to a URL-like value
 *       missing a scheme ({@code example.com/page}) is warned with an
 *       autofix prepending {@code https://} (rule 4307).</li>
 *   <li>{@code source[:N]?:name} containing a URL (rule 4324) is moved
 *       to {@code source[:N]?:url} when that slot is empty; otherwise
 *       flagged for manual review.</li>
 *   <li>{@code source[:N]?:url} containing non-URL text (rule 4325) is
 *       moved through a fallback chain: companion {@code source[:N]?},
 *       then {@code source[:N]?:name}, then {@code source[:N]?:note};
 *       if all three are full, flagged for manual review.</li>
 *   <li>When {@code source} and {@code source:url} both hold URLs but
 *       differ (rule 4312), the {@code :url} value is moved to the next
 *       available {@code source:N} slot.</li>
 *   <li>When a {@code source} value contains semicolons,
 *       {@link #checkSemicolonSeparatedSource} splits the items: 1 URL
 *       + 1 text → {@code source=text}, {@code source:url=URL}
 *       (rule 4314, autofixable); multiple URLs → enumerated into
 *       {@code source:N} slots past the highest existing index
 *       (rule 4315, autofixable); multiple text strings → warn only,
 *       semicolons may be legitimate punctuation in a single citation
 *       (rule 4316, unfixable); 3+ mixed items → warn only (rule 4317).</li>
 *   <li>Any attribute-scoped source ({@code <attr>:source}, e.g.
 *       {@code start_date:source}) set to {@code Wikipedia} requires a
 *       companion {@code wikipedia=*} tag; similarly {@code Wikidata}
 *       requires {@code wikidata=*}. Otherwise warned.</li>
 *   <li>Sub-keys {@code source:id}, {@code source:archive_url}, and
 *       {@code source:wikidata} are not flagged — they are recognized
 *       legitimate source-related tags.</li>
 * </ul>
 */
public class TagConsistencyTest extends Test {

    // --- Error codes --- distinct range from DateTagTest's 4200s -------------
    protected static final int CODE_MISSING_PLAIN_NAME = 4300;
    protected static final int CODE_NAME_HAS_PARENS = 4301;
    protected static final int CODE_MISSING_WIKIDATA = 4302;
    protected static final int CODE_MISSING_SOURCE = 4303;
    protected static final int CODE_SOURCE_IS_WIKIPEDIA = 4304;
    protected static final int CODE_SOURCE_IS_WIKIDATA = 4305;
    // 4306: retired in v0.5 — non-URL `source` is now valid (slot typing loosened).
    protected static final int CODE_SOURCE_MISSING_SCHEME = 4307;
    protected static final int CODE_ATTR_SOURCE_WIKIPEDIA = 4308;
    protected static final int CODE_ATTR_SOURCE_WIKIDATA = 4309;
    // 4310: retired in v0.5 — `source:name` without companion is a valid state.
    // 4311: retired in v0.5 — duplicate values in source/source:url are harmless.
    protected static final int CODE_SOURCE_URL_CONFLICTS = 4312;
    // 4313: retired in v0.5 — `source` text + `source:url` URL is a valid layout.
    protected static final int CODE_SOURCE_SEMICOLON_URL_TEXT = 4314;
    protected static final int CODE_SOURCE_SEMICOLON_MULTI_URL = 4315;
    protected static final int CODE_SOURCE_SEMICOLON_MULTI_TEXT = 4316;
    protected static final int CODE_SOURCE_SEMICOLON_MIXED = 4317;
    protected static final int CODE_RELATION_LABEL_MEMBER = 4318;
    protected static final int CODE_HISTORIC_SUSPICIOUS = 4319;
    protected static final int CODE_NAME_HAS_HISTORIC = 4320;
    // 4321: retired in v0.5 — non-URL `source` is now valid even with :name.
    // 4322: retired in v0.5 — multi-URL split now always appends past max source:N.
    // 4323: retired in v0.5 — multi-text split now always appends past max source:N.
    protected static final int CODE_SOURCE_NAME_HAS_URL = 4324;
    protected static final int CODE_SOURCE_URL_HAS_TEXT = 4325;
    protected static final int CODE_NODE_TAGS_REDUNDANT_WITH_PARENT_WAY = 4326;
    protected static final int CODE_NAME_HAS_WHITESPACE = 4327;
    protected static final int CODE_WIKIDATA_MALFORMED = 4328;
    protected static final int CODE_WIKIPEDIA_MALFORMED = 4329;
    // 4330, 4331, 4332 moved to BoundaryTest (boundary-geometry rules).
    protected static final int CODE_MAPWARPER_SOURCE = 4333;
    // 4334, 4335 moved to BoundaryTest (boundary-geometry rules).

    // --- Notability heuristics for the missing-wikidata rule (4302) ----------
    // A named feature only triggers 4302 when it carries one of these signals
    // OR is a relation. Designed to drop the noise from random named buildings,
    // local roads, and similar ordinary features.

    private static final Set<String> PLACE_NOTABLE = Set.of(
        "city", "town", "village", "hamlet", "suburb", "neighbourhood",
        "county", "state", "country", "region", "island", "archipelago",
        "continent");

    private static final Set<String> TOURISM_NOTABLE = Set.of(
        "museum", "attraction", "monument", "artwork", "gallery");

    private static final Set<String> AMENITY_NOTABLE = Set.of(
        "place_of_worship", "university", "courthouse", "townhall",
        "library", "theatre", "hospital", "school");

    private static final Set<String> BUILDING_NOTABLE = Set.of(
        "castle", "cathedral", "church", "chapel", "mosque",
        "synagogue", "temple", "palace");

    private static final Set<String> MILITARY_NOTABLE = Set.of(
        "castle", "fort", "barracks");

    /**
     * Shared HTTP client for the runtime Wikipedia → Wikidata QID lookup
     * (see {@link #lookupWikidataQid}). Static so we don't churn on every
     * fix invocation.
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    /** Matches the plain QID inside Wikidata API responses. */
    private static final Pattern QID_PATTERN = Pattern.compile("\"id\":\"(Q\\d+)\"");

    /** Validates the language-prefix portion of a wikipedia=lang:Title value. */
    private static final Pattern WIKIPEDIA_LANG = Pattern.compile("[a-z]{2,10}");

    /** Mapwarper tile-endpoint URL, capturing the numeric map id. */
    private static final Pattern MAPWARPER_TILE = Pattern.compile(
        "^https://(?:www\\.)?mapwarper\\.net/maps/tile/(\\d{1,6})/\\{z\\}/\\{x\\}/\\{y\\}\\.png$");

    /** Mapwarper canonical map-page URL, capturing the numeric map id. */
    private static final Pattern MAPWARPER_CANONICAL = Pattern.compile(
        "^https://(?:www\\.)?mapwarper\\.net/maps/(\\d{1,6})$");

    /** Matches a {@code source:url} or {@code source:N:url} key. */
    private static final Pattern MAPWARPER_URL_KEY = Pattern.compile(
        "^source(:.+)?:url$");

    /** Matches a bare {@code source} or {@code source:N} key. */
    private static final Pattern MAPWARPER_BARE_KEY = Pattern.compile(
        "^source(:.+)?$");

    /** Mapwarper /maps/&lt;id&gt;.json title extraction (handles both {@code "title":"..."} top-level and the {@code "map":{"title":"..."}} variant). */
    private static final Pattern MAPWARPER_TITLE_PATTERN =
        Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * Strict QID shape for {@code wikidata=*}: one capital Q followed by
     * 1+ digits. Anything else is malformed.
     */
    private static final Pattern WIKIDATA_QID = Pattern.compile("^Q\\d+$");

    /**
     * Strict {@code <lang>:<title>} shape for {@code wikipedia=*}: a
     * lowercase language code (2-10 chars, matching {@link #WIKIPEDIA_LANG}),
     * a colon, then a non-empty title. The title can contain spaces and
     * most printable characters; we just require it to be non-empty.
     *
     * <p>Negative lookahead rules out the obvious URL false-positive:
     * {@code https://en.wikipedia.org/wiki/Eiffel_Tower} would otherwise
     * match because {@code https} is a 5-char lowercase string followed by
     * {@code :}. {@code http:} and {@code https:} prefixes are excluded so
     * URL-shaped values fall through to fire 4329 unfixable.
     */
    private static final Pattern WIKIPEDIA_VALUE =
        Pattern.compile("^(?!https?:)[a-z]{2,10}:.+");

    /**
     * Matches the substring "historic" anywhere in a name-family value
     * (case-insensitive). Catches "historic", "Historic", "historical",
     * "Prehistoric", "ahistorical", etc. — any present-day historicizing
     * frame in the name, however constructed.
     */
    private static final Pattern HISTORIC_IN_NAME =
        Pattern.compile("historic", Pattern.CASE_INSENSITIVE);

    // --- Patterns ------------------------------------------------------------

    /**
     * Matches name-family keys: {@code name}, {@code name:XX}, {@code alt_name},
     * {@code old_name}, {@code short_name}, {@code official_name}, and their
     * {@code :XX} variants.
     */
    private static final Pattern NAME_FAMILY_KEY =
        Pattern.compile("^(?:name|alt_name|old_name|short_name|official_name)(?::[A-Za-z0-9_-]+)?$");

    /**
     * Matches a {@code source} key, either plain ({@code source}) or with a
     * numeric-only sub-index ({@code source:1}, {@code source:2}). This is
     * what "plain source or numeric variant" means throughout these rules.
     * Captures the numeric sub-index if present (group 1).
     */
    private static final Pattern SOURCE_KEY =
        Pattern.compile("^source(?::(\\d+))?$");

    /**
     * Matches a source-name key: {@code source:name} or {@code source:N:name}.
     * Captures the optional numeric sub-index (group 1).
     */
    private static final Pattern SOURCE_NAME_KEY =
        Pattern.compile("^source(?::(\\d+))?:name$");

    /**
     * Matches a source-URL key: {@code source:url} or {@code source:N:url}.
     * Captures the optional numeric sub-index (group 1). Used by
     * {@link #checkSourceUrlConsolidation} to enumerate all url-shaped
     * keys for per-pair consolidation.
     */
    private static final Pattern SOURCE_URL_KEY =
        Pattern.compile("^source(?::(\\d+))?:url$");

    /**
     * Matches any attribute-scoped source key: anything ending in
     * {@code :source} that is NOT itself {@code source:N} (those are covered
     * by {@link #SOURCE_KEY}). Captures the attribute prefix (group 1).
     */
    private static final Pattern ATTR_SOURCE_KEY =
        Pattern.compile("^([A-Za-z][A-Za-z0-9_:-]*?):source$");

    /** Strict URL check — must start with http:// or https://. */
    private static final Pattern URL_WITH_SCHEME =
        Pattern.compile("^https?://.+");

    /**
     * URL-shaped but missing scheme: contains at least one dot separating
     * non-whitespace parts before any slash, and has no whitespace anywhere.
     * Intended to catch things like {@code example.com/page},
     * {@code en.wikipedia.org/wiki/Foo}, {@code www.loc.gov/item/X}.
     */
    private static final Pattern URL_MISSING_SCHEME =
        Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}(?:/\\S*)?$");

    public TagConsistencyTest() {
        super(tr("OHM tag consistency"),
              tr("Checks for missing or inconsistent name, wikidata, and source tags."));
    }

    @Override
    public void visit(org.openstreetmap.josm.data.osm.Node n) {
        checkPrimitive(n);
        checkNodeTagsRedundantWithParentWay(n);
    }

    @Override
    public void visit(org.openstreetmap.josm.data.osm.Way w) {
        checkPrimitive(w);
    }

    @Override
    public void visit(org.openstreetmap.josm.data.osm.Relation r) {
        checkPrimitive(r);
        checkLabelMembers(r);
    }

    /**
     * Warn once per relation that contains at least one {@code role=label}
     * member. OHM renderers auto-generate label points server-side, so
     * editor-supplied labels are usually unnecessary. The warning also
     * prompts the editor to download parent relations of the shared label
     * object before making changes.
     */
    private void checkLabelMembers(Relation r) {
        List<String> labels = new ArrayList<>();
        for (RelationMember rm : r.getMembers()) {
            if ("label".equals(rm.getRole()) && rm.getMember() != null) {
                OsmPrimitive m = rm.getMember();
                labels.add(m.getType().getAPIName().substring(0, 1) + "/" + m.getId());
            }
        }
        if (labels.isEmpty()) return;

        errors.add(TestError.builder(this, Severity.WARNING, CODE_RELATION_LABEL_MEMBER)
            .message(tr("[ohm] Suspicious member - role=label; unfixable, please review"),
                     marktr("OHM servers automatically generate label points; only use these "
                        + "when necessary. To verify, download all parent relations of "
                        + "this label object (File ▸ Download parent relations / ways). "
                        + "role=label members on this relation: {0}."),
                        String.join(", ", labels))
            .primitives(r)
            .build());
    }

    /**
     * Flag a node whose entire tag set is duplicated on one of its parent
     * ways. This typically arises when an editor tags both the way and one
     * of its constituent nodes for the same feature — only the way needs
     * the tags. The autofix removes every tag from the node.
     *
     * <p>"Shared" means the node carries no tag the way doesn't already have
     * with the same value. Only one parent way needs to fully cover the
     * node's tags for the rule to fire (a node shared between two ways with
     * different attributes is still over-tagged relative to whichever one
     * carries the duplicate set).
     */
    private void checkNodeTagsRedundantWithParentWay(Node n) {
        Map<String, String> nodeTags = n.getKeys();
        if (nodeTags.isEmpty()) return;

        for (OsmPrimitive parent : n.getReferrers()) {
            if (!(parent instanceof Way)) continue;
            Way w = (Way) parent;
            boolean allShared = true;
            for (Map.Entry<String, String> e : nodeTags.entrySet()) {
                if (!e.getValue().equals(w.get(e.getKey()))) {
                    allShared = false;
                    break;
                }
            }
            if (!allShared) continue;

            List<Command> cmds = new ArrayList<>();
            for (String key : nodeTags.keySet()) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(n), key, null));
            }
            Command fix = new SequenceCommand(tr("Remove redundant node tags"), cmds);
            errors.add(TestError.builder(this, Severity.WARNING,
                                         CODE_NODE_TAGS_REDUNDANT_WITH_PARENT_WAY)
                .message(tr("[ohm] Suspicious tags - node with no unique tags from parent way; autofix by removing all node tags"),
                         marktr("All {0} tag(s) on this node are duplicated on parent way w/{1}; "
                            + "remove the node tags?"),
                            nodeTags.size(), Long.toString(w.getId()))
                .primitives(n)
                .fix(() -> fix)
                .build());
            return;
        }
    }

    private void checkPrimitive(OsmPrimitive p) {
        if (!p.hasKeys()) return;

        // Source:url consolidation runs before the per-key source check so
        // that if it fires, it does so against the un-processed state.
        checkSourceUrlConsolidation(p);
        checkSourceNameContents(p);
        checkMapwarperSource(p);

        // Suspicious historic=*. OHM convention is that historic=* applies
        // to entities that have actually passed into history; using it on
        // a still-current feature is premature. We emit on every historic=*
        // tag and let the editor confirm.
        String historic = p.get("historic");
        if (historic != null) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_HISTORIC_SUSPICIOUS)
                .message(tr("[ohm] Suspicious tag - historic; unfixable, please review if object was historic at these times"),
                         marktr("historic={0}: confirm the entity has actually passed into "
                            + "history before applying this tag."),
                            historic)
                .primitives(p)
                .build());
        }

        boolean hasAnyNameFamily = false;
        boolean hasPlainName = p.get("name") != null;

        for (String key : p.keySet()) {
            // Name-family handling
            if (NAME_FAMILY_KEY.matcher(key).matches()) {
                hasAnyNameFamily = true;
                String value = p.get(key);
                if (value != null) {
                    checkNameForDateContent(p, key, value);
                    checkNameWhitespace(p, key, value);
                }
                if (value != null && HISTORIC_IN_NAME.matcher(value).find()) {
                    errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_HISTORIC)
                        .message(tr("[ohm] Name warning - \"historic\" in name; unfixable, please confirm object was historic at these times."),
                                 marktr("{0}={1}: \"historic\" in a name often reflects a "
                                    + "present-day perspective. In OHM, confirm the "
                                    + "entity was actually called this at the time it "
                                    + "existed."),
                                    key, value)
                        .primitives(p)
                        .build());
                }
            }

            // Source handling
            Matcher sourceMatch = SOURCE_KEY.matcher(key);
            if (sourceMatch.matches()) {
                checkSourceTag(p, key, p.get(key), sourceMatch.group(1));
                continue;
            }

            Matcher attrSourceMatch = ATTR_SOURCE_KEY.matcher(key);
            if (attrSourceMatch.matches()
                && !SOURCE_KEY.matcher(key).matches()
                && !SOURCE_NAME_KEY.matcher(key).matches()) {
                // attrSourceMatch.group(1) captures the attribute name without
                // the trailing colon (e.g. "start_date"). Append ":" so the
                // downstream key-construction convention (attrPrefix +
                // "source") gives the right prefixed key.
                checkAttrSourceTag(p, key, p.get(key), attrSourceMatch.group(1) + ":");
                continue;
            }
        }

        if (!hasAnyNameFamily) return; // No name-related checks apply.

        // Rule: any name-family keys but no plain name. Skip on type=route
        // relations — routes are conventionally identified by ref (route
        // number / designation) rather than a country-neutral name; a route
        // legitimately may have only name:lang=* variants.
        if (!hasPlainName && !isRouteRelation(p)) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_MISSING_PLAIN_NAME)
                .message(tr("[ohm] Missing tag - name=*; unfixable, please review"),
                         marktr("Feature has name-family keys ({0}, etc.) but no plain "
                            + "''name'' key. Please add a canonical name."),
                            firstNameFamilyKeyFound(p))
                .primitives(p)
                .build());
        }

        // Rule: named feature without wikidata, gated by a notability signal
        // (wikipedia=*, historic=*, place/tourism/amenity/building/military
        // notable values) OR being a relation. Random named ways/nodes do
        // not fire the rule. If wikipedia=* is present, an autofix is
        // offered that performs a runtime Wikidata API lookup to derive
        // the QID (lazy — runs only when the user clicks Fix).
        if (getNonEmpty(p, "wikidata") == null && hasNotabilitySignal(p)) {
            TestError.Builder builder = TestError.builder(this, Severity.ERROR, CODE_MISSING_WIKIDATA)
                .message(tr("[ohm] Missing tag - wikidata; unfixable, please review"),
                         marktr("Wikidata QIDs help link OHM data to other databases."))
                .primitives(p);
            String wikipediaValue = getNonEmpty(p, "wikipedia");
            if (wikipediaValue != null) {
                builder.fix(() -> {
                    Optional<String> qidOpt = lookupWikidataQid(wikipediaValue);
                    if (qidOpt.isEmpty()) return null;
                    return new ChangePropertyCommand(Arrays.asList(p), "wikidata", qidOpt.get());
                });
            }
            errors.add(builder.build());
        }

        // Rules 4328 / 4329: format checks for wikidata and wikipedia tag
        // values. Catch garbage values like wikidata=notaqid or
        // wikipedia=not_a_real_format that pass the presence-only checks
        // but won't work in downstream consumers (the 4302 autofix would
        // silently fail on a malformed wikipedia value too).
        String wikidataValue = p.get("wikidata");
        if (wikidataValue != null && !wikidataValue.isEmpty()
            && !WIKIDATA_QID.matcher(wikidataValue).matches()) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_WIKIDATA_MALFORMED)
                .message(tr("[ohm] Malformed tag - wikidata value is not a QID; unfixable, please review"),
                         marktr("wikidata={0} is not a valid Wikidata QID. Expected shape: "
                            + "''Q'' followed by digits, e.g. Q243."),
                            wikidataValue)
                .primitives(p)
                .build());
        }
        String wikipediaValueCheck = p.get("wikipedia");
        if (wikipediaValueCheck != null && !wikipediaValueCheck.isEmpty()
            && !WIKIPEDIA_VALUE.matcher(wikipediaValueCheck).matches()) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_WIKIPEDIA_MALFORMED)
                .message(tr("[ohm] Malformed tag - wikipedia value is not <lang>:<title>; unfixable, please review"),
                         marktr("wikipedia={0} is not in the expected ''<lang>:<title>'' "
                            + "format (e.g. ''en:Eiffel Tower''). Downstream lookups will fail."),
                            wikipediaValueCheck)
                .primitives(p)
                .build());
        }

        // Rule: named feature without any source*. Skips type=chronology
        // relations: they're aggregator wrappers around member relations
        // (each of which has its own source provenance), so requiring a
        // top-level source tag on the chronology adds noise without
        // signal — see issue #23.
        if (!hasAnySourceTag(p) && !isChronologyRelation(p)) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_MISSING_SOURCE)
                .message(tr("[ohm] Missing tag - source on named feature; unfixable, please review & add"),
                         marktr("other mappers are lost without it."))
                .primitives(p)
                .build());
        }
    }

    /**
     * True if the primitive is a relation with {@code type=chronology}.
     * Used to suppress rules that don't make sense on chronology aggregators
     * (which exist to bundle member relations representing the same entity
     * across time, and inherit semantic content from those members).
     */
    private static boolean isChronologyRelation(OsmPrimitive p) {
        return p instanceof Relation && "chronology".equals(p.get("type"));
    }

    // --- Helpers -------------------------------------------------------------

    /**
     * Notability heuristic for the missing-wikidata rule (4302). Returns
     * true if the primitive is plausibly the kind of thing a Wikipedia
     * editor would write about — relations always count; otherwise the
     * primitive must carry one of: {@code wikipedia=*}, {@code historic=*},
     * {@code boundary=administrative}, or a notable value of
     * {@code place}, {@code tourism}, {@code amenity}, {@code building},
     * or {@code military}. The notable-value sets are deliberately tight
     * so the rule isn't tripped by random named buildings or local roads.
     */
    private static boolean hasNotabilitySignal(OsmPrimitive p) {
        // Maritime boundary-segment ways: members of a type=boundary
        // relation that carry maritime=yes are segments of a larger
        // boundary entity; they don't have their own Wikidata identity.
        // The parent boundary relation does, and the rule still fires
        // on the relation itself.
        if (p instanceof Way && "yes".equals(p.get("maritime"))) {
            for (OsmPrimitive parent : p.getReferrers()) {
                if (parent instanceof Relation
                    && "boundary".equals(parent.get("type"))) {
                    return false;
                }
            }
        }

        if (p instanceof Relation) return true;
        if (getNonEmpty(p, "wikipedia") != null) return true;
        if (p.get("historic") != null) return true;
        if ("administrative".equals(p.get("boundary"))) return true;

        String place = p.get("place");
        if (place != null && PLACE_NOTABLE.contains(place)) return true;

        String tourism = p.get("tourism");
        if (tourism != null && TOURISM_NOTABLE.contains(tourism)) return true;

        String amenity = p.get("amenity");
        if (amenity != null && AMENITY_NOTABLE.contains(amenity)) return true;

        String building = p.get("building");
        if (building != null && BUILDING_NOTABLE.contains(building)) return true;

        String military = p.get("military");
        if (military != null && MILITARY_NOTABLE.contains(military)) return true;

        return false;
    }

    /**
     * Resolve a {@code wikipedia=lang:Article Title} value to a Wikidata
     * QID by calling the Wikidata API at fix time.
     *
     * <p>Returns {@code Optional.empty()} for any failure mode — unparseable
     * value, network error, non-200 response, missing article, or no QID
     * in the response. The autofix supplier in 4302 returns a no-op (null)
     * Command on empty, so a Fix click that can't resolve the article does
     * nothing rather than corrupting the data.
     *
     * <p>Performed lazily (only when the user clicks Fix) to keep
     * validation fast and offline-friendly.
     */
    private static Optional<String> lookupWikidataQid(String wikipediaValue) {
        if (wikipediaValue == null || wikipediaValue.isEmpty()) return Optional.empty();
        int colon = wikipediaValue.indexOf(':');
        String lang;
        String title;
        if (colon < 0) {
            lang = "en";
            title = wikipediaValue;
        } else {
            lang = wikipediaValue.substring(0, colon);
            title = wikipediaValue.substring(colon + 1);
        }
        if (lang.isEmpty() || title.isEmpty()) return Optional.empty();
        if (!WIKIPEDIA_LANG.matcher(lang).matches()) return Optional.empty();

        try {
            String url = "https://www.wikidata.org/w/api.php"
                + "?action=wbgetentities"
                + "&sites=" + lang + "wiki"
                + "&titles=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&props=info&format=json";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent",
                    "OHM_Tag_Validator (https://github.com/OpenHistoricalMap/ohm-josm-tag-validator)")
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            String body = response.body();
            // wbgetentities returns "missing" when the article doesn't exist
            // or has no Wikidata mapping — bail before regex-finding a QID.
            if (body.contains("\"missing\"")) return Optional.empty();
            Matcher m = QID_PATTERN.matcher(body);
            if (m.find()) {
                return Optional.of(m.group(1));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Rule 4333: detect Mapwarper URLs in {@code source}/{@code source:url}
     * (and their numbered {@code source:N}/{@code source:N:url} variants)
     * and offer to migrate them to OHM's preferred layout — separate
     * {@code source:tiles} for the raster tile endpoint, {@code source:url}
     * for the canonical map page, and {@code source:name} for the human-
     * readable title fetched from the Mapwarper API.
     *
     * <p>Mirrors {@code MapwarperSourceFixer.py} in the qa-scripts repo:
     * <ul>
     *   <li><b>Rule 1A</b>: tile URL on {@code source[:N:]url} →
     *       add {@code source[:N:]tiles=&lt;original&gt;}, rewrite
     *       {@code source[:N:]url} to canonical map page.</li>
     *   <li><b>Rule 1B</b>: tile URL on bare {@code source[:N]} →
     *       add {@code source[:N:]tiles=&lt;original&gt;}, move any existing
     *       {@code source[:N:]url} to {@code source[:N:]url:2},
     *       set {@code source[:N:]url=&lt;canonical&gt;}, remove the bare
     *       key (its content is now split into tiles + url).</li>
     *   <li><b>Rule 2</b>: canonical URL on bare {@code source[:N]} →
     *       add {@code source[:N:]tiles=&lt;derived tile URL&gt;}, leave
     *       the bare key unchanged.</li>
     *   <li><b>Rule 3</b>: for any matched (discriminator, map_id), if
     *       {@code source[:N:]name} is empty/absent, fetch the title from
     *       {@code https://mapwarper.net/maps/&lt;id&gt;.json} and write it.</li>
     *   <li><b>Rule 4</b>: if the title lookup returns HTTP 404, append
     *       {@code "no such mapwarper map &lt;id&gt;"} to {@code fixme:tiles}.</li>
     * </ul>
     *
     * <p>Skips Rules 1/2 if the target {@code source[:N:]tiles} already
     * exists. Network calls (Rules 3/4) run lazily inside the fix lambda.
     */
    private void checkMapwarperSource(OsmPrimitive p) {
        List<MapwarperCandidate> candidates = findMapwarperCandidates(p);
        if (candidates.isEmpty()) return;

        // Build a summary of the migration in the warning message.
        List<String> mapIds = new ArrayList<>();
        for (MapwarperCandidate c : candidates) {
            if (!mapIds.contains(c.mapId)) mapIds.add(c.mapId);
        }

        TestError.Builder builder = TestError.builder(this, Severity.WARNING, CODE_MAPWARPER_SOURCE)
            .message(tr("[ohm] Source optimization - Mapwarper URL in source; autofix by splitting into source:url + source:tiles (+ source:name lookup)"),
                     marktr("Found Mapwarper URL(s) for map id(s) {0}. Split into source:url (canonical) + source:tiles (raster endpoint); fetch source:name from the Mapwarper API on apply."),
                        String.join(", ", mapIds))
            .primitives(p)
            .fix(() -> buildMapwarperFix(p, candidates));
        errors.add(builder.build());
    }

    /** One row of detected Mapwarper migration work. */
    private static final class MapwarperCandidate {
        enum Rule { TILE_ON_URL, TILE_ON_BARE, CANONICAL_ON_BARE }
        final Rule rule;
        final String srcKey;          // the key that holds the matched URL
        final String srcOldValue;     // the matched URL value
        final String discriminator;   // ":N" or ""
        final String mapId;
        // Rule 1B-only: a pre-existing source[:N:]url value to demote to :url:2.
        final String existingUrlValue;

        MapwarperCandidate(Rule rule, String srcKey, String srcOldValue,
                           String discriminator, String mapId, String existingUrlValue) {
            this.rule = rule;
            this.srcKey = srcKey;
            this.srcOldValue = srcOldValue;
            this.discriminator = discriminator;
            this.mapId = mapId;
            this.existingUrlValue = existingUrlValue;
        }
    }

    private static List<MapwarperCandidate> findMapwarperCandidates(OsmPrimitive p) {
        List<MapwarperCandidate> out = new ArrayList<>();
        for (String key : new ArrayList<>(p.keySet())) {
            String value = p.get(key);
            if (value == null) continue;

            Matcher urlKeyMatch = MAPWARPER_URL_KEY.matcher(key);
            if (urlKeyMatch.matches()) {
                Matcher tile = MAPWARPER_TILE.matcher(value);
                if (tile.matches()) {
                    String disc = urlKeyMatch.group(1) == null ? "" : urlKeyMatch.group(1);
                    if (p.get("source" + disc + ":tiles") != null) continue;
                    out.add(new MapwarperCandidate(
                        MapwarperCandidate.Rule.TILE_ON_URL,
                        key, value, disc, tile.group(1), null));
                }
                continue;
            }

            if (isMapwarperBareSourceKey(key)) {
                Matcher tile = MAPWARPER_TILE.matcher(value);
                if (tile.matches()) {
                    Matcher bareMatch = MAPWARPER_BARE_KEY.matcher(key);
                    String disc = bareMatch.matches() && bareMatch.group(1) != null
                        ? bareMatch.group(1) : "";
                    if (p.get("source" + disc + ":tiles") != null) continue;
                    String existingUrl = p.get("source" + disc + ":url");
                    out.add(new MapwarperCandidate(
                        MapwarperCandidate.Rule.TILE_ON_BARE,
                        key, value, disc, tile.group(1), existingUrl));
                    continue;
                }
                Matcher canon = MAPWARPER_CANONICAL.matcher(value);
                if (canon.matches()) {
                    Matcher bareMatch = MAPWARPER_BARE_KEY.matcher(key);
                    String disc = bareMatch.matches() && bareMatch.group(1) != null
                        ? bareMatch.group(1) : "";
                    if (p.get("source" + disc + ":tiles") != null) continue;
                    out.add(new MapwarperCandidate(
                        MapwarperCandidate.Rule.CANONICAL_ON_BARE,
                        key, value, disc, canon.group(1), null));
                }
            }
        }
        return out;
    }

    private static boolean isMapwarperBareSourceKey(String key) {
        if (!MAPWARPER_BARE_KEY.matcher(key).matches()) return false;
        if (key.endsWith(":url") || key.endsWith(":tiles")
            || key.endsWith(":name") || key.endsWith(":url:2")) return false;
        return true;
    }

    /**
     * Apply Rules 1/2 immediately, then Rules 3/4 via a lazy Mapwarper API
     * call. Returns a SequenceCommand suitable for the JOSM fix lambda.
     */
    private Command buildMapwarperFix(OsmPrimitive p, List<MapwarperCandidate> candidates) {
        List<Command> cmds = new ArrayList<>();
        Set<String> seenDiscriminators = new LinkedHashSet<>();
        List<String> notFoundIds = new ArrayList<>();

        for (MapwarperCandidate c : candidates) {
            String canonical = "https://mapwarper.net/maps/" + c.mapId;
            String tilesKey  = "source" + c.discriminator + ":tiles";
            String urlKey    = "source" + c.discriminator + ":url";

            if (c.rule == MapwarperCandidate.Rule.TILE_ON_URL) {
                // Add tiles=original, rewrite url=canonical.
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), tilesKey, c.srcOldValue));
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), urlKey, canonical));
            } else if (c.rule == MapwarperCandidate.Rule.TILE_ON_BARE) {
                // Add tiles=original.
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), tilesKey, c.srcOldValue));
                // Demote pre-existing url to url:2 if different.
                if (c.existingUrlValue != null && !c.existingUrlValue.equals(canonical)) {
                    cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                        "source" + c.discriminator + ":url:2", c.existingUrlValue));
                }
                // Set url=canonical.
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), urlKey, canonical));
                // Remove the bare source key.
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), c.srcKey, null));
            } else {
                // CANONICAL_ON_BARE: add tiles, leave bare key as-is.
                String derivedTileUrl = "https://mapwarper.net/maps/tile/" + c.mapId
                    + "/{z}/{x}/{y}.png";
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), tilesKey, derivedTileUrl));
            }
        }

        // Rules 3/4: per unique discriminator, fetch title if name is empty.
        for (MapwarperCandidate c : candidates) {
            if (!seenDiscriminators.add(c.discriminator)) continue;
            String nameKey = "source" + c.discriminator + ":name";
            String existingName = p.get(nameKey);
            if (existingName != null && !existingName.trim().isEmpty()) continue;
            MapwarperLookup look = lookupMapwarperTitle(c.mapId);
            if (look.title != null) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), nameKey, look.title));
            } else if (look.notFound) {
                notFoundIds.add(c.mapId);
            }
        }
        if (!notFoundIds.isEmpty()) {
            String existingFixme = p.get("fixme:tiles");
            StringBuilder sb = new StringBuilder(existingFixme == null ? "" : existingFixme);
            for (String id : notFoundIds) {
                String entry = "no such mapwarper map " + id;
                if (sb.length() > 0 && sb.indexOf(entry) >= 0) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(entry);
            }
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "fixme:tiles", sb.toString()));
        }

        return new SequenceCommand(
            tr("Migrate Mapwarper source URL(s)"), cmds);
    }

    /** Title-lookup result. */
    private static final class MapwarperLookup {
        final String title;     // non-null on success
        final boolean notFound; // true for HTTP 404
        MapwarperLookup(String title, boolean notFound) {
            this.title = title;
            this.notFound = notFound;
        }
    }

    private static MapwarperLookup lookupMapwarperTitle(String mapId) {
        try {
            String url = "https://mapwarper.net/maps/" + mapId + ".json";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent",
                    "OHM_Tag_Validator (https://github.com/OpenHistoricalMap/ohm-josm-tag-validator)")
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404) return new MapwarperLookup(null, true);
            if (status < 200 || status >= 300) return new MapwarperLookup(null, false);
            Matcher m = MAPWARPER_TITLE_PATTERN.matcher(response.body());
            if (!m.find()) return new MapwarperLookup(null, false);
            String title = m.group(1).trim();
            if (title.isEmpty()) return new MapwarperLookup(null, false);
            return new MapwarperLookup(title, false);
        } catch (Exception e) {
            return new MapwarperLookup(null, false);
        }
    }

    /** True if any key on the primitive starts with "source". */
    private static boolean hasAnySourceTag(OsmPrimitive p) {
        for (String key : p.keySet()) {
            if (key.equals("source") || key.startsWith("source:")) {
                return true;
            }
        }
        return false;
    }

    /** Return the first name-family key found, for use in warning messages. */
    private static String firstNameFamilyKeyFound(OsmPrimitive p) {
        for (String key : p.keySet()) {
            if (NAME_FAMILY_KEY.matcher(key).matches()) return key;
        }
        return "name:*"; // Shouldn't be reached — caller checked hasAnyNameFamily.
    }

    /** Matches a parenthesised group capturing its contents. */
    private static final Pattern PARENS_GROUP =
        Pattern.compile("\\(([^)]*)\\)");

    /**
     * Year-like pattern: 3 or 4 consecutive digits as a word. Catches
     * {@code 1880}, {@code 500} (BCE), {@code 2024-03} (the 2024 part),
     * range halves like {@code 1880-1922}.
     */
    private static final Pattern YEAR_LIKE =
        Pattern.compile("\\b\\d{3,4}\\b");

    /**
     * Date or date-range shape strict enough to autofix-strip from a name.
     * Matched (with {@link Matcher#matches}) against the trimmed inner of a
     * parens group OR against a candidate inline span. Accepts a single
     * year, year-month, full date, open-ended-left / right, two-bound year
     * range, two-bound year-month range, and the common prefix forms
     * (qualifier, {@code c.} / {@code ca.} / {@code circa}, {@code before},
     * {@code after}). Case-insensitive on the prefix keywords.
     */
    private static final Pattern CLEAN_DATE_SHAPE = Pattern.compile(
        "(?i)^\\s*"
        + "(?:c\\.?\\s+|ca\\.?\\s+|circa\\s+|before\\s+|after\\s+|[~?%])?"
        + "(?:"
        // Two-bound ranges with all combinations of precision — longest first.
        +     "\\d{4}-\\d\\d-\\d\\d-\\d{4}-\\d\\d-\\d\\d|"   // YMD-YMD
        +     "\\d{4}-\\d\\d-\\d\\d-\\d{4}-\\d\\d|"           // YMD-YM
        +     "\\d{4}-\\d\\d-\\d{4}-\\d\\d-\\d\\d|"           // YM-YMD
        +     "\\d{4}-\\d\\d-\\d\\d-\\d{4}|"                  // YMD-Y
        +     "\\d{4}-\\d{4}-\\d\\d-\\d\\d|"                  // Y-YMD
        +     "\\d{4}-\\d\\d-\\d{4}-\\d\\d|"                  // YM-YM
        +     "\\d{4}-\\d\\d-\\d{4}|"                         // YM-Y
        +     "\\d{4}-\\d{4}-\\d\\d|"                         // Y-YM
        +     "\\d{4}-\\d{4}|"                                // Y-Y
        // 2-digit tail year (only when > 12 so it isn't a valid month).
        +     "\\d{4}-(?:1[3-9]|[2-9]\\d)|"                   // Y-NN (NN > 12)
        // Single dates.
        +     "\\d{4}-\\d\\d-\\d\\d|"           // YMD
        +     "\\d{4}-\\d\\d|"                  // YM
        +     "\\d{4}-|"                        // Y-  (open-ended right)
        +     "-\\d{4}|"                        // -Y  (open-ended left)
        +     "\\d{4}"                          // Y
        + ")\\s*$"
    );

    /**
     * Inline (no parens) date-range patterns conservative enough to autofix-strip
     * from a name: a two-bound range only. Single-year inline (e.g. "Building
     * 1950") is deliberately NOT matched here — too easily a building number,
     * model number, etc.
     */
    private static final Pattern INLINE_DATE_RANGE = Pattern.compile(
        // Longest precision combinations first so the regex engine prefers them.
        "\\b\\d{4}-\\d\\d-\\d\\d-\\d{4}-\\d\\d-\\d\\d\\b"   // YMD-YMD
        + "|\\b\\d{4}-\\d\\d-\\d\\d-\\d{4}-\\d\\d\\b"        // YMD-YM
        + "|\\b\\d{4}-\\d\\d-\\d{4}-\\d\\d-\\d\\d\\b"        // YM-YMD
        + "|\\b\\d{4}-\\d\\d-\\d\\d-\\d{4}\\b"               // YMD-Y
        + "|\\b\\d{4}-\\d{4}-\\d\\d-\\d\\d\\b"               // Y-YMD
        + "|\\b\\d{4}-\\d\\d-\\d{4}-\\d\\d\\b"               // YM-YM
        + "|\\b\\d{4}-\\d\\d-\\d{4}\\b"                      // YM-Y
        + "|\\b\\d{4}-\\d{4}-\\d\\d\\b"                      // Y-YM
        + "|\\b\\d{4}-\\d{4}\\b"                             // Y-Y
        // 2-digit tail year deliberately omitted inline (no parens) — too
        // many false-positive risks for naked "Foo 1985-92" patterns that
        // might be model numbers, addresses, etc.
    );

    /**
     * True if {@code value} contains a parenthesised group whose contents
     * include a year-like number sequence. Used as the unfixable-fallback
     * trigger for rule 4301 — parens with year-like content but not a clean
     * date shape.
     */
    private static boolean containsDateInParens(String value) {
        Matcher m = PARENS_GROUP.matcher(value);
        while (m.find()) {
            if (YEAR_LIKE.matcher(m.group(1)).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a span {@code [start, end)} from {@code value}, collapse any
     * whitespace runs left at the join point to a single space, and trim.
     * Used by the rule 4301 autofix for stripping a parens group (with its
     * surrounding spaces) or an inline date range out of a name.
     */
    private static String removeSpanCollapseWhitespace(String value, int start, int end) {
        String joined = value.substring(0, start) + value.substring(end);
        return joined.replaceAll("\\s+", " ").trim();
    }

    /**
     * Structured form of a date expression extracted from a name. Captures
     * the bounds at year granularity (for the year-only comparison against
     * {@code start_date} / {@code end_date} tags) and the full-precision
     * strings for the autofix path that populates tags from the name when
     * tags are absent.
     */
    private static final class NameDate {
        final int startYear;        // -1 if open-left or absent
        final int endYear;          // -1 if open-right or absent
        final String startStr;      // null if no start bound
        final String endStr;        // null if no end bound
        final boolean openLeft;     // true for "before YYYY" / "-YYYY"
        final boolean openRight;    // true for "after YYYY" / "YYYY-"
        final boolean singleYear;   // true for "YYYY", "c. YYYY", "~YYYY", etc.

        NameDate(int sy, int ey, String ss, String es, boolean ol, boolean or, boolean sy2) {
            startYear = sy; endYear = ey; startStr = ss; endStr = es;
            openLeft = ol; openRight = or; singleYear = sy2;
        }
    }

    // Patterns to parse the various CLEAN_DATE_SHAPE variants into structured form.
    // Two-bound asymmetric-precision ranges (longest matches first).
    private static final Pattern PNAME_RANGE_YMD_YMD = Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d\\d)-(\\d{4})-(\\d\\d)-(\\d\\d)$");
    private static final Pattern PNAME_RANGE_YMD_YM  = Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d\\d)-(\\d{4})-(\\d\\d)$");
    private static final Pattern PNAME_RANGE_YM_YMD  = Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d{4})-(\\d\\d)-(\\d\\d)$");
    private static final Pattern PNAME_RANGE_YMD_Y   = Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d\\d)-(\\d{4})$");
    private static final Pattern PNAME_RANGE_Y_YMD   = Pattern.compile("^(\\d{4})-(\\d{4})-(\\d\\d)-(\\d\\d)$");
    private static final Pattern PNAME_RANGE_YM      = Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d{4})-(\\d\\d)$");
    private static final Pattern PNAME_RANGE_YM_Y    = Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d{4})$");
    private static final Pattern PNAME_RANGE_Y_YM    = Pattern.compile("^(\\d{4})-(\\d{4})-(\\d\\d)$");
    private static final Pattern PNAME_RANGE_Y       = Pattern.compile("^(\\d{4})-(\\d{4})$");
    // 2-digit tail year. Pattern requires the tail to be > 12 (so it isn't a valid month).
    private static final Pattern PNAME_RANGE_Y_NN    = Pattern.compile("^(\\d{4})-(1[3-9]|[2-9]\\d)$");
    // Single-date and open-ended forms.
    private static final Pattern PNAME_DATE_YMD = Pattern.compile("^(\\d{4})-(\\d\\d)-(\\d\\d)$");
    private static final Pattern PNAME_DATE_YM  = Pattern.compile("^(\\d{4})-(\\d\\d)$");
    private static final Pattern PNAME_OPEN_R   = Pattern.compile("^(\\d{4})-$");
    private static final Pattern PNAME_OPEN_L   = Pattern.compile("^-(\\d{4})$");
    private static final Pattern PNAME_SINGLE   = Pattern.compile("^(\\d{4})$");
    private static final Pattern PNAME_BEFORE   = Pattern.compile("(?i)^before\\s+(\\d{4})$");
    private static final Pattern PNAME_AFTER    = Pattern.compile("(?i)^after\\s+(\\d{4})$");
    private static final Pattern PNAME_CIRCA    = Pattern.compile("(?i)^(?:c\\.?\\s+|ca\\.?\\s+|circa\\s+|[~?%]\\s*)(\\d{4})$");

    /**
     * Parse the trimmed date text from a name (parens content or inline
     * date-range match) into a {@link NameDate} for comparison against
     * the primitive's {@code start_date} / {@code end_date} tags.
     * Returns {@code null} if the text doesn't match any known shape.
     */
    private static NameDate parseNameDate(String s) {
        s = s.trim();
        Matcher m;
        // Try longest-precision-first asymmetric ranges.
        if ((m = PNAME_RANGE_YMD_YMD.matcher(s)).matches()) {
            int sy = Integer.parseInt(m.group(1));
            int ey = Integer.parseInt(m.group(4));
            return new NameDate(sy, ey,
                m.group(1) + "-" + m.group(2) + "-" + m.group(3),
                m.group(4) + "-" + m.group(5) + "-" + m.group(6),
                false, false, false);
        }
        if ((m = PNAME_RANGE_YMD_YM.matcher(s)).matches()) {
            int sy = Integer.parseInt(m.group(1));
            int ey = Integer.parseInt(m.group(4));
            return new NameDate(sy, ey,
                m.group(1) + "-" + m.group(2) + "-" + m.group(3),
                m.group(4) + "-" + m.group(5),
                false, false, false);
        }
        if ((m = PNAME_RANGE_YM_YMD.matcher(s)).matches()) {
            int sy = Integer.parseInt(m.group(1));
            int ey = Integer.parseInt(m.group(3));
            return new NameDate(sy, ey,
                m.group(1) + "-" + m.group(2),
                m.group(3) + "-" + m.group(4) + "-" + m.group(5),
                false, false, false);
        }
        if ((m = PNAME_RANGE_YMD_Y.matcher(s)).matches()) {
            int sy = Integer.parseInt(m.group(1));
            int ey = Integer.parseInt(m.group(4));
            return new NameDate(sy, ey,
                m.group(1) + "-" + m.group(2) + "-" + m.group(3),
                m.group(4),
                false, false, false);
        }
        if ((m = PNAME_RANGE_Y_YMD.matcher(s)).matches()) {
            int sy = Integer.parseInt(m.group(1));
            int ey = Integer.parseInt(m.group(2));
            return new NameDate(sy, ey,
                m.group(1),
                m.group(2) + "-" + m.group(3) + "-" + m.group(4),
                false, false, false);
        }
        if ((m = PNAME_RANGE_YM.matcher(s)).matches()) {
            return new NameDate(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)),
                m.group(1) + "-" + m.group(2), m.group(3) + "-" + m.group(4),
                false, false, false);
        }
        if ((m = PNAME_RANGE_YM_Y.matcher(s)).matches()) {
            int sy = Integer.parseInt(m.group(1));
            int ey = Integer.parseInt(m.group(3));
            return new NameDate(sy, ey,
                m.group(1) + "-" + m.group(2),
                m.group(3),
                false, false, false);
        }
        if ((m = PNAME_RANGE_Y_YM.matcher(s)).matches()) {
            int sy = Integer.parseInt(m.group(1));
            int ey = Integer.parseInt(m.group(2));
            return new NameDate(sy, ey,
                m.group(1),
                m.group(2) + "-" + m.group(3),
                false, false, false);
        }
        if ((m = PNAME_RANGE_Y.matcher(s)).matches()) {
            return new NameDate(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                m.group(1), m.group(2),
                false, false, false);
        }
        if ((m = PNAME_RANGE_Y_NN.matcher(s)).matches()) {
            // Expand 2-digit tail using start year's century (first 2 digits).
            int sy = Integer.parseInt(m.group(1));
            String century = m.group(1).substring(0, 2);
            String expandedEnd = century + m.group(2);
            int ey = Integer.parseInt(expandedEnd);
            return new NameDate(sy, ey, m.group(1), expandedEnd, false, false, false);
        }
        if ((m = PNAME_DATE_YMD.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            return new NameDate(y, y, s, s, false, false, true);
        }
        if ((m = PNAME_DATE_YM.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            return new NameDate(y, y, s, s, false, false, true);
        }
        if ((m = PNAME_OPEN_R.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            return new NameDate(y, -1, m.group(1), null, false, true, false);
        }
        if ((m = PNAME_OPEN_L.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            return new NameDate(-1, y, null, m.group(1), true, false, false);
        }
        if ((m = PNAME_BEFORE.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            return new NameDate(-1, y, null, m.group(1), true, false, false);
        }
        if ((m = PNAME_AFTER.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            return new NameDate(y, -1, m.group(1), null, false, true, false);
        }
        if ((m = PNAME_CIRCA.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            return new NameDate(y, y, m.group(1), m.group(1), false, false, true);
        }
        if ((m = PNAME_SINGLE.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            return new NameDate(y, y, s, s, false, false, true);
        }
        return null;
    }

    /**
     * Extract the year integer at the head of a {@code *_date} value. Handles
     * plain ISO ({@code 1880}, {@code 1880-03}, {@code 1880-03-15}) and
     * astronomical-negative years ({@code -0799}, {@code -0799-12-31}).
     * Returns {@code null} for null/empty/unparseable values — used by
     * rule 4301's name-vs-tag agreement check (year-only equality).
     */
    private static final Pattern YEAR_HEAD = Pattern.compile("^(-?\\d{1,4})(?:-|$)");
    private static Integer extractTagYear(String tagValue) {
        if (tagValue == null || tagValue.isEmpty()) return null;
        Matcher m = YEAR_HEAD.matcher(tagValue);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Outcome of comparing a name's date(s) to the primitive's date tags. */
    private enum NameDateMatch {
        /** Tags fully match the name's date(s); safe to strip date from name. */
        STRIPPABLE,
        /** Two-bound range, no date tags; safe to populate tags AND strip name. */
        POPULATABLE,
        /** Mismatch / partial / single-year-without-matching-tags; no autofix. */
        UNFIXABLE,
    }

    /**
     * Compare a parsed {@link NameDate} against the primitive's
     * {@code start_date} / {@code end_date} tags at year-only equality.
     * See rule 4301 docs in MESSAGES.md for the decision table.
     */
    private static NameDateMatch checkNameDateAgreement(OsmPrimitive p, NameDate nd) {
        Integer startTagYear = extractTagYear(p.get("start_date"));
        Integer endTagYear   = extractTagYear(p.get("end_date"));
        boolean hasStart = startTagYear != null;
        boolean hasEnd   = endTagYear != null;

        if (nd.singleYear) {
            // Single year: both tags must be present and match the year.
            if (hasStart && hasEnd
                && startTagYear == nd.endYear
                && endTagYear == nd.endYear) {
                return NameDateMatch.STRIPPABLE;
            }
            return NameDateMatch.UNFIXABLE;
        }
        if (nd.openLeft) {
            // "before YYYY" / "-YYYY": end_date matches AND start_date absent.
            if (hasEnd && !hasStart && endTagYear == nd.endYear) {
                return NameDateMatch.STRIPPABLE;
            }
            return NameDateMatch.UNFIXABLE;
        }
        if (nd.openRight) {
            // "after YYYY" / "YYYY-": start_date matches AND end_date absent.
            if (hasStart && !hasEnd && startTagYear == nd.startYear) {
                return NameDateMatch.STRIPPABLE;
            }
            return NameDateMatch.UNFIXABLE;
        }
        // Two-bound range.
        if (hasStart && hasEnd
            && startTagYear == nd.startYear
            && endTagYear == nd.endYear) {
            return NameDateMatch.STRIPPABLE;
        }
        if (!hasStart && !hasEnd) {
            return NameDateMatch.POPULATABLE;
        }
        return NameDateMatch.UNFIXABLE;
    }

    /**
     * Rule 4327: name-family value has leading/trailing whitespace OR
     * contains an embedded control character (vertical tab, form feed,
     * NUL, etc.).
     *
     * <p>Three sub-paths under the same code, in priority order:
     *
     * <ol>
     *   <li><b>Embedded control character</b> (anywhere in the value,
     *       excluding the standard whitespace characters tab/LF/CR/space
     *       which are addressed by the whitespace path). Fires unfixable
     *       — auto-replacing control chars is too risky; the editor must
     *       decide whether each one is a typo, accidental paste artifact,
     *       or intentional separator.</li>
     *   <li><b>All-whitespace value</b>: stripping would clear the name.
     *       Fires unfixable; the editor decides whether to restore content
     *       or remove the tag.</li>
     *   <li><b>Leading or trailing whitespace</b>: fires fixable, autofix
     *       calls {@link String#strip()} on the value.</li>
     * </ol>
     */
    private void checkNameWhitespace(OsmPrimitive p, String key, String value) {
        // Path 1: embedded non-whitespace control character. ISO controls
        // include U+0000-U+001F and U+007F-U+009F. We exclude the standard
        // whitespace controls (tab U+0009, LF U+000A, CR U+000D) since the
        // whitespace path below handles them via String.strip().
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_WHITESPACE)
                    .message(tr("[ohm] Name warning - embedded control character; unfixable, please review"),
                             marktr("{0}={1}: contains a non-printable control character "
                                + "(U+{2}). Likely a paste artifact; remove it manually."),
                                key, value, String.format("%04X", (int) c))
                    .primitives(p)
                    .build());
                return;
            }
        }

        // Cheap rejection: no boundary whitespace → nothing left to do.
        if (!Character.isWhitespace(value.charAt(0))
            && !Character.isWhitespace(value.charAt(value.length() - 1))) {
            return;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            // Path 2: all-whitespace name. Unfixable — editor must decide.
            errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_WHITESPACE)
                .message(tr("[ohm] Name warning - whitespace-only name; unfixable, please review"),
                         marktr("{0}=\"{1}\": value is only whitespace. Restore content or "
                            + "remove the tag."),
                            key, value)
                .primitives(p)
                .build());
            return;
        }
        // Path 3: leading/trailing whitespace. Fixable.
        Command fix = new ChangePropertyCommand(Arrays.asList(p), key, trimmed);
        errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_WHITESPACE)
            .message(tr("[ohm] Name warning - leading or trailing whitespace; autofix by trimming"),
                     marktr("{0}=\"{1}\" has leading or trailing whitespace. Trim to \"{2}\"?"),
                        key, value, trimmed)
            .primitives(p)
            .fix(() -> fix)
            .build());
    }

    /**
     * Rule 4301: name-family value contains date-like content.
     *
     * <p>Three paths, checked in order; at most one warning fires per value:
     *
     * <ol>
     *   <li><b>Clean date in parens</b> (e.g. {@code (1950-)}, {@code (1880-1922)},
     *       {@code (c. 1900)}) — autofix-fixable, strip the parens span and
     *       collapse whitespace.</li>
     *   <li><b>Clean inline date range</b> ({@code YYYY-YYYY} or
     *       {@code YYYY-MM-YYYY-MM}) — autofix-fixable, strip the matched
     *       span and collapse whitespace. Single-year inline (e.g.
     *       {@code Building 1950}) is deliberately not detected here.</li>
     *   <li><b>Parens with year-like content but not a clean shape</b> (e.g.
     *       {@code (Springfield 1950)}) — unfixable; the parens have date-ish
     *       content but not a clean enough shape to autofix.</li>
     * </ol>
     */
    private void checkNameForDateContent(OsmPrimitive p, String key, String value) {
        // Path 1: clean date in parens.
        Matcher pm = PARENS_GROUP.matcher(value);
        while (pm.find()) {
            String inside = pm.group(1);
            if (CLEAN_DATE_SHAPE.matcher(inside).matches()) {
                emitNameDateOutcome(p, key, value, pm.start(), pm.end(), inside);
                return;
            }
        }

        // Path 2: clean inline date range.
        Matcher im = INLINE_DATE_RANGE.matcher(value);
        if (im.find()) {
            String inlineDate = value.substring(im.start(), im.end());
            emitNameDateOutcome(p, key, value, im.start(), im.end(), inlineDate);
            return;
        }

        // Path 3: parens with year-like content but not a clean shape — unfixable.
        if (containsDateInParens(value)) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_PARENS)
                .message(tr("[ohm] Name warning - parentheses in name and no clear dates to remove; unfixable, please review"),
                         marktr("{0}={1}: dates in parentheses are discouraged in names; "
                            + "move the date to start_date / end_date instead."),
                            key, value)
                .primitives(p)
                .build());
        }
    }

    /**
     * Given a span {@code [spanStart, spanEnd)} in {@code value} that has been
     * identified as a date expression inside a name, emit the appropriate
     * rule-4301 finding based on whether the name's date(s) agree with the
     * primitive's {@code start_date} / {@code end_date} tags.
     *
     * <ul>
     *   <li><b>STRIPPABLE</b>: tags match — autofix strips the date from the name.</li>
     *   <li><b>POPULATABLE</b>: two-bound range, no date tags — autofix strips the
     *       date AND writes {@code start_date} / {@code end_date} from the name.</li>
     *   <li><b>UNFIXABLE</b>: mismatch / partial / single-year-without-tags — no
     *       autofix, user must reconcile manually.</li>
     * </ul>
     */
    private void emitNameDateOutcome(OsmPrimitive p, String key, String value,
                                     int spanStart, int spanEnd, String dateText) {
        NameDate nd = parseNameDate(dateText);
        String fixed = removeSpanCollapseWhitespace(value, spanStart, spanEnd);
        if (nd == null) {
            // Defensive: CLEAN_DATE_SHAPE accepts a shape parseNameDate doesn't.
            // Fall back to the prior behavior — fixable strip.
            Command fix = new ChangePropertyCommand(Arrays.asList(p), key, fixed);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_PARENS)
                .message(tr("[ohm] Name warning - dates in name; autofix by stripping the date range"),
                         marktr("{0}={1}: dates in names are discouraged; move to "
                            + "start_date / end_date. Strip the date range to leave "
                            + "{0}={2}?"),
                            key, value, fixed)
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        NameDateMatch result = checkNameDateAgreement(p, nd);
        if (result == NameDateMatch.STRIPPABLE) {
            Command fix = new ChangePropertyCommand(Arrays.asList(p), key, fixed);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_PARENS)
                .message(tr("[ohm] Name warning - dates in name; autofix by stripping the date range"),
                         marktr("{0}={1}: dates in names are discouraged; move to "
                            + "start_date / end_date. Strip the date range to leave "
                            + "{0}={2}?"),
                            key, value, fixed)
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }
        if (result == NameDateMatch.POPULATABLE) {
            // Two-bound range with no date tags: strip name AND populate tags.
            List<Command> cmds = new ArrayList<>();
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), key, fixed));
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "start_date", nd.startStr));
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "end_date", nd.endStr));
            Command fix = new SequenceCommand(
                tr("Strip date from name and populate start_date / end_date"), cmds);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_PARENS)
                .message(tr("[ohm] Name warning - dates in name; autofix by populating start_date / end_date and stripping the date range"),
                         marktr("{0}={1}: dates in names are discouraged; move to "
                            + "start_date / end_date. Set start_date={2}, end_date={3}, "
                            + "and strip the date range to leave {0}={4}?"),
                            key, value, nd.startStr, nd.endStr, fixed)
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }
        // UNFIXABLE — disagreement or partial coverage; no autofix.
        errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_PARENS)
            .message(tr("[ohm] Name warning - dates in name disagree with start_date / end_date; unfixable, please review"),
                     marktr("{0}={1}: dates in names are discouraged, but the name's "
                        + "date(s) do not agree with the primitive's start_date / "
                        + "end_date tags. Reconcile manually before stripping."),
                        key, value)
            .primitives(p)
            .build());
    }

    /** True if the primitive is a {@link Relation} with {@code type=route}. */
    private static boolean isRouteRelation(OsmPrimitive p) {
        return p instanceof Relation && "route".equals(p.get("type"));
    }

    /**
     * Check a {@code source} or {@code source:N} tag. {@code numIdx} is the
     * captured numeric sub-index, or null for the plain {@code source} key.
     *
     * <p>If the value contains semicolons, hand off to
     * {@link #checkSemicolonSeparatedSource} for the split logic. Otherwise
     * the single-value checks: Wikipedia/Wikidata literals (4304/4305) and
     * URL missing scheme (4307). Plain text in {@code source} is valid under
     * the v0.5 contract — no rule fires for that case.
     */
    private void checkSourceTag(OsmPrimitive p, String key, String value, String numIdx) {
        checkSourceTag(p, key, value, numIdx, "");
    }

    /**
     * Source-content checks, parameterized by an {@code attrPrefix} so the
     * same logic applies to plain {@code source} (prefix = "") and any
     * attribute-scoped source slot (prefix = {@code "<attr>:"}, e.g.
     * {@code "start_date:"}). Wikipedia/Wikidata literal handling is
     * skipped here for attribute-source (those values are routed through
     * {@link #checkAttrSourceTag} which applies the attribution-completeness
     * rules 4308/4309).
     */
    private void checkSourceTag(OsmPrimitive p, String key, String value, String numIdx, String attrPrefix) {
        if (value == null || value.isEmpty()) return;

        // Semicolon-separated — might be multiple sources the user bundled
        // together. Only fires for the plain source key (not source:N),
        // since source:1, source:2 etc. are already the enumeration target.
        if (numIdx == null && value.contains(";")) {
            checkSemicolonSeparatedSource(p, key, value, attrPrefix);
            return;
        }

        // Wikipedia / Wikidata as source literals: only emit the
        // geometry-claim warnings (4304/4305) for plain source. Attribute-
        // source handles these through 4308/4309 (checkAttrSourceTag), which
        // is about attribution completeness rather than source quality.
        if (attrPrefix.isEmpty()) {
            if ("wikipedia".equalsIgnoreCase(value)) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_IS_WIKIPEDIA)
                    .message(tr("[ohm] Suspicious source - source=wikipedia; unfixable, please review"),
                             marktr("{0}={1}: Wikipedia is not a reasonable source for "
                                + "geometry claims. Please link to an actual map, image, "
                                + "or other primary source."),
                                key, value)
                    .primitives(p)
                    .build());
                return;
            }
            if ("wikidata".equalsIgnoreCase(value)) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_IS_WIKIDATA)
                    .message(tr("[ohm] Suspicious source - source=wikidata; unfixable, please review"),
                             marktr("{0}={1}: Wikidata is not a reasonable source for "
                                + "geometry claims. Please link to an actual map, image, "
                                + "or other primary source."),
                                key, value)
                    .primitives(p)
                    .build());
                return;
            }
        }

        // URL-shaped but missing scheme? Offer to prepend https://. Applies
        // to plain source and attribute-source equally.
        if (!URL_WITH_SCHEME.matcher(value).matches()
            && URL_MISSING_SCHEME.matcher(value).matches()) {
            emitMissingSchemeFix(p, key, value);
        }
        // Otherwise — URL or plain text — both valid in the source slot.
    }

    /**
     * Emit the rule 4307 finding (URL missing scheme) with autofix that
     * prepends {@code https://}. Used by both {@link #checkSourceTag}
     * (for {@code source} / {@code source:N}) and
     * {@link #checkSourceUrlPair} (for {@code source:url} / {@code source:N:url}).
     */
    private void emitMissingSchemeFix(OsmPrimitive p, String key, String value) {
        String fixed = "https://" + value;
        Command fix = new ChangePropertyCommand(Arrays.asList(p), key, fixed);
        errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_MISSING_SCHEME)
            .message(tr("[ohm] Source optimization - URL missing ''http[s]://''; autofix by prepending https://"),
                     marktr("{0}={1} looks like a URL missing the scheme. Prepend ''https://''?"),
                        key, value)
            .primitives(p)
            .fix(() -> fix)
            .build());
    }

    /**
     * Compute the next available {@code source:N} index on a primitive.
     * Scans all keys matching {@code ^source:(\d+)$}, returns
     * {@code max + 1}, or {@code 1} if no such keys exist.
     *
     * <p>Used by autofixes that need to enumerate values into numbered
     * {@code source} slots: rule 4312 case 2 (different URLs in
     * {@code source} and {@code source:url}), rule 4315 (multi-URL split),
     * rule 4316 (multi-text split). Always placing past the existing
     * maximum guarantees no clobbering and tolerates gaps.
     */
    private static int nextSourceIndex(OsmPrimitive p) {
        return nextSourceIndex(p, "");
    }

    /**
     * Compute the next available {@code <attrPrefix>source:N} index, where
     * {@code attrPrefix} is either empty (plain {@code source:N}) or a
     * trailing-colon form like {@code "start_date:"} (giving
     * {@code start_date:source:N}). Never overwrites; always returns
     * {@code max(N) + 1} or {@code 1} when none exist.
     */
    private static int nextSourceIndex(OsmPrimitive p, String attrPrefix) {
        // Build a pattern that matches the prefixed enumeration: e.g.
        //   ^source:(\d+)$              (plain)
        //   ^start_date:source:(\d+)$   (attribute-scoped)
        // Pattern.quote escapes any regex metachars in the prefix.
        Pattern pat = Pattern.compile("^" + Pattern.quote(attrPrefix) + "source:(\\d+)$");
        return p.keySet().stream()
            .map(pat::matcher)
            .filter(Matcher::matches)
            .map(m -> m.group(1))
            .filter(g -> g != null)
            .mapToInt(Integer::parseInt)
            .max()
            .orElse(0) + 1;
    }

    /**
     * Handle a {@code source} value containing semicolons.
     *
     * <p>Cases, based on classification of each semicolon-separated item:
     * <ul>
     *   <li><b>2 items, one URL + one text.</b> Rule 4314: write
     *       {@code source=text} and {@code source:url=URL}. If
     *       {@code source:url} already holds a different value, emit
     *       unfixable variant under the same code.</li>
     *   <li><b>2+ items, all URLs.</b> Rule 4315: enumerate into
     *       {@code source}, {@code source:N+1}, ... using the shared
     *       enumeration convention (always appendable past max-existing
     *       index).</li>
     *   <li><b>2+ items, all text.</b> Rule 4316: same enumeration
     *       convention into {@code source} slots (text now valid in
     *       {@code source}).</li>
     *   <li><b>3+ items, mixed URL and text.</b> Rule 4317: warn, no
     *       autofix.</li>
     * </ul>
     *
     * <p>Semicolons inside a URL (e.g. {@code jsessionid=XXX}) will be
     * incorrectly split here — we accept that false-positive risk per spec.
     */
    private void checkSemicolonSeparatedSource(OsmPrimitive p, String key, String value, String attrPrefix) {
        String[] parts = value.split(";");
        // Trim and filter empties.
        List<String> items = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) items.add(trimmed);
        }
        if (items.size() < 2) return; // Nothing to split.

        int urlCount = 0;
        int textCount = 0;
        for (String item : items) {
            if (URL_WITH_SCHEME.matcher(item).matches()) {
                urlCount++;
            } else {
                textCount++;
            }
        }

        String urlSlotKey = attrPrefix + "source:url";

        // Case: exactly 2 items, one URL + one text. Under v0.5 contract:
        // <attr>source=text, <attr>source:url=URL.
        if (items.size() == 2 && urlCount == 1 && textCount == 1) {
            String urlPart = URL_WITH_SCHEME.matcher(items.get(0)).matches()
                ? items.get(0) : items.get(1);
            String textPart = urlPart.equals(items.get(0)) ? items.get(1) : items.get(0);

            String existingUrl = p.get(urlSlotKey);
            boolean urlSlotEmpty = existingUrl == null || existingUrl.isEmpty();
            boolean urlSlotMatches = urlPart.equals(existingUrl);

            if (!urlSlotEmpty && !urlSlotMatches) {
                // <attr>source:url already holds a different URL — autofix
                // would clobber it.
                errors.add(TestError.builder(this, Severity.WARNING,
                                             CODE_SOURCE_SEMICOLON_URL_TEXT)
                    .message(tr("[ohm] Source mismatch - source contains 1 URL & 1 text string but source:url already holds a different value; unfixable, please review"),
                             marktr("{0}={1}: cannot split into {5}={2} and {6}={3} "
                                + "because {6} already holds {4}. Manual review needed."),
                                key, value, textPart, urlPart, existingUrl,
                                attrPrefix + "source", urlSlotKey)
                    .primitives(p)
                    .build());
                return;
            }

            List<Command> cmds = new ArrayList<>();
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), key, textPart));
            if (urlSlotEmpty) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), urlSlotKey, urlPart));
            }
            Command fix = new SequenceCommand(
                tr("Split {0} into text and {1}", attrPrefix + "source", urlSlotKey), cmds);
            errors.add(TestError.builder(this, Severity.WARNING,
                                         CODE_SOURCE_SEMICOLON_URL_TEXT)
                .message(tr("[ohm] Source optimization - source contains 1 URL & 1 text string; autofix by splitting into source & source:url"),
                         marktr("{0}={1}: move text to {2} and URL to {3}?"),
                            key, value, attrPrefix + "source", urlSlotKey)
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // Case: all URLs (2+). Enumerate per the shared convention.
        if (urlCount == items.size()) {
            emitMultiUrlSplit(p, key, value, items, attrPrefix);
            return;
        }

        // Case: all text (2+). Warn only — semicolons in text are
        // ambiguous: they may be legitimate punctuation, not a multi-source
        // delimiter. Manual review required.
        if (textCount == items.size()) {
            errors.add(TestError.builder(this, Severity.WARNING,
                                         CODE_SOURCE_SEMICOLON_MULTI_TEXT)
                .message(tr("[ohm] Source mismatch - source contains multiple text strings separated by semicolons; unfixable, please review"),
                         marktr("{0}={1}: semicolons in text are ambiguous. "
                            + "If these are separate sources, split manually into "
                            + "{2}, {2}:1, {2}:2, …; if the semicolons are "
                            + "punctuation in a single citation, leave alone."),
                            key, value, attrPrefix + "source")
                .primitives(p)
                .build());
            return;
        }

        // Case: 3+ mixed. Warn, no fix.
        errors.add(TestError.builder(this, Severity.WARNING,
                                     CODE_SOURCE_SEMICOLON_MIXED)
            .message(tr("[ohm] Source mismatch - source contains multiple values of different types; unfixable, please review"),
                     marktr("{0}={1}: 3 or more items mixing URLs and text. "
                      + "Manual review needed — split into {2}, {2}:N, "
                      + "{2}:url, {2}:N:url as appropriate."),
                        key, value, attrPrefix + "source")
            .primitives(p)
            .build());
    }

    /**
     * Autofix for rule 4315 (multi-URL split). If the primitive has no
     * enumerated {@code source:N} keys, the bare {@code source} key is
     * overwritten with the first item and the rest go into {@code source:1},
     * {@code source:2}, …
     *
     * <p>Otherwise (one or more {@code source:N} already exist), the
     * combined {@code source} value is cleared and all items are appended
     * starting at {@code source:(M+1)}, where M is the highest existing
     * numeric index. This matches the shared enumeration convention.
     */
    private void emitMultiUrlSplit(OsmPrimitive p, String key, String value, List<String> items, String attrPrefix) {
        String sourceBase = attrPrefix + "source";
        Pattern enumeratedPat = Pattern.compile("^" + Pattern.quote(sourceBase) + ":(\\d+)$");
        boolean hasExistingEnumerated = p.keySet().stream()
            .anyMatch(k -> enumeratedPat.matcher(k).matches());

        List<Command> cmds = new ArrayList<>();
        if (!hasExistingEnumerated) {
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), sourceBase, items.get(0)));
            for (int i = 1; i < items.size(); i++) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                   sourceBase + ":" + i, items.get(i)));
            }
        } else {
            int start = nextSourceIndex(p, attrPrefix);
            // Clear the combined-value source tag — items are being relocated.
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), key, null));
            for (int i = 0; i < items.size(); i++) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                   sourceBase + ":" + (start + i), items.get(i)));
            }
        }

        Command fix = new SequenceCommand(tr("Enumerate {0} URLs", sourceBase), cmds);
        errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_SEMICOLON_MULTI_URL)
            .message(tr("[ohm] Source optimization - source contains multiple URLs; autofix by enumerating source:# keys"),
                     marktr("{0}={1}: enumerate into {2}, {2}:1, {2}:2, ...?"),
                        key, value, sourceBase)
            .primitives(p)
            .fix(() -> fix)
            .build());
    }

    /**
     * Per-pair check for every {@code source[:N]?:url} key on a primitive.
     * Under the v0.5 contract:
     * <ul>
     *   <li>Scheme-missing URL in {@code :url} fires rule 4307 with the
     *       {@code https://} prepend autofix.</li>
     *   <li>Non-URL text in {@code :url} fires rule 4325, with a fallback
     *       chain for the autofix target ({@code source} → {@code source:name}
     *       → {@code source:note}). All three full → unfixable.</li>
     *   <li>Both companion and {@code :url} hold valid URLs but differ
     *       fires rule 4312 with autofix moving {@code :url} to the next
     *       numbered {@code source:N} slot.</li>
     * </ul>
     * (Codes 4311 and 4313 are retired; identical URLs and text-companion
     * + URL-in-:url are valid layouts under the new contract.)
     */
    /**
     * Matches any {@code <prefix>source[:N]?:url} key, capturing (1) the
     * full prefix up to and including the {@code source} segment, (2) the
     * optional numeric sub-index. Covers both plain {@code source:url} /
     * {@code source:N:url} and attribute-scoped variants like
     * {@code start_date:source:url} / {@code start_date:source:N:url}.
     */
    private static final Pattern ANY_SOURCE_URL_KEY =
        Pattern.compile("^((?:[A-Za-z][A-Za-z0-9_-]*:)*source)(?::(\\d+))?:url$");

    /** Same shape as {@link #ANY_SOURCE_URL_KEY} but for {@code :name}. */
    private static final Pattern ANY_SOURCE_NAME_KEY =
        Pattern.compile("^((?:[A-Za-z][A-Za-z0-9_-]*:)*source)(?::(\\d+))?:name$");

    private void checkSourceUrlConsolidation(OsmPrimitive p) {
        // Snapshot all url-shaped keys before invoking per-pair checks. We
        // only read keys (not modifying the primitive) so iteration is safe;
        // a snapshot avoids any future surprise. Covers plain and
        // attribute-scoped source:url keys uniformly.
        List<String[]> pairs = new ArrayList<>();
        for (String key : p.keySet()) {
            Matcher m = ANY_SOURCE_URL_KEY.matcher(key);
            if (m.matches()) {
                String sourceBase = m.group(1);   // e.g. "source" or "start_date:source"
                String numIdx = m.group(2);
                String companionKey = (numIdx == null) ? sourceBase : sourceBase + ":" + numIdx;
                // attrPrefix = everything before the "source" segment, with
                // its trailing colon kept. Empty for plain source.
                String attrPrefix = sourceBase.equals("source") ? "" : sourceBase.substring(0, sourceBase.length() - "source".length());
                pairs.add(new String[] { key, companionKey, attrPrefix });
            }
        }
        for (String[] pair : pairs) {
            checkSourceUrlPair(p, pair[0], pair[1], pair[2]);
        }
    }

    /**
     * Per-pair logic for one {@code source[:N]?:url} key and its companion
     * {@code source[:N]?}. See {@link #checkSourceUrlConsolidation} for the
     * rules.
     */
    private void checkSourceUrlPair(OsmPrimitive p, String urlKey, String companionKey, String attrPrefix) {
        String source = p.get(companionKey);
        String sourceUrl = p.get(urlKey);
        if (sourceUrl == null || sourceUrl.isEmpty()) return;

        // 4307: URL missing scheme in :url. Autofix prepends https://.
        if (!URL_WITH_SCHEME.matcher(sourceUrl).matches()
            && URL_MISSING_SCHEME.matcher(sourceUrl).matches()) {
            emitMissingSchemeFix(p, urlKey, sourceUrl);
            return;
        }

        // 4325: non-URL text in :url. Fallback chain: <attr>source /
        // <attr>source:name / <attr>source:note.
        if (!URL_WITH_SCHEME.matcher(sourceUrl).matches()) {
            emitTextInUrlFix(p, urlKey, sourceUrl, companionKey);
            return;
        }

        // sourceUrl is now a valid URL with scheme.
        // If companion has semicolons, defer to the semicolon handler.
        if (source != null && source.contains(";")) return;

        // 4312: both slots hold URLs but differ — move :url to next
        // <attr>source:N. (Identical URLs and text-companion + URL-in-:url
        // are both valid under the v0.5 contract; no warning.)
        if (source != null && !source.isEmpty()
            && !source.equals(sourceUrl)
            && URL_WITH_SCHEME.matcher(source).matches()) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_URL_CONFLICTS)
                .message(tr("[ohm] Source mismatch - source & source:url are different URLs; autofix by moving source:url to source:#"),
                         marktr("{0}={1} and {2}={3} are different URLs. Move {2} to the next numbered source key?"),
                            companionKey, source, urlKey, sourceUrl)
                .primitives(p)
                .fix(() -> {
                    String newKey = attrPrefix + "source:" + nextSourceIndex(p, attrPrefix);
                    List<Command> moveCmds = new ArrayList<>();
                    moveCmds.add(new ChangePropertyCommand(Arrays.asList(p), newKey, sourceUrl));
                    moveCmds.add(new ChangePropertyCommand(Arrays.asList(p), urlKey, null));
                    return new SequenceCommand(tr("Move {0} to {1}", urlKey, newKey), moveCmds);
                })
                .build());
        }
    }

    /**
     * Emit rule 4325 (text value in {@code source:url}) with a tiered
     * fallback for the autofix target: companion {@code source[:N]?} -&gt;
     * companion {@code source[:N]?:name} -&gt; companion
     * {@code source[:N]?:note}. If all three are full, no autofix.
     */
    private void emitTextInUrlFix(OsmPrimitive p, String urlKey, String value, String companionKey) {
        String nameKey = companionKey + ":name";
        String noteKey = companionKey + ":note";

        String target = null;
        String src = p.get(companionKey);
        if (src == null || src.isEmpty()) {
            target = companionKey;
        } else if (p.get(nameKey) == null || p.get(nameKey).isEmpty()) {
            target = nameKey;
        } else if (p.get(noteKey) == null || p.get(noteKey).isEmpty()) {
            target = noteKey;
        }

        if (target == null) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_URL_HAS_TEXT)
                .message(tr("[ohm] Source mismatch - text value in source:url & all sibling slots full; unfixable, please review"),
                         marktr("{0}={1} is not a URL but {2}, {3}, and {4} all hold values. "
                            + "Manual review needed."),
                            urlKey, value, companionKey, nameKey, noteKey)
                .primitives(p)
                .build());
            return;
        }

        final String moveTo = target;
        List<Command> cmds = new ArrayList<>();
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), moveTo, value));
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), urlKey, null));
        Command fix = new SequenceCommand(tr("Move {0} to {1}", urlKey, moveTo), cmds);
        errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_URL_HAS_TEXT)
            .message(tr("[ohm] Source mismatch - text value in source:url; autofix by moving to source / source:name / source:note"),
                     marktr("{0}={1} is not a URL. Move to {2}?"),
                        urlKey, value, moveTo)
            .primitives(p)
            .fix(() -> fix)
            .build());
    }

    /**
     * Iterate {@code source[:N]?:name} keys and flag any whose value is
     * a URL (rule 4324). Autofix moves the URL to the matching
     * {@code source[:N]?:url} slot when that slot is empty; otherwise
     * unfixable.
     */
    private void checkSourceNameContents(OsmPrimitive p) {
        List<String[]> hits = new ArrayList<>();
        for (String key : p.keySet()) {
            Matcher m = ANY_SOURCE_NAME_KEY.matcher(key);
            if (!m.matches()) continue;
            String value = p.get(key);
            if (value == null || value.isEmpty()) continue;
            if (!URL_WITH_SCHEME.matcher(value).matches()) continue;
            String sourceBase = m.group(1);   // "source" or "<attr>:source"
            String numIdx = m.group(2);
            String urlKey = (numIdx == null) ? sourceBase + ":url" : sourceBase + ":" + numIdx + ":url";
            hits.add(new String[] { key, value, urlKey });
        }
        for (String[] hit : hits) {
            String nameKey = hit[0];
            String value = hit[1];
            String urlKey = hit[2];
            String existingUrl = p.get(urlKey);

            if (existingUrl == null || existingUrl.isEmpty()) {
                List<Command> cmds = new ArrayList<>();
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), urlKey, value));
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), nameKey, null));
                Command fix = new SequenceCommand(tr("Move {0} to {1}", nameKey, urlKey), cmds);
                errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_NAME_HAS_URL)
                    .message(tr("[ohm] Source mismatch - URL value in source:name; autofix by moving to source:url"),
                             marktr("{0}={1} is a URL. Move to {2}?"), nameKey, value, urlKey)
                    .primitives(p)
                    .fix(() -> fix)
                    .build());
            } else if (!existingUrl.equals(value)) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_NAME_HAS_URL)
                    .message(tr("[ohm] Source mismatch - URL in source:name & source:url already set; unfixable, please review"),
                             marktr("{0}={1} is a URL but {2}={3} already holds a different URL. "
                                + "Manual review needed."),
                                nameKey, value, urlKey, existingUrl)
                    .primitives(p)
                    .build());
            }
            // existingUrl equals value -> both slots hold same URL,
            // harmless redundancy under the v0.5 contract. Don't fire.
        }
    }

    /**
     * Check an attribute-scoped source key like {@code start_date:source}.
     *
     * <p>Wikipedia / Wikidata literal values trigger attribution-completeness
     * warnings (4308 / 4309); any other value is handed off to the same
     * source-content pipeline used for plain {@code source} keys (URL-shape,
     * semicolon split, slot-typing — see {@code checkSourceTag}).
     *
     * <p>Suppression model (latest convention):
     * <ul>
     *   <li>{@code wikidata=*} present → silent for both 4308 and 4309
     *       (a QID is canonical and resolves to a Wikipedia article via
     *       sitelinks).</li>
     *   <li>{@code wikipedia=*} present (no {@code wikidata=*}) → silent for
     *       4308 (attribution target exists); 4309 fires fixable with an
     *       autofix that queries the Wikidata API to look up the QID.</li>
     *   <li>multiple {@code wikipedia*} tags, no {@code wikidata=*} → 4309
     *       fires unfixable (ambiguous which article is canonical).</li>
     *   <li>neither tag present → 4308 / 4309 fires unfixable.</li>
     * </ul>
     */
    private void checkAttrSourceTag(OsmPrimitive p, String key, String value, String attrPrefix) {
        if (value == null || value.isEmpty()) return;

        if ("wikipedia".equalsIgnoreCase(value)) {
            // Suppression: any wikipedia* or wikidata=* tag is enough.
            // Empty values don't count as "set" — use getNonEmpty.
            if (getNonEmpty(p, "wikidata") != null) return;
            if (hasAnyKeyStartingWith(p, "wikipedia")) return;
            errors.add(TestError.builder(this, Severity.WARNING, CODE_ATTR_SOURCE_WIKIPEDIA)
                .message(tr("[ohm] Missing tag - wikipedia, referenced in source keys; unfixable, please review & add tag"),
                         marktr("{0}={1}: please add an appropriate ''wikipedia'' or "
                            + "''wikidata'' tag."),
                            key, value)
                .primitives(p)
                .build());
            return;
        }
        if ("wikidata".equalsIgnoreCase(value)) {
            // Suppression: wikidata=* is the canonical attribution; silent.
            // Empty values don't count — use getNonEmpty.
            if (getNonEmpty(p, "wikidata") != null) return;

            // Try to autofix from wikipedia. Single canonical wikipedia=*
            // is fixable (lookup-via-API); multiple wikipedia* tags are
            // ambiguous (unfixable).
            String canonical = canonicalWikipediaForLookup(p);
            int wikipediaCount = countWikipediaKeys(p);
            if (canonical != null && wikipediaCount == 1) {
                TestError.Builder builder = TestError.builder(this, Severity.WARNING, CODE_ATTR_SOURCE_WIKIDATA)
                    .message(tr("[ohm] Missing tag - wikidata, referenced in source keys; fixable, please review"),
                             marktr("{0}={1}: derive ''wikidata=Q…'' by looking up the "
                                + "wikipedia article on the Wikidata API."),
                                key, value)
                    .primitives(p);
                builder.fix(() -> {
                    Optional<String> qidOpt = lookupWikidataQid(canonical);
                    if (qidOpt.isEmpty()) return null;
                    return new ChangePropertyCommand(Arrays.asList(p), "wikidata", qidOpt.get());
                });
                errors.add(builder.build());
                return;
            }
            // Multiple wikipedia tags → unfixable, can't pick a canonical
            // article. No wikipedia at all → also unfixable.
            errors.add(TestError.builder(this, Severity.WARNING, CODE_ATTR_SOURCE_WIKIDATA)
                .message(tr("[ohm] Missing tag - wikidata, referenced in source keys; unfixable, please review & add tag"),
                         marktr("{0}={1}: please add an appropriate ''wikidata'' tag."),
                            key, value)
                .primitives(p)
                .build());
            return;
        }

        // Non-literal value: hand off to the unified source-content pipeline
        // so URL-shape, semicolon split, and slot-typing rules apply uniformly
        // to attribute-source slots. Routes through the same checkSourceTag
        // entry as plain source, passing the attribute prefix so autofix
        // targets land in the matching <attr>:source* slots.
        checkSourceTag(p, key, value, null, attrPrefix);
    }

    /**
     * Return the value of the single canonical {@code wikipedia} key on the
     * primitive, or {@code null} if there is no such key. "Canonical" here
     * means the plain {@code wikipedia=*} tag (no language sub-key); the
     * lookup helper {@link #lookupWikidataQid} parses {@code <lang>:<title>}
     * out of that value.
     */
    private static String canonicalWikipediaForLookup(OsmPrimitive p) {
        return getNonEmpty(p, "wikipedia");
    }

    /**
     * Count of distinct {@code wikipedia*} keys on the primitive with
     * non-empty values. Empty-valued wikipedia* keys don't count toward
     * the "multiple wikipedia tags" ambiguity check in 4309.
     */
    private static int countWikipediaKeys(OsmPrimitive p) {
        int n = 0;
        for (String key : p.keySet()) {
            if (key.equals("wikipedia") || key.startsWith("wikipedia:")) {
                String v = p.get(key);
                if (v != null && !v.isEmpty()) n++;
            }
        }
        return n;
    }

    /**
     * Get a tag value, treating an explicitly-empty value the same as
     * absent. Used by the wikidata/wikipedia presence checks so that
     * {@code wikidata=""} doesn't accidentally suppress the missing-tag
     * rules (the JOSM tag editor lets users leave an empty value behind
     * when clearing without removing the row entirely).
     */
    private static String getNonEmpty(OsmPrimitive p, String key) {
        String v = p.get(key);
        return (v == null || v.isEmpty()) ? null : v;
    }

    /** True if any key on the primitive equals or starts with {@code prefix + ":"}. */
    private static boolean hasAnyKeyStartingWith(OsmPrimitive p, String prefix) {
        for (String key : p.keySet()) {
            if (key.equals(prefix) || key.startsWith(prefix + ":")) {
                return true;
            }
        }
        return false;
    }
}
