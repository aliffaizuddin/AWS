#!/usr/bin/env bash
# Offline unit tests for the chaos suite's pure helpers. No cluster needed.
set -euo pipefail

TEST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC2034 # read by the sourced test_*.sh files
CHAOS_DIR=$(cd "$TEST_DIR/.." && pwd)

PASSED=0
FAILED=0

# assert_eq <expected> <actual> <message>
assert_eq() {
  if [[ $1 == "$2" ]]; then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
    printf 'FAIL: %s\n  expected: %q\n  actual:   %q\n' "$3" "$1" "$2" >&2
  fi
}

# assert_contains <haystack> <needle> <message>
assert_contains() {
  if [[ $1 == *"$2"* ]]; then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
    printf 'FAIL: %s\n  missing: %s\n  in:\n%s\n' "$3" "$2" "$1" >&2
  fi
}

for f in "$TEST_DIR"/test_*.sh; do
  [[ -e $f ]] || continue
  # shellcheck source=/dev/null
  source "$f"
done

for t in $(declare -F | awk '{print $3}' | grep '^test_' || true); do
  "$t"
done

echo "passed: $PASSED, failed: $FAILED"
((FAILED == 0))
