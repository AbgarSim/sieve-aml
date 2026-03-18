Docker
======

Sieve ships with a ``Dockerfile`` and ``docker-compose.yml`` for easy
containerized deployment with PostgreSQL persistence.

Docker Compose (recommended)
----------------------------

.. code-block:: bash

   docker compose up --build

This starts:

- **sieve-server** on port ``8080``
- **PostgreSQL 16** on port ``5432`` (data persisted in a Docker volume)

The server automatically imports sanctions lists on first startup when the
database is empty.

Standalone Docker
-----------------

Build and run without Compose (in-memory mode, no PostgreSQL):

.. code-block:: bash

   docker build -t sieve-aml .
   docker run -p 8080:8080 sieve-aml

Environment Variables
---------------------

.. list-table::
   :header-rows: 1
   :widths: 35 15 50

   * - Variable
     - Default
     - Description
   * - ``SPRING_PROFILES_ACTIVE``
     - (none)
     - Set to ``postgres`` to enable PostgreSQL persistence
   * - ``SPRING_DATASOURCE_URL``
     - —
     - JDBC URL for PostgreSQL (e.g., ``jdbc:postgresql://db:5432/sieve``)
   * - ``SPRING_DATASOURCE_USERNAME``
     - —
     - Database username
   * - ``SPRING_DATASOURCE_PASSWORD``
     - —
     - Database password
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
