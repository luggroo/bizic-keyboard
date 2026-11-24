#!/usr/bin/env bash
set -euo pipefail
status=0
for suite in merged-smoke hand-words host; do
    echo "::group::$suite"
    if TUJIA_E2E_SUITE="$suite" bash qa/ci-device.sh; then
        echo "Mobile suite $suite PASS"
    else
        echo "Mobile suite $suite FAIL"
        status=1
    fi
    mv build/device-qa "build/device-qa-$suite"
    echo "::endgroup::"
done
mkdir -p build/device-qa
mv build/device-qa-* build/device-qa/
exit "$status"
