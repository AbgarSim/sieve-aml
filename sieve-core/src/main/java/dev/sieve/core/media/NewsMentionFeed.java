package dev.sieve.core.media;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A source of adverse news articles published in batches, such as GDELT's 15-minute files. */
public interface NewsMentionFeed {

    /**
     * Fetches the adverse articles the feed published between two instants.
     *
     * <p>Implementations do not throw on network failures; a batch they could not read counts as
     * missing.
     *
     * @param from the start, inclusive
     * @param to the end, exclusive
     * @return the articles and how far the feed has been read
     */
    Batch fetch(Instant from, Instant to);

    /**
     * The result of one fetch.
     *
     * @param mentions the adverse articles read
     * @param coveredUntil the feed has been read up to here; the next fetch starts from it
     * @param filesRead how many feed files were read
     * @param filesMissing how many feed files could not be read
     */
    record Batch(
            List<NewsMention> mentions, Instant coveredUntil, int filesRead, int filesMissing) {

        /**
         * Compact constructor with validation.
         *
         * @throws NullPointerException if {@code mentions} or {@code coveredUntil} is {@code null}
         */
        public Batch {
            mentions = List.copyOf(Objects.requireNonNull(mentions, "mentions must not be null"));
            Objects.requireNonNull(coveredUntil, "coveredUntil must not be null");
        }
    }
}
