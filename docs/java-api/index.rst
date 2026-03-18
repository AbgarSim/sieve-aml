Java API Reference
==================

Auto-generated API documentation from Javadoc comments using Doxygen and
Breathe.

.. note::

   This section is generated automatically from the source code. To rebuild,
   run ``doxygen Doxyfile`` from the project root, then ``make html`` from the
   ``docs/`` directory.

Core Domain Model
-----------------

.. doxygenclass:: dev::sieve::core::model::SanctionedEntity
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::core::model::NameInfo
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::core::model::Address
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::core::model::Identifier
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::core::model::SanctionsProgram
   :project: sieve
   :members:

Index
-----

.. doxygeninterface:: dev::sieve::core::index::EntityIndex
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::core::index::InMemoryEntityIndex
   :project: sieve
   :members:

Match Engine
------------

.. doxygeninterface:: dev::sieve::core::match::MatchEngine
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::core::match::MatchResult
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::core::match::ScreeningRequest
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::match::FuzzyMatchEngine
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::match::ExactMatchEngine
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::match::CompositeMatchEngine
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::match::NgramIndex
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::match::NormalizedNameCache
   :project: sieve
   :members:

Algorithms
----------

.. doxygenclass:: dev::sieve::match::algorithm::JaroWinkler
   :project: sieve
   :members:

Ingestion
---------

.. doxygeninterface:: dev::sieve::ingest::ListProvider
   :project: sieve
   :members:

.. doxygenclass:: dev::sieve::ingest::IngestionOrchestrator
   :project: sieve
   :members:
