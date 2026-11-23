#!/usr/bin/env bash
# Linux/macOS launcher: needs JDK 21+ on PATH
set -euo pipefail
cd "$(dirname "$0")"
exec java Launch.java "$@"
