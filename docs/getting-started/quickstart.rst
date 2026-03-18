Quickstart
==========

Prerequisites
-------------

- **Java 21+** — `Eclipse Temurin <https://adoptium.net/>`_ recommended
- **Maven 3.9+** — `Download <https://maven.apache.org/download.cgi>`_

Build from Source
-----------------

.. code-block:: bash

   git clone https://github.com/your-org/sieve-aml.git
   cd sieve-aml
   mvn clean verify

This produces executable JARs for the server and CLI:

- ``sieve-server/target/sieve-server-0.1.0-SNAPSHOT.jar``
- ``sieve-cli/target/sieve-cli-0.1.0-SNAPSHOT.jar``

Start the Server
----------------

.. code-block:: bash

   java -jar sieve-server/target/sieve-server-0.1.0-SNAPSHOT.jar

The server starts on port **8080** and automatically fetches OFAC SDN data on
first startup. Once loaded, the API is ready:

.. code-block:: bash

   curl -X POST http://localhost:8080/api/v1/screen \
     -H "Content-Type: application/json" \
     -d '{"name": "Vladimir Putin", "threshold": 0.80}'

Swagger UI is available at ``http://localhost:8080/swagger-ui.html``.

Screen via CLI
--------------

.. code-block:: bash

   java -jar sieve-cli/target/sieve-cli-0.1.0-SNAPSHOT.jar screen "John Doe"

The CLI auto-fetches lists if the index is empty. Exit codes:

- ``0`` — no match found
- ``1`` — match found (useful for CI/CD pipelines)
- ``2`` — error

Next Steps
----------

- :doc:`docker` — run Sieve with Docker Compose (includes PostgreSQL)
- :doc:`configuration` — customize lists, thresholds, and storage
- :doc:`/api/index` — full REST API reference
