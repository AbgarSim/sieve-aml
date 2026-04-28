#!/usr/bin/env bash
# =============================================================================
# build-native.sh — Build the sieve_postal JNI shared library locally
#
# Prerequisites:
#   1. Install libpostal:
#        brew install curl autoconf automake libtool pkg-config  # macOS
#        git clone https://github.com/openvenues/libpostal
#        cd libpostal && ./bootstrap.sh
#        ./configure --datadir=/tmp/libpostal-data
#        make -j$(nproc) && sudo make install
#
#   2. (Optional) Install Senzing improved model v1.2.0:
#        curl -sL https://public-read-libpostal-data.s3.amazonaws.com/v1.2.0/parser.tar.gz \
#          | tar -xz -C /tmp/libpostal-data
#
#   3. Ensure JAVA_HOME is set (or auto-detected)
#
# Usage:
#   ./build-native.sh          # build the shared library
#   ./build-native.sh clean    # remove built artifacts
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="${SCRIPT_DIR}/src/main/native"

if [[ "${1:-}" == "clean" ]]; then
    make -C "$NATIVE_DIR" clean
    echo "Clean complete."
    exit 0
fi

echo "Building sieve_postal JNI native library..."
make -C "$NATIVE_DIR"

# Determine output library name
if [[ "$(uname -s)" == "Darwin" ]]; then
    LIB_NAME="libsieve_postal.dylib"
else
    LIB_NAME="libsieve_postal.so"
fi

echo ""
echo "Build complete: ${NATIVE_DIR}/${LIB_NAME}"
echo ""
echo "To use locally, run the server with:"
echo "  java -Djava.library.path=${NATIVE_DIR} -jar sieve-server/target/*.jar"
echo ""
echo "Or set the environment variable:"
echo "  export LD_LIBRARY_PATH=${NATIVE_DIR}  # Linux"
echo "  export DYLD_LIBRARY_PATH=${NATIVE_DIR}  # macOS"
