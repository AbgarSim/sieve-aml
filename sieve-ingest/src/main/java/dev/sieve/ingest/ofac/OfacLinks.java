package dev.sieve.ingest.ofac;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.relations.RemarkRelations;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the links an OFAC list states in words to the entries they point at: the "Linked To:"
 * names in an entry's remarks and the owner named in a vessel's {@code vesselInfo}.
 *
 * <p>OFAC ends a remark with text such as {@code (Linked To: ISLAMIC REVOLUTIONARY GUARD CORPS
 * (IRGC)-QODS FORCE; Linked To: HIZBALLAH)}, where each name is the primary name of another entry
 * on the same list. A name resolves to the one entry whose primary name matches it, ignoring case,
 * accents and punctuation, else to the one entry with a matching alias; a name that matches no
 * entry or several stays text only. Every resolved name becomes a {@link RelationType#LINKED}
 * relation with the role {@value #LINKED_ROLE} on the entry that states it, in the direction OFAC
 * states it. A resolved vessel owner becomes an {@link RelationType#OWNERSHIP} relation on the
 * owner and a {@link RelationType#LINKED} relation on the vessel, both with the role {@value
 * #OWNER_ROLE}.
 */
final class OfacLinks {

    private static final Logger log = LoggerFactory.getLogger(OfacLinks.class);

    /** Role of the relation an entry's "Linked To:" remark gives. */
    static final String LINKED_ROLE = "linked to";

    /** Role of the relations between a vessel and the owner its {@code vesselInfo} names. */
    static final String OWNER_ROLE = "owner";

    private static final String MARKER = "Linked To:";

    private OfacLinks() {}

    /**
     * Returns the names after each "Linked To:" in a remark, in order. A name runs to the next
     * semicolon or to the parenthesis that closes the remark's parenthetical, so names with
     * parentheses of their own, such as {@code CORPS (IRGC)-QODS FORCE}, stay whole.
     *
     * @param remarks the entry's remarks, may be {@code null}
     * @return the names, possibly empty
     */
    static List<String> linkedNames(String remarks) {
        List<String> names = new ArrayList<>();
        if (remarks == null) {
            return names;
        }
        int from = 0;
        while (true) {
            int marker = remarks.indexOf(MARKER, from);
            if (marker < 0) {
                return names;
            }
            int start = marker + MARKER.length();
            int end = remarks.length();
            int depth = 0;
            for (int i = start; i < remarks.length(); i++) {
                char c = remarks.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                    depth--;
                } else if (c == ';' && depth == 0) {
                    end = i;
                    break;
                }
            }
            String name = remarks.substring(start, end).strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
            from = Math.max(end, start);
        }
    }

    /**
     * Adds to the entries the relations their remarks and vessel owners state, keeping the
     * relations they already carry, and logs how many names resolved.
     *
     * @param entities every entry of the list, wallets included
     * @param vesselOwners the owner name each vessel's {@code vesselInfo} gives, by vessel id
     * @param label the list's name for the log
     * @return the entries, in the same order, with their relations
     */
    static List<SanctionedEntity> resolve(
            List<SanctionedEntity> entities, Map<String, String> vesselOwners, String label) {
        Map<String, Set<String>> primary = new HashMap<>();
        Map<String, Set<String>> aliases = new HashMap<>();
        Map<String, SanctionedEntity> byId = new HashMap<>();
        for (SanctionedEntity entity : entities) {
            byId.put(entity.id(), entity);
            if (entity.entityType() == EntityType.CRYPTO_WALLET) {
                continue;
            }
            index(primary, entity.primaryName(), entity.id());
            for (NameInfo alias : entity.aliases()) {
                index(aliases, alias, entity.id());
            }
        }

        Map<String, List<Relation>> added = new HashMap<>();
        int entries = 0;
        int names = 0;
        int resolved = 0;
        int ambiguous = 0;
        List<String> unresolved = new ArrayList<>();
        for (SanctionedEntity entity : entities) {
            List<String> linked = linkedNames(entity.remarks());
            if (!linked.isEmpty()) {
                entries++;
            }
            for (String name : linked) {
                names++;
                String target = resolve(name, entity.id(), primary, aliases);
                if (target == null) {
                    unresolved.add(name);
                } else if (target.isEmpty()) {
                    ambiguous++;
                } else {
                    resolved++;
                    add(added, entity.id(), RelationType.LINKED, target, LINKED_ROLE);
                }
            }
            String owner = vesselOwners.get(entity.id());
            if (owner != null) {
                String target = resolve(owner, entity.id(), primary, aliases);
                if (target == null || target.isEmpty()) {
                    log.debug(
                            "{}: vessel owner not on the list [vessel={}, owner={}]",
                            label,
                            entity.id(),
                            owner);
                } else {
                    add(added, target, RelationType.OWNERSHIP, entity.id(), OWNER_ROLE);
                    add(added, entity.id(), RelationType.LINKED, target, OWNER_ROLE);
                }
            }
        }
        log.info(
                "{}: resolved linked-to names [entries={}, names={}, resolved={}, ambiguous={}, unresolved={}, vesselOwners={}]",
                label,
                entries,
                names,
                resolved,
                ambiguous,
                unresolved.size(),
                vesselOwners.size());
        if (!unresolved.isEmpty()) {
            log.debug(
                    "{}: linked-to names not on the list, such as {}",
                    label,
                    unresolved.subList(0, Math.min(20, unresolved.size())));
        }

        List<SanctionedEntity> result = new ArrayList<>(entities.size());
        for (SanctionedEntity entity : entities) {
            List<Relation> more = added.get(entity.id());
            if (more == null) {
                result.add(entity);
            } else {
                List<Relation> all = new ArrayList<>(entity.relations());
                all.addAll(more);
                result.add(entity.withRelations(all));
            }
        }
        return result;
    }

    /**
     * Returns the id of the one entry a name resolves to, an empty string when several entries
     * match and {@code null} when none does. The name's own entry never counts.
     */
    private static String resolve(
            String name,
            String self,
            Map<String, Set<String>> primary,
            Map<String, Set<String>> aliases) {
        String key = RemarkRelations.normalize(name);
        for (Map<String, Set<String>> index : List.of(primary, aliases)) {
            Set<String> hits = new LinkedHashSet<>(index.getOrDefault(key, Set.of()));
            hits.remove(self);
            if (hits.size() == 1) {
                return hits.iterator().next();
            }
            if (hits.size() > 1) {
                return "";
            }
        }
        return null;
    }

    private static void add(
            Map<String, List<Relation>> added,
            String holder,
            RelationType type,
            String target,
            String role) {
        Relation relation = new Relation(type, target, role, null, null, null);
        List<Relation> relations = added.computeIfAbsent(holder, h -> new ArrayList<>());
        if (!relations.contains(relation)) {
            relations.add(relation);
        }
    }

    private static void index(Map<String, Set<String>> index, NameInfo name, String id) {
        if (name == null || name.fullName() == null) {
            return;
        }
        String key = RemarkRelations.normalize(name.fullName());
        if (!key.isEmpty()) {
            index.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(id);
        }
    }
}
