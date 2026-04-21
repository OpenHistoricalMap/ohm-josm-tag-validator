// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.ohmtags.validation;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
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
 *   <li>Named features without a {@code wikidata} tag are warned. Wikidata
 *       is the preferred identifier for cross-referencing.</li>
 * </ul>
 *
 * <p><b>Source.</b>
 * <ul>
 *   <li>Named features without any {@code source*} tag are warned — named
 *       features should document the provenance of their geometry and
 *       metadata.</li>
 *   <li>{@code source} or numeric-variant {@code source:N} set to the
 *       literal value {@code Wikipedia} or {@code Wikidata} (any case) is
 *       warned with a specific message — these aren't acceptable primary
 *       geometry sources regardless of whether companion
 *       {@code wikipedia=*} or {@code wikidata=*} tags exist.</li>
 *   <li>{@code source} / {@code source:N} set to a URL-like value missing
 *       a scheme ({@code example.com/page}) is warned with an autofix
 *       prepending {@code https://}.</li>
 *   <li>{@code source} / {@code source:N} set to any other non-URL value
 *       is warned with an autofix renaming the key to {@code source:name}
 *       / {@code source:N:name} and clearing the original — the user then
 *       supplies a proper URL.</li>
 *   <li>{@code source:N:name} without a matching {@code source:N} is
 *       warned — the URL slot is empty.</li>
 *   <li>Any attribute-scoped source ({@code <attr>:source}, e.g.
 *       {@code start_date:source}) set to {@code Wikipedia} requires a
 *       companion {@code wikipedia=*} tag; similarly {@code Wikidata}
 *       requires {@code wikidata=*}. Otherwise warned.</li>
 *   <li>When both {@code source} and {@code source:url} exist,
 *       {@link #checkSourceUrlConsolidation} folds them together: if the
 *       two values are identical, the redundant {@code source:url} is
 *       deleted; otherwise the existing {@code source} is moved to
 *       {@code source:name} (appended with {@code ;} if {@code source:name}
 *       already exists), {@code source:url} is renamed to {@code source},
 *       and {@code source:url} is deleted.</li>
 *   <li>When a {@code source} value contains semicolons,
 *       {@link #checkSemicolonSeparatedSource} splits the items based on
 *       their shape: exactly-one-URL + exactly-one-text is split into
 *       {@code source} and {@code source:name}; multiple URLs are
 *       enumerated into {@code source}, {@code source:1}, etc.; multiple
 *       text items are enumerated into {@code source:name},
 *       {@code source:1:name}, etc.; 3+ mixed items get a warning without
 *       autofix.</li>
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
    protected static final int CODE_SOURCE_NOT_URL = 4306;
    protected static final int CODE_SOURCE_MISSING_SCHEME = 4307;
    protected static final int CODE_ATTR_SOURCE_WIKIPEDIA = 4308;
    protected static final int CODE_ATTR_SOURCE_WIKIDATA = 4309;
    protected static final int CODE_SOURCE_NAME_WITHOUT_URL = 4310;
    // New error codes for this revision.
    protected static final int CODE_SOURCE_URL_REDUNDANT = 4311;
    protected static final int CODE_SOURCE_URL_CONFLICTS = 4312;
    protected static final int CODE_SOURCE_URL_WITH_NAME = 4313;
    protected static final int CODE_SOURCE_SEMICOLON_URL_TEXT = 4314;
    protected static final int CODE_SOURCE_SEMICOLON_MULTI_URL = 4315;
    protected static final int CODE_SOURCE_SEMICOLON_MULTI_TEXT = 4316;
    protected static final int CODE_SOURCE_SEMICOLON_MIXED = 4317;

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
    }

    private void checkPrimitive(OsmPrimitive p) {
        if (!p.hasKeys()) return;

        // Source:url consolidation runs before the per-key source check so
        // that if it fires, it does so against the un-processed state.
        checkSourceUrlConsolidation(p);

        boolean hasAnyNameFamily = false;
        boolean hasPlainName = p.get("name") != null;

        for (String key : p.keySet()) {
            // Name-family handling
            if (NAME_FAMILY_KEY.matcher(key).matches()) {
                hasAnyNameFamily = true;
                String value = p.get(key);
                if (value != null && (value.contains("(") || value.contains(")"))) {
                    errors.add(TestError.builder(this, Severity.WARNING, CODE_NAME_HAS_PARENS)
                        .message(tr("[ohm] Name warning - parentheses in name; unfixable, please review"),
                                 tr("{0}={1}: ''(dates)'' are discouraged in names; "
                                    + "please review and remove any dates in name keys.",
                                    key, value))
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

            Matcher sourceNameMatch = SOURCE_NAME_KEY.matcher(key);
            if (sourceNameMatch.matches()) {
                String numIdx = sourceNameMatch.group(1);
                String companionSourceKey = numIdx == null
                    ? "source" : "source:" + numIdx;
                if (p.get(companionSourceKey) == null
                    || p.get(companionSourceKey).isEmpty()) {
                    errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_NAME_WITHOUT_URL)
                        .message(tr("[ohm] Source optimization - source[:#]:name is present, but source[:#] is not; please review & add a source=URL, if possible"),
                                 tr("{0}={1} is set, but {2} is empty. "
                                    + "Would you like to add a URL for the source?",
                                    key, p.get(key), companionSourceKey))
                        .primitives(p)
                        .build());
                }
            }
        }

        if (!hasAnyNameFamily) return; // No name-related checks apply.

        // Rule: any name-family keys but no plain name.
        if (!hasPlainName) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_MISSING_PLAIN_NAME)
                .message(tr("[ohm] Missing tag - name=*; unfixable, please review"),
                         tr("Feature has name-family keys ({0}, etc.) but no plain "
                            + "''name'' key. Please add a canonical name.",
                            firstNameFamilyKeyFound(p)))
                .primitives(p)
                .build());
        }

        // Rule: named feature without wikidata.
        if (p.get("wikidata") == null) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_MISSING_WIKIDATA)
                .message(tr("[ohm] Missing tag - wikidata; unfixable, please review and add a Wikidata QID"),
                         tr("This named feature has no ''wikidata'' tag. "
                            + "Wikidata is the preferred identifier for cross-referencing."))
                .primitives(p)
                .build());
        }

        // Rule: named feature without any source*.
        if (!hasAnySourceTag(p)) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_MISSING_SOURCE)
                .message(tr("[ohm] Missing tag - source; named feature without source; unfixable, please review"),
                         tr("This named feature has no ''source'' tag. "
                            + "Please document the provenance of this feature."))
                .primitives(p)
                .build());
        }
    }

    // --- Helpers -------------------------------------------------------------

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

    /**
     * Check a {@code source} or {@code source:N} tag. {@code numIdx} is the
     * captured numeric sub-index, or null for the plain {@code source} key.
     *
     * <p>If the value contains semicolons, hand off to
     * {@link #checkSemicolonSeparatedSource} for the split logic. Otherwise
     * run the single-value checks (Wikipedia/Wikidata, URL with/without
     * scheme, non-URL rename-to-:name).
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
                         tr("{0}={1}: Wikipedia is not a reasonable source for "
                            + "geometry claims. Please link to an actual map, image, "
                            + "or other primary source.",
                            key, value))
                .primitives(p)
                .build());
            return;
        }
        if ("wikidata".equalsIgnoreCase(value)) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_IS_WIKIDATA)
                .message(tr("[ohm] Suspicious source - source=wikidata; unfixable, please review"),
                         tr("{0}={1}: Wikidata is not a reasonable source for "
                            + "geometry claims. Please link to an actual map, image, "
                            + "or other primary source.",
                            key, value))
                .primitives(p)
                .build());
            return;
        }

        // Already a proper URL? Nothing to flag.
        if (URL_WITH_SCHEME.matcher(value).matches()) return;

        // URL-shaped but missing scheme? Offer to prepend https://
        if (URL_MISSING_SCHEME.matcher(value).matches()) {
            String fixed = "https://" + value;
            Command fix = new ChangePropertyCommand(Arrays.asList(p), key, fixed);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_MISSING_SCHEME)
                .message(tr("[ohm] Source optimization - repair URL missing ''http[s]://''"),
                         tr("{0}={1} looks like a URL missing the scheme. Prepend ''https://''?",
                            key, value))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // Non-URL, non-Wikipedia/Wikidata source. Offer to rename to :name.
        String renamedKey = numIdx == null
            ? "source:name" : "source:" + numIdx + ":name";
        List<Command> cmds = new ArrayList<>();
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), renamedKey, value));
        cmds.add(new ChangePropertyCommand(Arrays.asList(p), key, null));
        Command fix = new SequenceCommand(tr("Rename {0} to {1}", key, renamedKey), cmds);
        errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_NOT_URL)
            .message(tr("[ohm] Source optimization - move non-URL source tags to source:name"),
                     tr("{0}={1} is not a URL. Move to {2} and leave {0} blank "
                        + "for a URL?",
                        key, value, renamedKey))
            .primitives(p)
            .fix(() -> fix)
            .build());
    }

    /**
     * Handle a {@code source} value containing semicolons.
     *
     * <p>Cases, based on classification of each semicolon-separated item:
     * <ul>
     *   <li><b>2 items, one URL + one text.</b> Warn with autofix: URL →
     *       {@code source}, text → {@code source:name}.</li>
     *   <li><b>2+ items, all URLs.</b> Warn with autofix enumerating into
     *       {@code source}, {@code source:1}, {@code source:2}, etc.</li>
     *   <li><b>2+ items, all text.</b> Warn with autofix enumerating into
     *       {@code source:name}, {@code source:1:name}, etc. (existing
     *       {@code source} untouched.)</li>
     *   <li><b>3+ items, mixed URL and text.</b> Warn, no autofix.</li>
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

        // Case: exactly 2 items, one URL + one text.
        if (items.size() == 2 && urlCount == 1 && textCount == 1) {
            String urlPart = URL_WITH_SCHEME.matcher(items.get(0)).matches()
                ? items.get(0) : items.get(1);
            String textPart = urlPart.equals(items.get(0)) ? items.get(1) : items.get(0);
            List<Command> cmds = new ArrayList<>();
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), key, urlPart));
            // Respect existing source:name if present — use the append logic
            // consistent with source:url consolidation.
            String existingName = p.get("source:name");
            String newName = (existingName == null || existingName.isEmpty())
                ? textPart
                : existingName + ";" + textPart;
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "source:name", newName));
            Command fix = new SequenceCommand(
                tr("Split source into URL and name"), cmds);
            errors.add(TestError.builder(this, Severity.WARNING,
                                         CODE_SOURCE_SEMICOLON_URL_TEXT)
                .message(tr("[ohm] Source optimization - source contains one URL and one text string; autofix by splitting into source and source:name"),
                         tr("{0}={1}: move URL to source and text to source:name?", key, value))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // Case: all URLs (2+).
        if (urlCount == items.size()) {
            List<Command> cmds = new ArrayList<>();
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "source", items.get(0)));
            for (int i = 1; i < items.size(); i++) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                   "source:" + i, items.get(i)));
            }
            Command fix = new SequenceCommand(tr("Enumerate source URLs"), cmds);
            errors.add(TestError.builder(this, Severity.WARNING,
                                         CODE_SOURCE_SEMICOLON_MULTI_URL)
                .message(tr("[ohm] Source optimization - source contains multiple URLs; autofix by enumerating source:# keys"),
                         tr("{0}={1}: enumerate into source, source:1, source:2, ...?",
                            key, value))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // Case: all text (2+).
        if (textCount == items.size()) {
            List<Command> cmds = new ArrayList<>();
            // Existing source tag is untouched per spec. Text items go into
            // source:name (and enumerated source:N:name variants).
            cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                               "source:name", items.get(0)));
            for (int i = 1; i < items.size(); i++) {
                cmds.add(new ChangePropertyCommand(Arrays.asList(p),
                                                   "source:" + i + ":name", items.get(i)));
            }
            // Clear the combined-value source tag since the pieces are now
            // redistributed. But only if it matches what we're splitting —
            // don't clobber if something changed since.
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), key, null));
            Command fix = new SequenceCommand(tr("Enumerate source names"), cmds);
            errors.add(TestError.builder(this, Severity.WARNING,
                                         CODE_SOURCE_SEMICOLON_MULTI_TEXT)
                .message(tr("[ohm] Source optimization - source contains multiple text strings; autofix by enumerating source:#:name keys"),
                         tr("{0}={1}: enumerate into source:name, source:1:name, ...?",
                            key, value))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // Case: 3+ mixed. Warn, no fix.
        errors.add(TestError.builder(this, Severity.WARNING,
                                     CODE_SOURCE_SEMICOLON_MIXED)
            .message(tr("[ohm] Source mismatch - source contains multiple values of different types; unfixable, please review"),
                     tr("{0}={1}: 3 or more items mixing URLs and text. "
                      + "Manual review needed — split into source, source:N, "
                      + "source:name, source:N:name as appropriate.",
                        key, value))
            .primitives(p)
            .build());
    }

    /**
     * Detect and fix the (common) case where a feature has both
     * {@code source} and {@code source:url} tags. Three sub-cases:
     *
     * <ul>
     *   <li><b>{@code source} value equals {@code source:url} value.</b>
     *       Delete the redundant {@code source:url}.</li>
     *   <li><b>{@code source} is a URL differing from {@code source:url}.</b>
     *       Three-step rewrite: existing {@code source} → {@code source:name},
     *       {@code source:url} → {@code source}, delete {@code source:url}.
     *       If {@code source:name} already exists, append the existing
     *       {@code source} value to it with {@code ;} separator.</li>
     *   <li><b>{@code source} is non-URL text.</b> Three-step rewrite as above:
     *       existing {@code source} → {@code source:name} (with append logic
     *       if {@code source:name} already exists), {@code source:url} →
     *       {@code source}, delete {@code source:url}.</li>
     * </ul>
     */
    private void checkSourceUrlConsolidation(OsmPrimitive p) {
        String source = p.get("source");
        String sourceUrl = p.get("source:url");
        if (sourceUrl == null || sourceUrl.isEmpty()) return;
        // If source has semicolons, defer to the semicolon handler — don't
        // treat a multi-value source as a single value here.
        if (source != null && source.contains(";")) return;
        // If source is blank, just rename source:url → source.
        if (source == null || source.isEmpty()) {
            List<Command> cmds = new ArrayList<>();
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "source", sourceUrl));
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "source:url", null));
            Command fix = new SequenceCommand(tr("Rename source:url to source"), cmds);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_URL_WITH_NAME)
                .message(tr("[ohm] Source mismatch - no source tag and valid source:url tag; autofix by moving *:url value to source="),
                         tr("source:url={0} should live in source.", sourceUrl))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        // Case 1: identical values. Redundant.
        if (source.equals(sourceUrl)) {
            Command fix = new ChangePropertyCommand(Arrays.asList(p),
                                                    "source:url", null);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_URL_REDUNDANT)
                .message(tr("[ohm] Source keys with duplicate values - source=source:url; autofix by deleting source:url"),
                         tr("source and source:url hold the same value. Delete source:url?"))
                .primitives(p)
                .fix(() -> fix)
                .build());
            return;
        }

        boolean sourceIsUrl = URL_WITH_SCHEME.matcher(source).matches();

        if (sourceIsUrl) {
            // Case 2: both source and source:url are URLs but differ.
            // Move source:url to the next available source:N slot.
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_URL_CONFLICTS)
                .message(tr("[ohm] Source mismatch - source and source:url are different URLs; autofix by moving source:url to source:N"),
                         tr("source={0} and source:url={1} are different URLs. Move source:url to the next numbered source key?",
                            source, sourceUrl))
                .primitives(p)
                .fix(() -> {
                    int maxN = p.keySet().stream()
                        .map(k -> SOURCE_KEY.matcher(k))
                        .filter(java.util.regex.Matcher::matches)
                        .map(m -> m.group(1))
                        .filter(g -> g != null)
                        .mapToInt(Integer::parseInt)
                        .max()
                        .orElse(0);
                    String newKey = "source:" + (maxN + 1);
                    List<Command> moveCmds = new ArrayList<>();
                    moveCmds.add(new ChangePropertyCommand(Arrays.asList(p), newKey, sourceUrl));
                    moveCmds.add(new ChangePropertyCommand(Arrays.asList(p), "source:url", null));
                    return new SequenceCommand(tr("Move source:url to {0}", newKey), moveCmds);
                })
                .build());
        } else {
            // Case 3: source is text, source:url is a URL — swap them.
            String existingName = p.get("source:name");
            String newName = (existingName == null || existingName.isEmpty())
                ? source : existingName + ";" + source;
            List<Command> cmds = new ArrayList<>();
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "source:name", newName));
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "source", sourceUrl));
            cmds.add(new ChangePropertyCommand(Arrays.asList(p), "source:url", null));
            Command fix = new SequenceCommand(tr("Consolidate source and source:url"), cmds);
            errors.add(TestError.builder(this, Severity.WARNING, CODE_SOURCE_URL_WITH_NAME)
                .message(tr("[ohm] Source optimization - source contains a name and source:url contains a URL; autofix by swapping these"),
                         tr("Consolidate: source:url \u2192 source, source \u2192 source:name?"))
                .primitives(p)
                .fix(() -> fix)
                .build());
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
                    .message(tr("[ohm] Missing tag - wikipedia, referenced in source keys; unfixable, please review and add an appropriate Wikipedia article"),
                             tr("{0}={1}: please add an appropriate ''wikipedia'' tag.",
                                key, value))
                    .primitives(p)
                    .build());
            }
            return;
        }
        if ("wikidata".equalsIgnoreCase(value)) {
            if (p.get("wikidata") == null) {
                errors.add(TestError.builder(this, Severity.WARNING, CODE_ATTR_SOURCE_WIKIDATA)
                    .message(tr("[ohm] Missing tag - wikidata, referenced in source keys; unfixable, please add appropriate Wikidata QID"),
                             tr("{0}={1}: please add an appropriate ''wikidata'' tag.",
                                key, value))
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
