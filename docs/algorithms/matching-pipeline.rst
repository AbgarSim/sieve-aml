Matching Pipeline
=================

Sieve processes each screening query through a multi-stage pipeline that
balances accuracy with performance. The pipeline is designed to reduce the
search space at each stage, applying increasingly expensive comparisons only
to plausible candidates.

Pipeline Overview
-----------------

.. mermaid::

   flowchart LR
       Q["Query: 'Vladimir Putin'"] --> N["① Normalize"]
       N --> NG["② N-gram Lookup"]
       NG --> JW["③ Jaro-Winkler"]
       JW --> S["④ Sort & Return"]

       N -->|"'vladimir putin'"| NG
       NG -->|"~50 candidates"| JW
       JW -->|"8 matches"| S

       style Q fill:#f9f,stroke:#333
       style S fill:#9f9,stroke:#333

Stage Details
-------------

**① Normalization** (:doc:`normalization`)

The query string is normalized: lowercased, trimmed, and internal whitespace
is collapsed. This is done **once** per query. Entity names are pre-normalized
at index load time, eliminating redundant work.

.. code-block:: text

   "  Vladimir   PUTIN  " → "vladimir putin"

**② N-gram Candidate Selection** (:doc:`ngram-index`)

The normalized query is decomposed into overlapping 3-character trigrams.
These trigrams are looked up in an inverted index to find entities sharing
enough character sequences with the query. This reduces the candidate set
from tens of thousands to ~50 entities.

.. code-block:: text

   "vladimir putin" → {"vla","lad","adi","dim","imi","mir","ir ","r p"," pu","put","uti","tin"}
   Trigram lookup → 50 candidate entities

**③ Jaro-Winkler Similarity** (:doc:`jaro-winkler`)

Each candidate's pre-normalized primary name and aliases are compared against
the query using the Jaro-Winkler similarity algorithm. A threshold-aware
variant skips comparisons that cannot possibly meet the minimum score based
on string length ratios.

.. code-block:: text

   JaroWinkler("vladimir putin", "putin vladimir vladimirovich") → 0.9412
   JaroWinkler("vladimir putin", "microsoft corporation")       → skipped (length ratio)

**④ Sort & Return**

Results above the threshold are sorted by score (descending) and deduplicated
across match engines. The composite engine keeps the highest score per entity.

Dual Engine Architecture
------------------------

Sieve runs two engines in parallel through the ``CompositeMatchEngine``:

.. mermaid::

   flowchart TD
       Q["Query"] --> C["CompositeMatchEngine"]
       C --> E["ExactMatchEngine"]
       C --> F["FuzzyMatchEngine"]
       E -->|"score = 1.0"| D["Deduplicate by entity ID"]
       F -->|"score = 0.0–1.0"| D
       D -->|"keep highest score"| R["Sorted results"]

- **ExactMatchEngine** — normalized case-insensitive equality. If the query
  exactly matches a primary name or alias after normalization, it returns a
  score of 1.0.

- **FuzzyMatchEngine** — Jaro-Winkler similarity. Returns the highest score
  across the primary name and all aliases for each entity.

Both engines share the same ``NormalizedNameCache`` and ``NgramIndex``,
so the expensive pre-computation is done only once.

Performance Characteristics
---------------------------

.. list-table::
   :header-rows: 1
   :widths: 25 25 25 25

   * - Stage
     - Time Cost
     - Operations
     - Allocation
   * - Normalize query
     - ~500ns
     - 1 regex + toLower
     - cached
   * - N-gram lookup
     - ~5–10µs
     - hash lookups
     - ~50 entity refs
   * - Jaro-Winkler
     - ~375µs
     - ~250 comparisons
     - zero (ThreadLocal arrays)
   * - Sort results
     - ~100ns
     - O(k log k), k < 20
     - 1 ArrayList
   * - **Total**
     - **~400µs**
     -
     -
