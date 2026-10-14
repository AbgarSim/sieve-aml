package dev.sieve.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Sliding-window counts over transactions sorted oldest first. */
final class Windows {

    private Windows() {}

    /** The most transactions any window of the given length holds. */
    static int busiest(List<Transaction> sorted, Duration window) {
        int best = 0;
        int start = 0;
        for (int end = 0; end < sorted.size(); end++) {
            Instant limit = sorted.get(end).timestamp().minus(window);
            while (sorted.get(start).timestamp().isBefore(limit)) {
                start++;
            }
            best = Math.max(best, end - start + 1);
        }
        return best;
    }

    static String describe(Duration window) {
        if (window.toDays() > 0 && window.equals(Duration.ofDays(window.toDays()))) {
            return window.toDays() == 1 ? "1 day" : window.toDays() + " days";
        }
        if (window.equals(Duration.ofHours(window.toHours()))) {
            return window.toHours() + " hours";
        }
        return window.toMinutes() + " minutes";
    }
}
