package dev.sieve.beam;

import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import dev.sieve.risk.BehaviouralRiskScorer;
import dev.sieve.risk.MatchEngineScreener;
import dev.sieve.risk.RiskConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a scorer with the default rules that screens counterparties, with Sieve's exact and fuzzy
 * engines, against a list of names, such as names exported from the sanctions lists.
 */
public final class ListedNamesScorerFactory implements ScorerFactory {

    private static final long serialVersionUID = 1L;

    private final ArrayList<String> names;
    private final ListSource source;
    private final double threshold;

    /**
     * Creates the factory.
     *
     * @param names the listed names counterparties are screened against
     * @param source the list the names are reported as coming from
     * @param threshold the lowest match score that counts as a hit
     */
    public ListedNamesScorerFactory(List<String> names, ListSource source, double threshold) {
        this.names = new ArrayList<>(Objects.requireNonNull(names, "names must not be null"));
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.threshold = threshold;
    }

    @Override
    public BehaviouralRiskScorer create() {
        InMemoryEntityIndex index = new InMemoryEntityIndex();
        Instant now = Instant.now();
        for (int i = 0; i < names.size(); i++) {
            index.add(
                    new SanctionedEntity(
                            "listed-" + (i + 1),
                            EntityType.ENTITY,
                            source,
                            new NameInfo(
                                    names.get(i),
                                    null,
                                    null,
                                    null,
                                    null,
                                    NameType.PRIMARY,
                                    null,
                                    null),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            null,
                            List.of(),
                            null,
                            now));
        }
        MatchEngineScreener screener =
                new MatchEngineScreener(
                        new CompositeMatchEngine(
                                List.of(new ExactMatchEngine(), new FuzzyMatchEngine())),
                        index,
                        threshold);
        return BehaviouralRiskScorer.withDefaultRules(RiskConfig.defaults(), screener);
    }
}
