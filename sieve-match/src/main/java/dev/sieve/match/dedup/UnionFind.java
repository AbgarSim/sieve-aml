package dev.sieve.match.dedup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Union-Find (disjoint set) data structure for transitive entity merging.
 *
 * <p>When entity A matches entity B, and entity B matches entity C, all three should be merged
 * into a single canonical entity. Union-Find efficiently handles this transitive closure.
 *
 * <p>Uses path compression and union by rank for near-constant-time operations.
 *
 * @param <T> the element type
 */
final class UnionFind<T> {

    private final Map<T, T> parent = new HashMap<>();
    private final Map<T, Integer> rank = new HashMap<>();

    /**
     * Adds an element to the structure as its own singleton set, if not already present.
     *
     * @param element the element to add
     */
    void makeSet(T element) {
        parent.putIfAbsent(element, element);
        rank.putIfAbsent(element, 0);
    }

    /**
     * Finds the representative (root) of the set containing the given element.
     *
     * <p>Uses path compression to flatten the tree structure.
     *
     * @param element the element to find the root of
     * @return the representative element of the set
     */
    T find(T element) {
        T root = element;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        // Path compression
        T current = element;
        while (!current.equals(root)) {
            T next = parent.get(current);
            parent.put(current, root);
            current = next;
        }
        return root;
    }

    /**
     * Merges the sets containing the two given elements.
     *
     * <p>Uses union by rank to keep the tree balanced.
     *
     * @param a the first element
     * @param b the second element
     */
    void union(T a, T b) {
        T rootA = find(a);
        T rootB = find(b);
        if (rootA.equals(rootB)) {
            return;
        }
        int rankA = rank.get(rootA);
        int rankB = rank.get(rootB);
        if (rankA < rankB) {
            parent.put(rootA, rootB);
        } else if (rankA > rankB) {
            parent.put(rootB, rootA);
        } else {
            parent.put(rootB, rootA);
            rank.put(rootA, rankA + 1);
        }
    }

    /**
     * Returns all elements in the structure.
     *
     * @return the element set
     */
    Set<T> elements() {
        return parent.keySet();
    }

    /**
     * Returns the connected components (clusters) as a map from representative to members.
     *
     * @return clusters keyed by representative element
     */
    Map<T, List<T>> clusters() {
        Map<T, List<T>> result = new LinkedHashMap<>();
        for (T element : parent.keySet()) {
            T root = find(element);
            result.computeIfAbsent(root, k -> new ArrayList<>()).add(element);
        }
        return result;
    }
}
