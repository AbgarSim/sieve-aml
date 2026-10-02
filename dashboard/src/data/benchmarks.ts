// Published results from docs/performance/benchmarks.rst, measured with sieve-benchmark's HTTP
// stress test against the OFAC SDN list. Update both together.

export const BENCH = {
  source: 'docs/performance/benchmarks.rst',
  env: 'Apple Silicon (M-series) · 16 GB · Java 21 (Temurin) · OFAC SDN, ~20k entities, ~100k names',
  /** Single-thread latency and peak throughput per optimisation phase. */
  versions: [
    { v: 'v1', label: 'Baseline (linear scan)', ms: 590, rps: 4 },
    { v: 'v2', label: 'N-gram + name cache', ms: 12, rps: 435 },
    { v: 'v3', label: 'All optimisations', ms: 7.5, rps: 931 },
  ],
  /** v3 ramp-up: concurrency, req/s, p50 ms, p99 ms. */
  rampUp: [
    [1, 131, 7.2, 12.4],
    [10, 755, 11.5, 34.0],
    [50, 903, 39.0, 151.8],
    [100, 943, 80.7, 228.4],
    [200, 931, 119.8, 621.4],
  ] as [number, number, number, number][],
  /** v3 threshold sensitivity: match threshold, req/s, avg ms, p99 ms. */
  thresholds: [
    [0.7, 1042, 76.8, 292.8],
    [0.8, 894, 84.5, 364.8],
    [0.85, 899, 92.8, 379.9],
    [0.9, 1109, 71.1, 267.0],
  ] as [number, number, number, number][],
};
