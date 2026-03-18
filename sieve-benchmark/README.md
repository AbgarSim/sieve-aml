# Sieve Benchmark

Benchmarking and stress testing module for the Sieve AML screening platform.

## Build

```bash
mvn -B clean package -pl sieve-benchmark -am -DskipTests
```

This produces an executable uber-jar at `target/sieve-benchmark-0.1.0-SNAPSHOT.jar`.

## Usage

```bash
JAR=sieve-benchmark/target/sieve-benchmark-0.1.0-SNAPSHOT.jar

# Run all benchmarks (download + matching stress test)
java -jar $JAR

# Download speed benchmark only (all 4 providers, sequential + parallel)
java -jar $JAR --download

# Matching stress test only (fetches lists first, then benchmarks matching)
java -jar $JAR --match

# JMH microbenchmarks (synthetic data, reproducible, no network needed)
java -jar $JAR jmh
```

## Benchmark Modes

### Download Benchmark (`--download`)

Measures wall-clock time to fetch and parse all four sanctions lists:

- **OFAC SDN** — US Treasury sanctions list
- **UK HMT** — UK financial sanctions list
- **EU Consolidated** — EU sanctions list
- **UN Consolidated** — UN Security Council sanctions list

Runs each provider sequentially, then all four in parallel, and reports the speedup.

### Matching Stress Test (`--match`)

Loads all sanctions entities into an in-memory index and benchmarks the matching engine:

- **Single-threaded latency** — per-call latency (mean, P50, P99, max) for exact, fuzzy, and composite engines
- **Throughput vs threshold** — how match threshold affects queries/sec and result count
- **Concurrent stress test** — throughput and latency at 1, 4, 16, 64, and 256 concurrent virtual threads

### JMH Microbenchmarks (`jmh`)

Rigorous JMH-based microbenchmarks using synthetic data (no network dependency):

- Exact match throughput (single-threaded)
- Fuzzy match throughput (single-threaded)
- Composite match throughput (1, 4, 16 threads)
- Parameterized index sizes: 1K, 5K, 15K entities
