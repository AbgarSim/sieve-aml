Configuration
=============

Sieve is configured via ``application.yml`` (or environment variables following
Spring Boot's relaxed binding rules).

Sanctions Lists
---------------

Each list provider can be independently enabled, pointed at a custom URL, and
scheduled for automatic refresh.

.. code-block:: yaml

   sieve:
     lists:
       ofac-sdn:
         enabled: true
         url: https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML
         refresh-cron: "0 0 2 * * *"    # 2 AM daily
       eu-consolidated:
         enabled: false
         url: https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw
         refresh-cron: "0 0 3 * * *"
       un-consolidated:
         enabled: false
         url: https://scsanctions.un.org/resources/xml/en/consolidated.xml
         refresh-cron: "0 0 4 * * *"
       uk-hmt:
         enabled: false
         url: https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml
         refresh-cron: "0 0 5 * * *"

Screening
---------

.. code-block:: yaml

   sieve:
     screening:
       default-threshold: 0.80   # Score cutoff when client omits threshold
       max-results: 50           # Maximum matches returned per request

Index Storage
-------------

.. code-block:: yaml

   sieve:
     index:
       type: in-memory           # Default: ConcurrentHashMap-based index

   # For PostgreSQL persistence, activate the postgres profile:
   # SPRING_PROFILES_ACTIVE=postgres

Address Normalization
---------------------

.. code-block:: yaml

   sieve:
     address:
       libpostal-enabled: false          # Requires native libpostal installation
       libpostal-data-dir: /opt/libpostal-data

Server
------

.. code-block:: yaml

   server:
     port: 8080

   logging:
     level:
       dev.sieve: INFO
       root: WARN

All properties can be overridden via environment variables using Spring Boot's
relaxed binding. For example:

.. code-block:: bash

   SIEVE_LISTS_OFAC_SDN_ENABLED=true
   SIEVE_SCREENING_DEFAULT_THRESHOLD=0.85
   SERVER_PORT=9090
