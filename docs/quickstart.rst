Quick Start
===========

Get screening in under five minutes.

Dependencies
------------

**Maven**

.. code-block:: xml

   <dependency>
       <groupId>dev.sieve</groupId>
       <artifactId>sieve-core</artifactId>
       <version>0.1.0-SNAPSHOT</version>
   </dependency>
   <dependency>
       <groupId>dev.sieve</groupId>
       <artifactId>sieve-ingest</artifactId>
       <version>0.1.0-SNAPSHOT</version>
   </dependency>
   <dependency>
       <groupId>dev.sieve</groupId>
       <artifactId>sieve-match</artifactId>
       <version>0.1.0-SNAPSHOT</version>
   </dependency>

**Gradle**

.. code-block:: groovy

   implementation 'dev.sieve:sieve-core:0.1.0-SNAPSHOT'
   implementation 'dev.sieve:sieve-ingest:0.1.0-SNAPSHOT'
   implementation 'dev.sieve:sieve-match:0.1.0-SNAPSHOT'

Minimal Example
---------------

.. code-block:: java

   import dev.sieve.core.index.*;
   import dev.sieve.core.match.*;
   import dev.sieve.ingest.*;
   import dev.sieve.ingest.ofac.OfacSdnProvider;
   import dev.sieve.match.*;
   import java.util.List;

   public class QuickScreen {
       public static void main(String[] args) {
           EntityIndex index = new InMemoryEntityIndex();
           ListProvider ofac = new OfacSdnProvider();
           new IngestionOrchestrator(List.of(ofac)).ingest(index);

           MatchEngine engine = new CompositeMatchEngine(List.of(
               new ExactMatchEngine(new NormalizedNameCache(), new NgramIndex()),
               new FuzzyMatchEngine(new NormalizedNameCache(), new NgramIndex())
           ));

           ScreeningRequest req = ScreeningRequest.of("John Doe", 0.80);
           List<MatchResult> hits = engine.screen(req, index);
           hits.forEach(m -> System.out.printf(
               "%s — %.2f (%s)%n",
               m.entity().primaryName().fullName(), m.score(), m.matchAlgorithm()));
       }
   }

What Just Happened
------------------

The ``IngestionOrchestrator`` fetched the OFAC SDN XML list over HTTPS, parsed it
via StAX streaming, and loaded normalized ``SanctionedEntity`` records into an
in-memory index. The ``CompositeMatchEngine`` then ran your query through exact and
fuzzy (Jaro-Winkler) engines in parallel, deduplicated results by entity, and
returned matches sorted by descending score.

.. tip::

   Add ``PhoneticMatchEngine`` and ``TokenMatchEngine`` to the engine list for
   transliteration and name-reordering coverage.

Next: :doc:`architecture` · :doc:`algorithms` · :doc:`screening-modes`
