Behavioural Risk Analysis
=========================

Screening tells you whether a *name* is on a list. Behavioural risk analysis
looks at what a customer or account *does*: it reads the subject's
transactions, looks for patterns that point to money laundering or sanctions
evasion, and returns a score from 0 to 100 with every rule that fired and what
it saw, so an analyst can check the reasoning against the transactions.

It lives in the ``sieve-risk`` module and is available from the CLI
(``sieve risk``), the Vert.x server (``POST /api/v1/risk/assess``), the Java
API (``BehaviouralRiskScorer``) and an Apache Beam pipeline (``sieve-beam``).

Rules
-----

.. list-table::
   :header-rows: 1
   :widths: 22 8 70

   * - Rule
     - Points
     - Fires when (defaults, all configurable through ``RiskConfig``)
   * - ``listed-counterparty``
     - 60
     - A counterparty's name matches a listed entity at or above the
       screening threshold (Sieve's exact and fuzzy engines).
   * - ``structuring``
     - 35
     - Three or more amounts from 90% of the reporting threshold to just under
       it (9,000 to 9,999.99 against 10,000) fall within 7 days.
   * - ``high-risk-jurisdiction``
     - 30
     - A counterparty is in a jurisdiction FATF names as subject to a call for
       action (KP, IR, MM).
   * - ``pass-through``
     - 25
     - At least 90% of an incoming amount is sent out again within 48 hours.
   * - ``velocity``
     - 20
     - Ten or more transactions fall within 24 hours.
   * - ``round-amounts``
     - 10
     - At least three amounts, and at least half of all amounts, are whole
       multiples of 1,000.

The score is the sum of the points of the rules that fired, capped at 100.
Levels: ``LOW`` below 30, ``MEDIUM`` from 30 to 59, ``HIGH`` from 60. Amounts
are compared in each transaction's own currency, so a subject's transactions
are expected to share one currency.

Transactions file
-----------------

The CLI and the Beam pipeline read CSV with this header::

   id,subject_id,counterparty,counterparty_country,amount,currency,timestamp,direction
   t1,acct-1,"DOE, John",GB,9500.00,USD,2026-10-01T09:30:00Z,in
   t2,acct-1,Grocer,,12.50,USD,2026-10-01T10:00:00Z,out

``counterparty_country`` is an ISO 3166-1 alpha-2 code and may be empty;
``timestamp`` is ISO 8601 in UTC; ``direction`` is ``in`` or ``out`` from the
subject's side. Fields holding commas are quoted.

CLI
---

.. code-block:: bash

   sieve risk transactions.csv                 # fetches lists, screens counterparties
   sieve risk transactions.csv --no-screening  # behaviour only, offline
   sieve risk transactions.csv --csv           # one CSV row per subject

Exit codes: ``0`` every subject is ``LOW``, ``1`` a subject is ``MEDIUM`` or
``HIGH``, ``2`` error.

REST API
--------

``POST /api/v1/risk/assess`` (Vert.x server) screens counterparties against the
lists the server has loaded::

   {"transactions": [
     {"id": "t1", "subjectId": "acct-1", "counterparty": "Grocer",
      "counterpartyCountry": "US", "amount": "84.12", "currency": "USD",
      "timestamp": "2026-10-01T09:00:00Z", "direction": "out"}
   ], "threshold": 0.85}

The response has one entry per subject with ``score``, ``level``,
``transactionCount`` and ``signals`` (``rule``, ``points``, ``detail``).

Apache Beam pipeline
--------------------

``sieve-beam`` runs the same screening and scoring as a batch pipeline, so a
large transaction history can be assessed on any Beam runner. The
``ScoreTransactions`` transform keys rows by subject, groups them, and scores
each subject; malformed rows are counted in the ``malformedRows`` metric and
skipped. ``TransactionRiskPipeline`` wraps it with file input and output and
runs on the direct runner by default:

.. code-block:: bash

   mvn -pl sieve-beam -am install -DskipTests
   mvn -pl sieve-beam dependency:build-classpath -Dmdep.outputFile=cp.txt
   java -cp "sieve-beam/target/sieve-beam-0.1.0-SNAPSHOT.jar:$(cat cp.txt)" \
       dev.sieve.beam.TransactionRiskPipeline \
       --input=transactions.csv --listedNames=names.txt --output=assessments

``--listedNames`` is a file of listed names, one per line, that counterparties
are screened against (for example names exported with ``sieve export``);
``--threshold`` sets the match threshold (default 0.85). The output is
``assessments.csv`` with the header
``subject_id,score,level,transactions,rules,details``.

``ScoreTransactionsTest`` runs the transform under Beam's ``TestPipeline`` and
checks the output with ``PAssert``.
