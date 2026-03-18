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

## JMeter — Max Throughput Test

Finds the system's **maximum sustainable req/sec** on `POST /api/v1/screen` using
[Apache JMeter](https://jmeter.apache.org/).

The test plan (`max-throughput-test.jmx`) runs a staircase load profile:

1. **Warm-up** — 10 threads for 10 s (JIT + cache stabilization)
2. **Staircase ramp** — 10 → 20 → 50 → 100 → 200 → 500 threads, each held for `stepDuration` seconds
3. **Sustained peak** — `peakThreads` threads held for `peakDuration` seconds

The key output is the **Throughput** column in the Aggregate Report — the step where throughput
stops climbing is the system's saturation point.

### Prerequisites

- Apache JMeter 5.6+ (`brew install jmeter` or [download](https://jmeter.apache.org/download_jmeter.cgi))
- A running Sieve server (Spring Boot or Vert.x)

### Running with the Script

```bash
# Defaults: localhost:8080, 30s steps, 1000 peak threads, 120s peak
./sieve-benchmark/run-jmeter.sh

# Custom target
./sieve-benchmark/run-jmeter.sh -Jhost=10.0.0.5 -Jport=8081

# Heavier load
./sieve-benchmark/run-jmeter.sh -JpeakThreads=2000 -JpeakDuration=300

# Longer steps
./sieve-benchmark/run-jmeter.sh -JstepDuration=60 -JpeakDuration=180
```

Results (raw `.jtl`, CSV summaries, and **HTML dashboard report**) are written to
`sieve-benchmark/results/<timestamp>/`.

### Running with Maven

```bash
mvn verify -pl sieve-benchmark -P jmeter

# Custom parameters
mvn verify -pl sieve-benchmark -P jmeter \
  -Djmeter.host=10.0.0.5 -Djmeter.port=8081 \
  -Djmeter.peakThreads=2000 -Djmeter.peakDuration=300
```

Maven results are written to `sieve-benchmark/target/jmeter/results/`.

### Running in JMeter GUI

```bash
jmeter -t sieve-benchmark/src/test/jmeter/max-throughput-test.jmx
```

### Configurable Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `host` | `localhost` | Target server hostname |
| `port` | `8080` | Target server port |
| `protocol` | `http` | HTTP or HTTPS |
| `stepDuration` | `30` | Duration of each staircase step (seconds) |
| `peakThreads` | `1000` | Thread count for the sustained peak phase |
| `peakDuration` | `120` | Duration of the sustained peak phase (seconds) |

### Comparing Spring Boot vs Vert.x

```bash
# Start both servers, then:
./sieve-benchmark/run-jmeter.sh -Jport=8080   # Spring Boot
./sieve-benchmark/run-jmeter.sh -Jport=8081   # Vert.x

# Compare the HTML reports in results/
```
