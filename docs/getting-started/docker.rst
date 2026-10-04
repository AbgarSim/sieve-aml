Docker
======

Sieve ships with a ``Dockerfile`` and ``docker-compose.yml`` for easy
containerized deployment with PostgreSQL persistence.

Docker Compose (recommended)
----------------------------

.. code-block:: bash

   docker compose up --build

This starts:

- **sieve-spring**, the Spring server, on port ``8081``, with PostgreSQL as its system of record
- **sieve-server**, the in-memory Vert.x server, on port ``8080``
- **PostgreSQL 16** on port ``5432`` (data persisted in a Docker volume)

The Spring server builds its screening index from the database at startup and
imports the sanctions lists when the database is empty.

Standalone Docker
-----------------

Build and run without Compose, pointing the server at an existing PostgreSQL
database (it is required):

.. code-block:: bash

   docker build -t sieve-aml .
   docker run -p 8080:8080 -e POSTGRES_HOST=db.example.org -e POSTGRES_PASSWORD=... sieve-aml

Environment Variables
---------------------

.. list-table::
   :header-rows: 1
   :widths: 35 15 50

   * - Variable
     - Default
     - Description
   * - ``POSTGRES_HOST`` / ``POSTGRES_PORT`` / ``POSTGRES_DB``
     - ``localhost`` / ``5432`` / ``sieve``
     - Where the PostgreSQL system of record runs
   * - ``POSTGRES_USER`` / ``POSTGRES_PASSWORD``
     - ``sieve`` / ``sieve``
     - Database credentials
   * - ``SPRING_DATASOURCE_URL``
     - —
     - Full JDBC URL, overriding the ``POSTGRES_*`` variables
   * - ``SIEVE_LISTS_OFAC_SDN_ENABLED``
     - ``true``
     - Enable/disable OFAC SDN list
   * - ``SIEVE_SCREENING_DEFAULT_THRESHOLD``
     - ``0.80``
     - Default match score threshold

Verify
------

.. code-block:: bash

   # Health check
   curl http://localhost:8080/api/v1/health

   # Screen a name
   curl -X POST http://localhost:8080/api/v1/screen \
     -H "Content-Type: application/json" \
     -d '{"name": "Vladimir Putin"}'
