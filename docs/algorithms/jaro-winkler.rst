Jaro-Winkler Similarity
=======================

Sieve uses the Jaro-Winkler string similarity algorithm for fuzzy name
matching. It is implemented from scratch in pure Java with no external
dependencies.

.. contents:: On this page
   :local:
   :depth: 2

Overview
--------

Jaro-Winkler produces a similarity score between **0.0** (no similarity) and
**1.0** (exact match). It is particularly well-suited for name matching because
it rewards strings that share a common prefix — a property that aligns with
how human names tend to vary (e.g., "Vladimir" vs "Vladimyr").

The algorithm consists of two phases:

1. **Jaro similarity** — counts matching characters and transpositions
2. **Winkler adjustment** — boosts the score for shared prefixes

Jaro Similarity
---------------

Given two strings *s₁* and *s₂*, the Jaro similarity is:

.. math::

   jaro = \frac{1}{3} \left( \frac{m}{|s_1|} + \frac{m}{|s_2|} + \frac{m - t}{m} \right)

Where:

- *m* = number of matching characters
- *t* = number of transpositions / 2
- Characters are considered matching if they are the same and within
  ⌊max(|s₁|, |s₂|) / 2⌋ - 1 positions of each other

**Example:**

.. code-block:: text

   s₁ = "putin"
   s₂ = "poutin"

   Match window = max(5, 6) / 2 - 1 = 2
   Matches: p-p, u-u, t-t, i-i, n-n → m = 5
   Transpositions: 0
   Jaro = (5/5 + 5/6 + 5/5) / 3 = 0.9444

Winkler Adjustment
------------------

The Winkler modification boosts the Jaro score for strings sharing a common
prefix (up to 4 characters):

.. math::

   jw = jaro + \ell \cdot p \cdot (1 - jaro)

Where:

- *ℓ* = length of common prefix (max 4)
- *p* = prefix scaling factor (default: 0.1)

**Example:**

.. code-block:: text

   "putin" vs "poutin"
   Common prefix: "p" → ℓ = 1
   JW = 0.9444 + 1 × 0.1 × (1 - 0.9444) = 0.9500

Threshold-Aware Early Exit
--------------------------

Sieve includes an optimized ``similarityWithThreshold()`` method that skips
the full computation when the result cannot possibly meet the threshold:

.. code-block:: java

   public static double similarityWithThreshold(String s1, String s2, double threshold) {
       // Upper bound: Jaro score <= (2 + min/max) / 3
       int shorter = Math.min(s1.length(), s2.length());
       int longer = Math.max(s1.length(), s2.length());
       double maxPossibleJaro = (2.0 + (double) shorter / longer) / 3.0;
       double maxPossible = maxPossibleJaro + (4 * 0.1 * (1.0 - maxPossibleJaro));
       if (maxPossible < threshold) {
           return 0.0;  // impossible to meet threshold — skip
       }
       return similarity(s1, s2);
   }

This eliminates unnecessary comparisons when string lengths diverge too much.
For example, ``"al"`` (length 2) vs ``"international corporation"`` (length 27)
has a maximum possible Jaro score of 0.358 — well below any practical
threshold.

Memory Optimization
-------------------

The standard Jaro algorithm allocates two boolean arrays per comparison to
track matched characters. At ~250 comparisons per query, this creates
significant GC pressure.

Sieve eliminates this by using **ThreadLocal reusable arrays**:

.. code-block:: java

   private static final ThreadLocal<boolean[]> TL_MATCHED_1 =
           ThreadLocal.withInitial(() -> new boolean[128]);

   static double jaro(String s1, String s2) {
       boolean[] s1Matched = borrowArray(TL_MATCHED_1, s1.length());
       boolean[] s2Matched = borrowArray(TL_MATCHED_2, s2.length());
       // ... compute matches and transpositions ...
       clearArray(s1Matched, s1.length());  // reset for reuse
       clearArray(s2Matched, s2.length());
   }

The arrays grow on demand but are never deallocated, achieving **zero
allocation** in the steady state.

Complexity
----------

.. list-table::
   :header-rows: 1
   :widths: 25 75

   * - Aspect
     - Complexity
   * - Time
     - O(n × w) where n = max string length, w = match window
   * - Space
     - O(n) — reused via ThreadLocal
   * - Typical cost
     - ~1–2µs per comparison for names of length 10–40

References
----------

- Winkler, W. E. (1990). "String Comparator Metrics and Enhanced Decision
  Rules in the Fellegi-Sunter Model of Record Linkage"
- `Jaro–Winkler distance (Wikipedia) <https://en.wikipedia.org/wiki/Jaro%E2%80%93Winkler_distance>`_
