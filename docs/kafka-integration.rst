Kafka Integration
=================

.. note::

   **Planned, not implemented.** This page is a design for a Kafka Streams screening pipeline.
   No Kafka code ships in Sieve yet; screening runs through the REST API, the CLI and the Java
   modules. The design is kept here as the target for a later release.

Real-time screening pipeline using Kafka Streams for high-throughput event-driven architectures.

Topology
--------

.. graphviz::

   digraph kafka_topology {
       rankdir=LR;
       node [shape=box, style="rounded,filled", fontname="Helvetica", fontsize=10];
       edge [fontsize=9];

       input   [label="screening-requests\n(input topic)", fillcolor="#e3f2fd"];
       proc    [label="ScreeningProcessor\n(stateless transform)", fillcolor="#fff3e0"];
       results [label="match-results\n(output topic)", fillcolor="#e8f5e9"];
       audit   [label="audit-events\n(output topic)", fillcolor="#fce4ec"];
       dlq     [label="screening-dlq\n(dead-letter topic)", fillcolor="#ffebee"];

       input -> proc;
       proc  -> results [label="matches"];
       proc  -> audit   [label="audit events"];
       proc  -> dlq     [label="malformed", style=dashed];
   }

Consumer Configuration
----------------------

.. code-block:: yaml

   sieve:
     kafka:
       bootstrap-servers: localhost:9092
       input-topic: screening-requests
       output-topic: match-results
       audit-topic: audit-events
       dlq-topic: screening-dlq
       consumer-group: sieve-screener
       auto-offset-reset: earliest
       max-poll-records: 500

.. warning::

   Enable ``processing.guarantee: exactly_once_v2`` in Kafka Streams config
   to ensure each screening request is processed exactly once. Without this,
   duplicate audit records may be emitted during consumer rebalances.

Dead-Letter Topic
-----------------

Records that fail deserialization or violate schema constraints (e.g., missing
``name`` field) are routed to the DLQ topic with the original payload and an
error header. Monitor the DLQ for integration issues and malformed upstream data.

.. tip::

   Set ``dlq-topic`` retention to 7 days and configure an alert on the DLQ
   consumer lag to catch upstream schema drift early.
