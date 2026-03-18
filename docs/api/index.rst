REST API Reference
==================

Sieve exposes a RESTful API at ``/api/v1`` for sanctions screening, list
management, and health checks. All endpoints accept and return JSON.

**Base URL:** ``http://localhost:8080/api/v1``

**Swagger UI:** ``http://localhost:8080/swagger-ui.html`` (interactive docs
available when the server is running)

.. toctree::
   :maxdepth: 2

   screening
   lists
   health

Error Handling
--------------

All errors follow `RFC 7807 Problem Details <https://www.rfc-editor.org/rfc/rfc7807>`_:

.. code-block:: json

   {
     "type": "about:blank",
     "title": "Bad Request",
     "status": 400,
     "detail": "Name must not be blank",
     "instance": "/api/v1/screen"
   }

Common HTTP Status Codes
------------------------

.. list-table::
   :header-rows: 1
   :widths: 15 85

   * - Code
     - Meaning
   * - ``200``
     - Request succeeded
   * - ``400``
     - Invalid request parameters (validation failure)
   * - ``500``
     - Internal server error
