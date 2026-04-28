#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# Sieve AML — JMeter benchmark runner
#
# Prerequisites:
#   - Apache JMeter 5.6+ installed and on PATH (or set JMETER_HOME)
#   - A Sieve server running on the target host:port
#
# Usage:
#   ./run-jmeter.sh                                              # defaults (localhost:8080)
#   ./run-jmeter.sh -Jhost=10.0.0.5 -Jport=8081                 # custom target
#   ./run-jmeter.sh -JpeakThreads=2000 -JpeakDuration=300        # heavier load
#   ./run-jmeter.sh -JstepDuration=60 -JpeakDuration=180         # longer steps
#
# Results are written to sieve-benchmark/results/<timestamp>/
# ─────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JMETER_DIR="${SCRIPT_DIR}/src/test/jmeter"

# Locate JMeter binary
if [[ -n "${JMETER_HOME:-}" ]]; then
    JMETER="${JMETER_HOME}/bin/jmeter"
elif command -v jmeter &>/dev/null; then
    JMETER="jmeter"
else
    echo "ERROR: JMeter not found. Install it or set JMETER_HOME."
    echo "  brew install jmeter          # macOS"
    echo "  apt-get install jmeter       # Debian/Ubuntu"
    echo "  https://jmeter.apache.org/download_jmeter.cgi"
    exit 1
fi

JMX_FILE="${JMETER_DIR}/max-throughput-test.jmx"

if [[ ! -f "${JMX_FILE}" ]]; then
    echo "ERROR: Test plan not found: ${JMX_FILE}"
    exit 1
fi

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULTS_DIR="${SCRIPT_DIR}/results/${TIMESTAMP}"
mkdir -p "${RESULTS_DIR}"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Sieve AML — Max Throughput Test"
echo "  Plan:    ${JMX_FILE}"
echo "  Results: ${RESULTS_DIR}/"
echo "═══════════════════════════════════════════════════════════════"
echo ""

"${JMETER}" -n \
    -t "${JMX_FILE}" \
    -JresultsDir="${RESULTS_DIR}" \
    -l "${RESULTS_DIR}/raw.jtl" \
    -j "${RESULTS_DIR}/jmeter.log" \
    -e -o "${RESULTS_DIR}/report" \
    "$@"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  ✓ Complete"
echo "    Raw results:  ${RESULTS_DIR}/raw.jtl"
echo "    HTML report:  ${RESULTS_DIR}/report/index.html"
echo "═══════════════════════════════════════════════════════════════"
echo ""
