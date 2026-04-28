Audit & Compliance
==================

Every screening decision produces an immutable audit record — including no-match outcomes.

What Gets Logged
----------------

.. list-table::
   :header-rows: 1
   :widths: 30 70

   * - Field
     - Description
   * - ``eventId``
     - UUID v4, unique per screening event
   * - ``screenedName``
     - The query name exactly as submitted
   * - ``threshold``
     - Match threshold applied
   * - ``matchCount``
     - Number of entities that exceeded the threshold
   * - ``topMatches``
     - List of ``(entityId, score, algorithm)`` tuples for the top results
   * - ``outcome``
     - ``MATCH``, ``NO_MATCH``, or ``ERROR``
   * - ``screenedAt``
     - ISO-8601 timestamp of the screening
   * - ``durationMs``
     - Wall-clock time for the screening operation

AuditEvent Record
-----------------

.. code-block:: java

   public record ScreeningAuditEvent(
       String eventId,
       String screenedName,
       double threshold,
       int matchCount,
       List<AuditMatchEntry> topMatches,
       String outcome,
       Instant screenedAt,
       long durationMs
   ) {
       public record AuditMatchEntry(
           String entityId, double score, String algorithm) {}
   }

AuditEmitter SPI
----------------

The ``ScreeningAuditEmitter`` interface defines a single method:

.. code-block:: java

   public interface ScreeningAuditEmitter {
       void emit(ScreeningAuditEvent event);

       static ScreeningAuditEmitter logging() { /* SLF4J INFO */ }
       static ScreeningAuditEmitter noop()    { /* discard */ }
   }

**Registering a custom emitter:**

.. code-block:: java

   ScreeningAuditEmitter kafkaEmitter = event -> {
       producer.send(new ProducerRecord<>("audit-topic",
           event.eventId(), objectMapper.writeValueAsString(event)));
   };

   var handler = new ScreeningHandler(
       matchEngine, entityIndex, objectMapper, config, kafkaEmitter);

Built-in implementations:

- ``ScreeningAuditEmitter.logging()`` — writes each event as a structured SLF4J INFO message
- ``ScreeningAuditEmitter.noop()`` — discards events silently (for testing)

Immutability & Tamper Evidence
------------------------------

.. warning::

   ``ScreeningAuditEvent`` is a Java ``record`` — all fields are final and the
   instance is immutable after construction. For tamper-evident audit trails,
   persist events to an append-only store (Kafka topic with compaction disabled,
   write-once S3 bucket, or an immutable ledger database) and include a
   cryptographic hash chain if your compliance framework requires it.
