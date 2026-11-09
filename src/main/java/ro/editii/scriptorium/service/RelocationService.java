package ro.editii.scriptorium.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Transactional;
import ro.editii.scriptorium.dao.RelocationRepository;
import ro.editii.scriptorium.dao.TeiDivRepository;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.model.Relocation;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.model.TeiFile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps old division URLs working across TEI reimports. A reimport drops a
 * file's divisions and parses them again, and their paths are slugs of their
 * headings, disambiguated in document order - an edited TEI, or a change of
 * the slug rules, gives some of them other paths (Hamlet's Act IV scenes all
 * headed "Another room in the Castle" became .../another, .../another_room,
 * ...). Favourites, reading progress, links and search hits keep the old ones.
 *
 * <p>{@link #snapshot} records a file's divisions before they are deleted;
 * {@link #recordRelocations} matches them to the reimported ones and stores a
 * {@link Relocation} for each path that is gone:
 * <ol>
 *   <li>the division with the same text (its digest, when unique on both
 *       sides) - a slug change, a move, a retitling, a sibling inserted before;</li>
 *   <li>else the division with the same chain of headings (work title down to
 *       its own heading) and the same occurrence of that chain - edited text;</li>
 *   <li>else, under the same (matched) parent, the new sibling whose heading
 *       shares most words with the old one, at least half - a heading that
 *       gained or lost a label ("Scene II. Another room in the Castle");</li>
 *   <li>else ({@link Relocation.Reason#PARENT}) the nearest ancestor that
 *       survived or was matched - a removed division.</li>
 * </ol>
 * Relocations that ended on a moved path are repointed (no chains to follow),
 * relocations of paths that name a division again are dropped (a live path is
 * never redirected), and saved references - collection items, reading
 * progress - are rewritten to the new (canonical, stable) paths.
 *
 * <p>A file rewritten beyond recognition simply relocates little or nothing.
 * Recording never fails an import: it runs in a savepoint, and the import
 * logs and ignores whatever goes wrong here (see TeiFileDbService).
 */
@Service
@Log4j2
@RequiredArgsConstructor
public class RelocationService {

    /** How many relocations {@link #resolve} follows - a guard against loops. */
    static final int MAX_HOPS = 10;
    /** Least share of words two headings must have in common to be the same division's. */
    static final double SIMILAR_HEADINGS = 0.5;

    final RelocationRepository relocationRepository;
    final TeiDivRepository teiDivRepository;
    final TeiFileRepository teiFileRepository;
    final JdbcTemplate jdbcTemplate;
    final DataSource dataSource;

    /** A division as a reimport must recognise it. */
    public record Division(String path, String parentPath, String head, int position,
                           String headings, int occurrence, String digest) {
        String chainKey() { return headings + "#" + occurrence; }
    }

    /** One TEI file's divisions at one moment. */
    public record Snapshot(String teiFilename, List<Division> divisions) {
        public static Snapshot empty(String teiFilename) { return new Snapshot(teiFilename, List.of()); }
        Set<String> paths() { return divisions.stream().map(Division::path).collect(Collectors.toSet()); }
    }

    public record Outcome(int moved, int parent, int repointed, int dropped, int referencesUpdated) {
        public static final Outcome NONE = new Outcome(0, 0, 0, 0, 0);
        public boolean isEmpty() { return moved + parent + repointed + dropped + referencesUpdated == 0; }
    }

    /** "/shakespeare/the_tragedy/act_iv" for "shakespeare/the_tragedy/act_iv.html" or "/shakespeare//the_tragedy/act_iv/". */
    public static String canonical(String path) {
        if (path == null) return null;
        String p = path.split("[?#]", 2)[0];
        final String[] fragms = Arrays.stream(p.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (fragms.length == 0) return "/";
        final String last = fragms[fragms.length - 1];
        final int dot = last.indexOf('.');
        if (dot > 0) fragms[fragms.length - 1] = last.substring(0, dot);
        return "/" + String.join("/", fragms);
    }

    // ---------------------------------------------------------------- snapshot

    @Transactional(readOnly = true)
    public Snapshot snapshot(String teiFilename) {
        return this.teiFileRepository.getByFilename(teiFilename)
                .map(this::snapshot)
                .orElse(Snapshot.empty(teiFilename));
    }

    /** Every imported file's divisions - taken before a destroy-all wipes them in bulk. */
    @Transactional(readOnly = true)
    public Map<String, Snapshot> snapshotAll() {
        final Map<String, Snapshot> all = new LinkedHashMap<>();
        for (TeiFile file : this.teiFileRepository.findAll())
            all.put(file.getFilename(), this.snapshot(file));
        return all;
    }

    /** Within the caller's transaction (the import's): not @Transactional, see recordRelocations. */
    public Snapshot snapshot(TeiFile file) {
        final List<Division> divisions = new ArrayList<>();
        final Map<String, Integer> occurrences = new HashMap<>();
        int position = 0;
        for (TeiDiv opus : this.teiDivRepository.getOperaForTeiFileId(file.getId()))
            walk(opus, null, position++, "", divisions, occurrences);
        return new Snapshot(file.getFilename(), divisions);
    }

    private static void walk(TeiDiv div, String parentPath, int position, String parentHeadings,
                             List<Division> out, Map<String, Integer> occurrences) {
        final String head = normalize(div.getHead());
        // per author too: one file may hold several authors' works
        final String headings = parentHeadings.isEmpty() ? div.getAuthor().getStrId() + "/" + head : parentHeadings + "/" + head;
        final int occurrence = occurrences.merge(headings, 1, Integer::sum);
        final String path = canonical(div.getCompletePath());
        out.add(new Division(path, parentPath, head, position, headings, occurrence,
                div.getTxtSha256() == null ? null : HexFormat.of().formatHex(div.getTxtSha256())));
        final List<TeiElem> children = div.getDbChildren();
        if (children == null) return;
        final List<TeiDiv> subdivisions = children.stream()
                .filter(TeiDiv.class::isInstance).map(TeiDiv.class::cast)
                .sorted(Comparator.comparingInt(TeiElem::getNth).thenComparing(TeiElem::getId, Comparator.nullsLast(Long::compare)))
                .toList();
        for (int i = 0; i < subdivisions.size(); i++)
            walk(subdivisions.get(i), path, i, headings, out, occurrences);
    }

    /** "  Another room in the Castle. " -> "another room in the castle" */
    static String normalize(String head) {
        if (head == null) return "";
        return head.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }

    // ------------------------------------------------------------- relocating

    /**
     * Records where the divisions of {@code before} went, now that their file
     * was reimported as {@code after}; called in the import's transaction,
     * after the parse. Never throws: a failure rolls back to a savepoint taken
     * on the import's own connection, so it costs only these writes, never the
     * import (PostgreSQL aborts a whole transaction after one failed
     * statement), and is logged. Deliberately not @Transactional: an
     * exception crossing a transactional proxy would mark the import
     * rollback-only even when caught. Plain JDBC writes, so a rolled-back
     * savepoint leaves no half-persisted entities in the persistence context.
     */
    public Outcome recordRelocations(Snapshot before, Snapshot after) {
        if (before.divisions().isEmpty()) return Outcome.NONE;
        final Connection connection = DataSourceUtils.getConnection(this.dataSource);
        Savepoint savepoint = null;
        try {
            savepoint = connection.setSavepoint();
            final Outcome outcome = this.relocate(before, after);
            connection.releaseSavepoint(savepoint);
            return outcome;
        } catch (RuntimeException | SQLException e) {
            try {
                if (savepoint != null) connection.rollback(savepoint);
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            log.warn("{}: reimported, but its relocations could not be recorded - old URLs of moved divisions " +
                     "will 404: {}", before.teiFilename(), e.toString(), e);
            return Outcome.NONE;
        } finally {
            DataSourceUtils.releaseConnection(connection, this.dataSource);
        }
    }

    private Outcome relocate(Snapshot before, Snapshot after) {
        final Set<String> livePaths = after.paths();
        final Map<String, String> moved = match(before, after);
        final Timestamp now = Timestamp.from(Instant.now());

        // a removed division: its nearest ancestor that survived or moved
        final Map<String, String> parent = new LinkedHashMap<>();
        for (Division old : before.divisions()) {
            if (livePaths.contains(old.path()) || moved.containsKey(old.path())) continue;
            final String ancestor = survivingAncestor(old.path(), livePaths, moved);
            if (ancestor != null) parent.put(old.path(), ancestor);
        }

        // no live path is ever redirected: drop relocations it reclaimed
        int dropped = 0;
        for (String live : livePaths)
            dropped += this.jdbcTemplate.update("DELETE FROM relocation WHERE old_path = ?", live);

        final Map<String, String> targets = new LinkedHashMap<>(moved);
        targets.putAll(parent);
        // where each old division really went, live paths included: a path a
        // reshuffle gave to another division (a same-heading sibling inserted
        // before) cannot redirect - it is live - but what was saved for the old
        // division, and relocations that ended on it, follow the division
        final Map<String, String> followed = new LinkedHashMap<>(targets);
        followed.putAll(displaced(before, after));

        // earlier relocations that ended on a path that moved now: point them further
        int repointed = 0;
        for (Map.Entry<String, String> target : followed.entrySet())
            repointed += this.jdbcTemplate.update(
                    "DELETE FROM relocation WHERE new_path = ? AND old_path = ?", target.getKey(), target.getValue()); // came home
        repointed += this.rewrite("relocation", "new_path", followed, false);

        final List<Object[]> rows = new ArrayList<>();
        moved.forEach((from, to) -> rows.add(new Object[]{from, to, Relocation.Reason.MOVED.name(), now}));
        parent.forEach((from, to) -> rows.add(new Object[]{from, to, Relocation.Reason.PARENT.name(), now}));
        for (Object[] row : rows) {
            this.jdbcTemplate.update("DELETE FROM relocation WHERE old_path = ?", row[0]);
            this.jdbcTemplate.update("INSERT INTO relocation (old_path, new_path, reason, created_at) VALUES (?, ?, ?, ?)", row);
        }

        final int references = this.updateReferences(followed);
        final Outcome outcome = new Outcome(moved.size(), parent.size(), repointed, dropped, references);
        final long unrecognised = before.divisions().stream()
                .filter(d -> !livePaths.contains(d.path()) && !targets.containsKey(d.path())).count();
        if (!outcome.isEmpty() || unrecognised > 0)
            log.info("{}: relocated {} moved and {} removed division(s), {} not recognised (404); " +
                     "{} earlier relocation(s) repointed, {} dropped (path live again); {} saved reference(s) updated",
                    before.teiFilename(), moved.size(), parent.size(), unrecognised, repointed, dropped, references);
        return outcome;
    }

    /** Old path -> where its division is now, by identical text (digests unique on both sides). */
    static Map<String, String> byText(Snapshot before, Snapshot after) {
        final Map<String, String> byDigest = uniqueDigests(after);
        final Set<String> oldUnique = uniqueDigests(before).keySet();
        final Map<String, String> identity = new LinkedHashMap<>();
        for (Division old : before.divisions())
            if (old.digest() != null && oldUnique.contains(old.digest()) && byDigest.containsKey(old.digest()))
                identity.put(old.path(), byDigest.get(old.digest()));
        return identity;
    }

    /** Old path -> new path, for the old divisions whose path no longer exists. */
    static Map<String, String> match(Snapshot before, Snapshot after) {
        final Set<String> livePaths = after.paths();
        final Map<String, String> identity = byText(before, after);
        final Map<String, String> byChain = after.divisions().stream()
                .collect(Collectors.toMap(Division::chainKey, Division::path, (a, b) -> a));
        // taken: a path whose old division stays there, and every path a division is known (by text) to be at now;
        // a live path whose old division moved away is free for another one (a reshuffle)
        final Set<String> claimed = new HashSet<>(identity.values());
        for (Division old : before.divisions())
            if (livePaths.contains(old.path()) && (!identity.containsKey(old.path()) || identity.get(old.path()).equals(old.path())))
                claimed.add(old.path());
        final Map<String, String> moved = new LinkedHashMap<>();

        final List<Division> pending = new ArrayList<>();
        for (Division old : before.divisions()) {
            if (livePaths.contains(old.path())) continue;
            String target = identity.get(old.path());
            if (target == null) {
                final String sameHeadings = byChain.get(old.chainKey());
                if (sameHeadings != null && !claimed.contains(sameHeadings)) target = sameHeadings;
            }
            if (target != null) {
                claimed.add(target);
                moved.put(old.path(), target);
            } else {
                pending.add(old);
            }
        }

        // similar headings under the same parent, parents first (document order)
        final Map<String, List<Division>> newChildren = after.divisions().stream()
                .filter(d -> d.parentPath() != null)
                .collect(Collectors.groupingBy(Division::parentPath, LinkedHashMap::new, Collectors.toList()));
        for (Division old : pending) {
            if (old.parentPath() == null) continue; // a work's own title: no siblings to compare
            final String newParent = livePaths.contains(old.parentPath()) ? old.parentPath() : moved.get(old.parentPath());
            if (newParent == null) continue;
            Division best = null;
            double bestScore = 0;
            for (Division candidate : newChildren.getOrDefault(newParent, List.of())) {
                if (claimed.contains(candidate.path())) continue;
                final double score = similarity(old.head(), candidate.head())
                        + (candidate.position() == old.position() ? 0.01 : 0); // same place breaks a tie
                if (score > bestScore) { best = candidate; bestScore = score; }
            }
            if (best != null && bestScore >= SIMILAR_HEADINGS) {
                claimed.add(best.path());
                moved.put(old.path(), best.path());
            }
        }
        return moved;
    }

    /** Shared words over all words (Jaccard), 1 when one heading's words all occur in the other's. */
    static double similarity(String a, String b) {
        final Set<String> left = new HashSet<>(Arrays.asList(a.split(" "))), right = new HashSet<>(Arrays.asList(b.split(" ")));
        left.remove(""); right.remove("");
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.containsAll(right) || right.containsAll(left)) return 1;
        final Set<String> common = new HashSet<>(left);
        common.retainAll(right);
        final Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) common.size() / union.size();
    }

    private static Map<String, String> uniqueDigests(Snapshot snapshot) {
        final Map<String, List<Division>> byDigest = snapshot.divisions().stream()
                .filter(d -> d.digest() != null)
                .collect(Collectors.groupingBy(Division::digest));
        return byDigest.entrySet().stream()
                .filter(e -> e.getValue().size() == 1)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get(0).path()));
    }

    /** The removed division's closest ancestor that lives on (or moved); never above the work. */
    private static String survivingAncestor(String path, Set<String> livePaths, Map<String, String> moved) {
        String current = path;
        while (current.lastIndexOf('/') > 0) {
            current = current.substring(0, current.lastIndexOf('/'));
            if (current.chars().filter(c -> c == '/').count() < 2) return null; // /author alone is not a division
            if (livePaths.contains(current)) return current;
            if (moved.containsKey(current)) return moved.get(current);
        }
        return null;
    }

    /** Old divisions whose path lives on but holds another division now: old path -> where the old one went. */
    static Map<String, String> displaced(Snapshot before, Snapshot after) {
        final Set<String> livePaths = after.paths();
        final Map<String, String> displaced = new LinkedHashMap<>();
        byText(before, after).forEach((from, to) -> {
            if (livePaths.contains(from) && !from.equals(to)) displaced.put(from, to);
        });
        return displaced;
    }

    /** Saved references follow their division: collection items and reading progress (stored without the leading slash). */
    private int updateReferences(Map<String, String> followed) {
        return this.rewrite("book_collection_item", "div_path", followed, true)
             + this.rewrite("reading_progress", "div_path", followed, true)
             + this.rewrite("reading_progress", "opus_path", followed, true);
    }

    private static final String MARK = "\u0001relocating:";

    /**
     * Applies every old -> new mapping at once: through a marker, so a value
     * just rewritten is never rewritten again by another mapping (P->Q, Q->R
     * must not send P's rows on to R). {@code bare}: the column stores paths
     * without the leading slash (either form is accepted).
     */
    private int rewrite(String table, String column, Map<String, String> mapping, boolean bare) {
        int updated = 0;
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            final String to = bare ? e.getValue().substring(1) : e.getValue();
            for (String from : bare ? List.of(e.getKey().substring(1), e.getKey()) : List.of(e.getKey()))
                updated += this.jdbcTemplate.update(
                        "UPDATE " + table + " SET " + column + " = ? WHERE " + column + " = ?", MARK + to, from);
        }
        if (updated > 0)
            this.jdbcTemplate.update("UPDATE " + table + " SET " + column + " = SUBSTRING(" + column + ", ?) WHERE "
                    + column + " LIKE ?", MARK.length() + 1, MARK + "%");
        return updated;
    }

    // -------------------------------------------------------------- resolving

    /**
     * Where {@code path} lives now, if it was relocated: the end of its
     * relocation chain (canonical), or empty. A loop yields empty.
     */
    @Transactional(readOnly = true)
    public Optional<Relocation> resolve(String path) {
        final String start = canonical(path);
        if (start == null || start.equals("/")) return Optional.empty();
        String current = start;
        Relocation last = null;
        final Set<String> seen = new HashSet<>();
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            if (!seen.add(current)) return Optional.empty(); // a loop
            final Optional<Relocation> next = this.find(current);
            if (next.isEmpty()) break;
            last = next.get();
            current = canonical(last.getNewPath());
        }
        if (last == null || current.equals(start)) return Optional.empty();
        return Optional.of(new Relocation(start, current, last.getReason(), last.getCreatedAt()));
    }

    // Relocations entered by hand may lack the leading slash.
    private Optional<Relocation> find(String canonicalPath) {
        final Optional<Relocation> withSlash = this.relocationRepository.findById(canonicalPath);
        return withSlash.isPresent() ? withSlash : this.relocationRepository.findById(canonicalPath.substring(1));
    }

    /** All relocations keyed by old path (tests, tooling). */
    @Transactional(readOnly = true)
    public Map<String, Relocation> all() {
        return this.relocationRepository.findAll().stream()
                .collect(Collectors.toMap(Relocation::getOldPath, Function.identity()));
    }
}
