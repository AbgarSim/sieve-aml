Address Parsing & Matching
==========================

Screen entities by physical address using NLP-powered parsing and component-level fuzzy matching.

Why Address Matching Matters
----------------------------

OFAC guidance explicitly requires that compliance programs screen not just names but
also addresses, identifiers, and other entity attributes. Address matching catches
cases where a sanctioned entity operates at a known location under a different name.

LibPostal Integration
---------------------

Sieve uses a JNI bridge to `libpostal <https://github.com/openvenues/libpostal>`_ —
an open-source C library trained on OpenStreetMap data covering **99 languages** for
address parsing and normalization.

**Install the native library:**

.. code-block:: bash

   # macOS
   brew install libpostal

   # Ubuntu/Debian
   sudo apt-get install libpostal-dev

   # From source
   git clone https://github.com/openvenues/libpostal
   cd libpostal && ./bootstrap.sh && ./configure && make && sudo make install

When libpostal is unavailable, Sieve falls back to a **heuristic comma-splitting
parser** that assigns components positionally (last segment → country, second-to-last
→ city, remaining → street).

Parsing Pipeline
----------------

1. **Raw input** — free-text string, e.g., ``"123 Main Street, London, United Kingdom"``
2. **LibPostal parse** — extracts labeled components: ``house_number``, ``road``, ``city``, ``state``, ``postcode``, ``country``
3. **Component extraction** — maps libpostal labels to the ``Address`` domain model fields
4. **Normalization** — lowercases, strips whitespace; null-safe for missing components
5. **Component-level matching** — each query component is compared against entity address components using exact and containment scoring
6. **Composite address score** — weighted sum of component scores, normalized to query-present components only

Field Weights
-------------

.. list-table::
   :header-rows: 1

   * - Component
     - Weight
     - Rationale
   * - Country
     - 0.30
     - Most discriminative at scale
   * - City
     - 0.25
     - Strong geographic signal
   * - Street
     - 0.25
     - Precise location identifier
   * - Postal Code
     - 0.10
     - Useful but often missing
   * - State/Province
     - 0.10
     - Regional disambiguation

Configuration
-------------

.. code-block:: yaml

   sieve:
     address:
       libpostal-enabled: true
       fallback-strategy: heuristic   # "heuristic" | "none"

.. warning::

   Address screening alone is not sufficient for AML compliance. Always combine
   address results with name screening and manual review per your institution's
   risk-based approach.
