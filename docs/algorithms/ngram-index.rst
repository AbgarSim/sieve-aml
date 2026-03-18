N-gram Inverted Index
=====================

The n-gram index is Sieve's primary candidate selection mechanism. It reduces
the search space from tens of thousands of entities to a small set of
plausible candidates before running the expensive Jaro-Winkler comparison.

.. contents:: On this page
   :local:
   :depth: 2

How It Works
------------

**Build phase** (at index load time):

1. Every entity's normalized primary name and aliases are decomposed into
   overlapping 3-character **trigrams**.
2. An inverted map is built: ``trigram → [entity IDs]``.

**Query phase** (per screening request):

1. The normalized query is decomposed into trigrams.
2. Entity IDs are collected by trigram overlap.
3. Entities sharing ≥30% of query trigrams pass the filter.
4. A length-ratio check eliminates entities whose names differ from the
   query by more than 3×.

Trigram Decomposition
---------------------

A string is split into all overlapping substrings of length 3:

.. code-block:: text

   "putin"      → {"put", "uti", "tin"}
   "vladimir"   → {"vla", "lad", "adi", "dim", "imi", "mir"}

For the full name ``"vladimir putin"``:

.. code-block:: text

   → {"vla", "lad", "adi", "dim", "imi", "mir", "ir ", "r p", " pu", "put", "uti", "tin"}

Strings shorter than 3 characters produce no trigrams and fall back to a
full scan (this is rare for real names).

Inverted Index Structure
------------------------

.. mermaid::

   flowchart LR
       subgraph "Inverted Index"
           T1["'put'"] --> E1["entity-123, entity-456"]
           T2["'uti'"] --> E2["entity-123, entity-789"]
           T3["'vla'"] --> E3["entity-123"]
           T4["'mic'"] --> E4["entity-999, entity-888"]
       end

At query time, trigram lookups are O(1) hash map reads. The engine counts
how many trigrams each entity shares with the query:

.. code-block:: text

   Query: "putin"  →  trigrams: {"put", "uti", "tin"}

   entity-123: hit on "put" + "uti"        → 2/3 = 67% overlap ✅
   entity-456: hit on "put"                → 1/3 = 33% overlap ✅
   entity-789: hit on "uti"                → 1/3 = 33% overlap ✅
   entity-999: no hits                     → 0/3 = 0%  overlap ❌

Filtering Criteria
------------------

Two filters reduce the candidate set:

**1. Minimum trigram overlap (30%)**

An entity must share at least 30% of the query's trigrams to be considered
a candidate. This ratio is tuned to cast a wide enough net for fuzzy matches
while eliminating clearly unrelated entities.

.. code-block:: java

   int minHits = Math.max(1, (int) (queryTrigrams.size() * 0.3));

**2. Length ratio filter (3×)**

If the query and the candidate's shortest/longest name differ by more than
a factor of 3, the entity is skipped. This is because Jaro-Winkler cannot
produce high scores when string lengths diverge significantly.

.. code-block:: java

   double ratio = (double) Math.max(queryLen, longest)
                / Math.min(queryLen, shortest);
   if (ratio > 3.0) continue;  // skip — can't produce high JW score

Effectiveness
-------------

For a typical index of ~20,000 sanctioned entities:

.. list-table::
   :header-rows: 1
   :widths: 40 30 30

   * - Metric
     - Without n-gram
     - With n-gram
   * - Candidates per query
     - 20,000
     - ~50–200
   * - Jaro-Winkler comparisons
     - ~100,000
     - ~250–1,000
   * - Query latency
     - ~150ms
     - ~0.4ms
   * - Reduction factor
     - —
     - **99.5–99.75%**

The n-gram filter eliminates 99%+ of entities that have no character-level
similarity to the query, while preserving all entities that could plausibly
match at or above the configured threshold.

Thread Safety
-------------

- The index rebuilds atomically under a synchronized lock with double-checked
  locking on the index size.
- Volatile references to the trigram map and entity map ensure safe publication
  across threads.
- Query-time reads are lock-free.

Why Trigrams?
-------------

- **Bigrams** (n=2) produce too many false positives — common letter pairs
  like "an", "er", "in" appear in most names.
- **4-grams** are too specific and miss legitimate fuzzy matches where
  characters are transposed or substituted.
- **Trigrams** strike the right balance for name matching in sanctions
  screening.
