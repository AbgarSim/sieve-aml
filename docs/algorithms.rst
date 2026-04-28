Matching Algorithms
===================

Four engines run in parallel inside a composite pipeline; the best score per entity wins.

Exact Match
-----------

Normalized string equality after lowercasing, Unicode NFKD decomposition, and whitespace collapse.

**Use when** you need deterministic, zero-false-positive identity checks.
**Complexity:** O(n) per candidate (string compare).

.. list-table::
   :header-rows: 1

   * - Input A
     - Input B
     - Score
   * - ``john doe``
     - ``JOHN DOE``
     - 1.00
   * - ``john doe``
     - ``Jon Doe``
     - 0.00

*Implementation:* ``dev.sieve.match.ExactMatchEngine``

Jaro-Winkler
-------------

Primary fuzzy name similarity metric with a prefix bonus that rewards strings sharing a common opening.

**Use when** you need resilience to typos, minor spelling variations, and transliteration drift.
**Complexity:** O(max(m, n)) per pair.

.. list-table::
   :header-rows: 1

   * - Input A
     - Input B
     - Score
   * - ``Vladimir``
     - ``Vladmir``
     - 0.96
   * - ``Muhammad``
     - ``Mohamed``
     - 0.84

*Implementation:* ``dev.sieve.match.FuzzyMatchEngine`` backed by ``dev.sieve.match.algorithm.JaroWinkler``

Double Metaphone
----------------

Phonetic encoding that maps names to consonant-skeleton codes, catching transliteration and sounds-alike variants across scripts.

**Use when** screening names that may be romanized from Arabic, Cyrillic, or CJK scripts.
**Complexity:** O(n) per encoding.

.. list-table::
   :header-rows: 1

   * - Input A
     - Input B
     - Score
   * - ``Schmidt``
     - ``Smith``
     - 1.00 (phonetic)
   * - ``Qadhafi``
     - ``Gaddafi``
     - 1.00 (phonetic)

*Implementation:* ``dev.sieve.match.PhoneticMatchEngine`` backed by ``dev.sieve.match.algorithm.DoubleMetaphone``

Token Set Ratio
---------------

Order-independent token matching — splits names into token sets and scores by the best overlap, handling ``"LAST, First"`` vs ``"First Last"`` reorderings.

**Use when** names appear in varying order conventions across jurisdictions.
**Complexity:** O(k log k) where k = token count (dominated by sort).

.. list-table::
   :header-rows: 1

   * - Input A
     - Input B
     - Score
   * - ``John Smith``
     - ``SMITH, John``
     - 1.00
   * - ``Ali Abu Hassan``
     - ``Hassan Ali``
     - 0.67

*Implementation:* ``dev.sieve.match.TokenMatchEngine``

Composite / Weighted
--------------------

The ``CompositeMatchEngine`` aggregates results from all registered engines:

1. Each engine screens the query independently.
2. Results are merged by entity ID.
3. Per-entity, the **highest score** across all engines is kept (max aggregation).
4. The winning engine's algorithm name is recorded in ``matchAlgorithm``.

.. code-block:: java

   MatchEngine engine = new CompositeMatchEngine(List.of(
       new ExactMatchEngine(nameCache, ngramIndex),
       new FuzzyMatchEngine(nameCache, ngramIndex),
       new PhoneticMatchEngine(nameCache, ngramIndex),
       new TokenMatchEngine(nameCache, ngramIndex)
   ));

Score Aggregation
-----------------

The default aggregation strategy is **max-wins**: the highest score from any single
engine becomes the entity's final score. This avoids diluting a perfect exact match
with lower fuzzy scores.

To implement a custom strategy (e.g., weighted average per field), implement the
``MatchEngine`` interface and compose the individual engines inside your own
aggregation logic.

.. warning::

   Lowering the threshold below 0.70 significantly increases false positives.
   For production AML screening, regulators typically expect thresholds between
   0.80 and 0.90 with manual review of borderline cases.
