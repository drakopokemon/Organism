#!/bin/bash
# Legacy entrypoint -> full chaos suite in deploy/chaos-test.sh
set -e
bash "$(dirname "$0")/../deploy/chaos-test.sh" "$@"
