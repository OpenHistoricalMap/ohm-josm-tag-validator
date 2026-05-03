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
import java.util.List;
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

    private void checkPrimitive(OsmPrimitive p) {
        if (!p.hasKeys()) return;

        // Source:url consolidation runs before the per-key source check so
        // that if it fires, it does so against the un-processed state.
        checkSourceUrlConsolidation(p);
        checkSourceNameContents(p);

        // Suspicious historic=*. OHM convention is that historic=* applies
        // to entities that have actually passed into history; using it on
        // a still-current feature is premature. We emit on every historic=*
        // tag and let the editor confirm.
        String historic = p.get("historic");
        if (historic != null) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_HISTORIC_SUSPICIOUS)
                .message(tr("[ohm] Suspicious tag - historic; unfixable, should only be used once an object actually is historic"),
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
                if (value != null && containsDateInParens(value)) {
                    errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_PARENS)
                        .message(tr("[ohm] Name warning - parentheses in name; unfixable, please review"),
                                 marktr("{0}={1}: dates in parentheses are discouraged in names; "
                                    + "move the date to start_date / end_date instead."),
                                    key, value)
                        .primitives(p)
                        .build());
                }
                if (value != null && HISTORIC_IN_NAME.matcher(value).find()) {
                    errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_HISTORIC)
                        .message(tr("[ohm] Name warning - \"historic\" in name; unfixable, please review if this is date appropriate"),
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
                checkAttrSourceTag(p, key, p.get(key), attrSourceMatch.group(1));
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
        if (p.get("wikidata") == null && hasNotabilitySignal(p)) {
            TestError.Builder builder = TestError.builder(this, Severity.ERROR, CODE_MISSING_WIKIDATA)
                .message(tr("[ohm] Missing tag - wikidata; unfixable, please review"),
                         marktr("Wikidata QIDs help link OHM data to other databases."))
                .primitives(p);
            String wikipediaValue = p.get("wikipedia");
            if (wikipediaValue != null) {
                builder.fix(() -> {
                    Optional<String> qidOpt = lookupWikidataQid(wikipediaValue);
                    if (qidOpt.isEmpty()) return null;
                    return new ChangePropertyCommand(Arrays.asList(p), "wikidata", qidOpt.get());
                });
            }
            errors.add(builder.build());
        }

        // Rule: named feature without any source*. Skips type=chronology
        // relations: they're aggregator wrappers around member relations
        // (each of which has its own source provenance), so requiring a
        // top-level source tag on the chronology adds noise without
        // signal — see issue #23.
        if (!hasAnySourceTag(p) && !isChronologyRelation(p)) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_MISSING_SOURCE)
                .message(tr("[ohm] Missing tag - source on named feature; unfixable, please review and add"),
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
        if (p.get("wikipedia") != null) return true;
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
     * True if {@code value} contains a parenthesised group whose contents
     * include a year-like number sequence. Used to narrow rule 4301 to
     * names that actually encode dates in parens, not non-date
     * disambiguation like {@code (Springfield)} or {@code (north section)}.
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
        if (value == null || value.isEmpty()) return;

        // Semicolon-separated — might be multiple sources the user bundled
        // together. Only fires for the plain source key (not source:N),
        // since source:1, source:2 etc. are already the enumeration target.
        if (numIdx == null && value.contains(";")) {
            checkSemicolonSeparatedSource(p, key, value);
            return;
        }

        // Wikipedia / Wikidata as source: specific warnings, no autofix.
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

        // URL-shaped but missing scheme? Offer to prepend https://. (4307
        // also runs against source:url / source:N:url in checkSourceUrlPair.)
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
            .message(tr("[ohm] Source optimization - repair URL missing ''http[s]://''"),
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
        return p.keySet().stream()
            .map(SOURCE_KEY::matcher)
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
    private void checkSemicolonSeparatedSource(OsmPrimitive p, String key, String value) {
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

        // Case: exactly 2 items, one URL + one text. Under v0.5 contract:
        // source=text, source:url=URL.
        if (items.size() == 2 && urlCount == 1 && textCount == 1) {
            String urlPart = URL_WITH_SCHEME.matcher(items.get(0)).matches()
                ? items.get(0) : items.get(1);
            String textPart = urlPart.equals(items.get(0)) ? items.get(1) : items.get(0);

            String existingUrl = p.get("source:url");
            boolean urlSlotEmpty = existingUrl == null || existingUrl.isEmpty();
            boolean urlSlotMatches = urlPart.equals(existingUrl);

            if (!urlSlotEmpty && !urlSlotMatches) {
                // source:url already holds a different URL — autofix would
                // clobber it.
                errors.add(TestError.builder(this, Severity.WARNING,
                                             CODE_SOURCE_SEMICOLON_URL_TEXT)
                    .message(tr("[ohm] Source mismatch - source contains 1 URL & 1 text string but source:url already holds a different value; unfixable, please review"),
                             marktr("{0}={1}: cannot split into source={2} and source:url={3} "
                                + "because source:url already holds {4}. Manual review needed."),
                                key, value, textPart, urlPart, existingUrl)
                    .primitives(p)
                    .build());
                return;
            }

            List<Command> cmds = new ArrayList<>();
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), key, textPart));
            if (urlSlotEmpty) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(p), "source:url", urlPart));
            }
            Command fix = new SequenceCommand(
                tr("Split source into text and source:url"), cmds);
            errors.add(TestError.builder(this, Severity.WARNING,
                                         CODE_SOURCE_SEMICOLON_URL_TEXT)
                .message(tr("[ohm] Source optimization - source contains 1 URL & 1 text string; autofix by splitting into source & source:url"),
                         marktr("{0}={1}: move text to source and URL to source:url?"), key, value)
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // Case: all URLs (2+). Enumerate per the shared convention.
        if (urlCount == items.size()) {
            emitMultiUrlSplit(p, key, value, items);
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
                            + "source, source:1, source:2, …; if the semicolons are "
                            + "punctuation in a single citation, leave alone."),
                            key, value)
                .primitives(p)
                .build());
            return;
        }

        // Case: 3+ mixed. Warn, no fix.
        errors.add(TestError.builder(this, Severity.WARNING,
                                     CODE_SOURCE_SEMICOLON_MIXED)
            .message(tr("[ohm] Source mismatch - source contains multiple values of different types; unfixable, please review"),
                     marktr("{0}={1}: 3 or more items mixing URLs and text. "
                      + "Manual review needed — split into source, source:N, "
                      + "source:url, source:N:url as appropriate."),
                        key, value)
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
    private void emitMultiUrlSplit(OsmPrimitive p, String key, String value, List<String> items) {
        boolean hasExistingEnumerated = p.keySet().stream()
            .map(SOURCE_KEY::matcher)
            .filter(Matcher::matches)
            .anyMatch(m -> m.group(1) != null);

        List<Command> cmds = new ArrayList<>();
        if (!hasExistingEnumerated) {
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "source", items.get(0)));
            for (int i = 1; i < items.size(); i++) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                   "source:" + i, items.get(i)));
            }
        } else {
            int start = nextSourceIndex(p);
            // Clear the combined-value source tag — items are being relocated.
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), key, null));
            for (int i = 0; i < items.size(); i++) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                   "source:" + (start + i), items.get(i)));
            }
        }

        Command fix = new SequenceCommand(tr("Enumerate source URLs"), cmds);
        errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_SEMICOLON_MULTI_URL)
            .message(tr("[ohm] Source optimization - source contains multiple URLs; autofix by enumerating source:# keys"),
                     marktr("{0}={1}: enumerate into source, source:1, source:2, ...?"),
                        key, value)
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
    private void checkSourceUrlConsolidation(OsmPrimitive p) {
        // Snapshot all url-shaped keys before invoking per-pair checks. We
        // only read keys (not modifying the primitive) so iteration is safe;
        // a snapshot avoids any future surprise.
        List<String[]> pairs = new ArrayList<>();
        for (String key : p.keySet()) {
            Matcher m = SOURCE_URL_KEY.matcher(key);
            if (m.matches()) {
                String numIdx = m.group(1);
                String companionKey = (numIdx == null) ? "source" : "source:" + numIdx;
                pairs.add(new String[] { key, companionKey });
            }
        }
        for (String[] pair : pairs) {
            checkSourceUrlPair(p, pair[0], pair[1]);
        }
    }

    /**
     * Per-pair logic for one {@code source[:N]?:url} key and its companion
     * {@code source[:N]?}. See {@link #checkSourceUrlConsolidation} for the
     * rules.
     */
    private void checkSourceUrlPair(OsmPrimitive p, String urlKey, String companionKey) {
        String source = p.get(companionKey);
        String sourceUrl = p.get(urlKey);
        if (sourceUrl == null || sourceUrl.isEmpty()) return;

        // 4307: URL missing scheme in :url. Autofix prepends https://.
        if (!URL_WITH_SCHEME.matcher(sourceUrl).matches()
            && URL_MISSING_SCHEME.matcher(sourceUrl).matches()) {
            emitMissingSchemeFix(p, urlKey, sourceUrl);
            return;
        }

        // 4325: non-URL text in :url. Fallback chain: source / source:name
        // / source:note.
        if (!URL_WITH_SCHEME.matcher(sourceUrl).matches()) {
            emitTextInUrlFix(p, urlKey, sourceUrl, companionKey);
            return;
        }

        // sourceUrl is now a valid URL with scheme.
        // If companion has semicolons, defer to the semicolon handler.
        if (source != null && source.contains(";")) return;

        // 4312: both slots hold URLs but differ — move :url to next source:N.
        // (Identical URLs and text-companion + URL-in-:url are both valid
        // under the v0.5 contract; no warning.)
        if (source != null && !source.isEmpty()
            && !source.equals(sourceUrl)
            && URL_WITH_SCHEME.matcher(source).matches()) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_URL_CONFLICTS)
                .message(tr("[ohm] Source mismatch - source and source:url are different URLs; autofix by moving source:url to source:#"),
                         marktr("{0}={1} and {2}={3} are different URLs. Move {2} to the next numbered source key?"),
                            companionKey, source, urlKey, sourceUrl)
                .primitives(p)
                .fix(() -> {
                    String newKey = "source:" + nextSourceIndex(p);
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
                .message(tr("[ohm] Source mismatch - text value in source:url and all sibling slots full; unfixable, please review"),
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
            Matcher m = SOURCE_NAME_KEY.matcher(key);
            if (!m.matches()) continue;
            String value = p.get(key);
            if (value == null || value.isEmpty()) continue;
            if (!URL_WITH_SCHEME.matcher(value).matches()) continue;
            String numIdx = m.group(1);
            String urlKey = (numIdx == null) ? "source:url" : "source:" + numIdx + ":url";
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
                    .message(tr("[ohm] Source mismatch - URL in source:name and source:url already set; unfixable, please review"),
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
     * Only Wikipedia/Wikidata values are checked here (requiring companion
     * tags). Other values are not validated.
     */
    private void checkAttrSourceTag(OsmPrimitive p, String key, String value, String attrPrefix) {
        if (value == null || value.isEmpty()) return;

        if ("wikipedia".equalsIgnoreCase(value)) {
            if (!hasAnyKeyStartingWith(p, "wikipedia")) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_ATTR_SOURCE_WIKIPEDIA)
                    .message(tr("[ohm] Missing tag - wikipedia, referenced in source keys; unfixable, please review and add tag"),
                             marktr("{0}={1}: please add an appropriate ''wikipedia'' tag."),
                                key, value)
                    .primitives(p)
                    .build());
            }
            return;
        }
        if ("wikidata".equalsIgnoreCase(value)) {
            if (p.get("wikidata") == null) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_ATTR_SOURCE_WIKIDATA)
                    .message(tr("[ohm] Missing tag - wikidata, referenced in source keys; unfixable, please review and add tag"),
                             marktr("{0}={1}: please add an appropriate ''wikidata'' tag."),
                                key, value)
                    .primitives(p)
                    .build());
            }
            return;
        }
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
