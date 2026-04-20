Name Normalization
==================

Name normalization is the first step in the matching pipeline. It transforms
raw name strings into a canonical form so that comparisons are consistent
regardless of casing, spacing, or formatting differences.

What Normalization Does
-----------------------

.. code-block:: text

   Input:  "  Vladimir   PUTIN  "
   Output: "vladimir putin"

Three operations are applied in sequence:

1. **Strip** — remove leading and trailing whitespace
2. **Lowercase** — convert to lowercase (locale-independent)
3. **Collapse whitespace** — replace sequences of whitespace with a single space

Implementation
--------------

.. code-block:: java

   // NameNormalizer.java
   private static final Pattern WHITESPACE = Pattern.compile("\\s+");

   static String normalize(String name) {
       if (name == null || name.isBlank()) {
           return "";
       }
       return WHITESPACE.matcher(name.strip().toLowerCase()).replaceAll(" ");
   }

Key design decisions:

- **Pre-compiled regex** — the ``\s+`` pattern is compiled once as a static
  field, avoiding recompilation overhead on every call.
- **Memoized results** — a ``ConcurrentHashMap`` caches up to 100,000
  normalized strings. Entity names are cached at index load time; query
  strings benefit from cache hits on repeated screening.
- **Null-safe** — null or blank inputs return an empty string, never ``null``.

Pre-normalization Cache
-----------------------

At index load time, the ``NormalizedNameCache`` pre-computes and stores the
normalized form of every entity's primary name and aliases:

.. mermaid::

   flowchart LR
       L["Index Load"] --> C["NormalizedNameCache"]
       C --> P["primaryName → normalized"]
       C --> A["aliases[] → normalized[]"]
       Q["Query time"] -->|"cache.get(entity)"| R["instant lookup"]

This eliminates ~100,000 string allocations and regex operations per query
that were previously needed to normalize every entity name on every request.

.. code-block:: java

   // At query time — zero normalization cost per entity
   NormalizedNameCache.NormalizedEntry cached = nameCache.get(entity);
   double score = JaroWinkler.similarity(normalizedQuery, cached.primaryName());

Thread Safety
-------------

- ``NormalizedNameCache`` uses a ``ConcurrentHashMap`` for thread-safe
  concurrent reads.
- The cache rebuilds automatically (with double-checked locking) when the
  index size changes, e.g., after a list refresh.
- ``NameNormalizer``'s static cache is also a ``ConcurrentHashMap`` with a
  bounded size to prevent unbounded memory growth.
