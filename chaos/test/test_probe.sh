# shellcheck shell=bash
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/probe.sh
source "$CHAOS_DIR/lib/probe.sh"

_probe_log() { local f; f=$(mktemp); cat > "$f"; echo "$f"; }

test_parse_probe_clean_outage_and_recovery() {
  local f out
  f=$(_probe_log <<'LOG'
900 200
1000 200
1250 500
1500 500
1750 200
2000 200
2250 200
2500 200
2750 200
LOG
)
  out=$(parse_probe "$f" 1000)
  assert_eq yes "$(kv outage_seen <<<"$out")" "clean: outage seen"
  assert_eq 1250 "$(kv outage_start_ms <<<"$out")" "clean: outage start"
  assert_eq yes "$(kv recovered <<<"$out")" "clean: recovered"
  assert_eq 750 "$(kv recovery_ms <<<"$out")" "clean: recovery measured from kill to start of stable run"
  assert_eq 0 "$(kv flap_2xx <<<"$out")" "clean: no flapping"
  assert_eq 500 "$(kv outage_statuses <<<"$out")" "clean: statuses"
}

test_parse_probe_flapping_counts_short_2xx_runs() {
  local f out
  f=$(_probe_log <<'LOG'
1250 000
1500 200
1750 500
2000 200
2250 200
2500 200
2750 200
3000 200
LOG
)
  out=$(parse_probe "$f" 1000)
  assert_eq 1 "$(kv flap_2xx <<<"$out")" "flap: one 2xx inside the outage"
  assert_eq 1000 "$(kv recovery_ms <<<"$out")" "flap: recovery is the stable run, not the blip"
  assert_eq 000,500 "$(kv outage_statuses <<<"$out")" "flap: statuses in first-seen order"
}

test_parse_probe_never_recovers() {
  local f out
  f=$(_probe_log <<'LOG'
1250 500
1500 200
1750 200
LOG
)
  out=$(parse_probe "$f" 1000)
  assert_eq no "$(kv recovered <<<"$out")" "never: not recovered"
  assert_eq - "$(kv recovery_ms <<<"$out")" "never: no recovery time"
  assert_eq 0 "$(kv flap_2xx <<<"$out")" "never: trailing short run is not flapping"
}

test_parse_probe_no_outage() {
  local f out
  f=$(_probe_log <<'LOG'
900 500
1000 200
1250 200
LOG
)
  out=$(parse_probe "$f" 1000)
  assert_eq no "$(kv outage_seen <<<"$out")" "no-outage: errors before the kill are ignored"
  assert_eq - "$(kv recovery_ms <<<"$out")" "no-outage: no recovery time, never a clean 0"
  assert_eq - "$(kv recovered <<<"$out")" "no-outage: recovered is undefined"
  assert_eq 2 "$(kv samples <<<"$out")" "no-outage: samples counts valid lines after the kill"
}

test_parse_probe_empty_log() {
  local f out
  f=$(mktemp)
  out=$(parse_probe "$f" 1000)
  assert_eq no "$(kv outage_seen <<<"$out")" "empty: no outage seen"
  assert_eq 0 "$(kv samples <<<"$out")" "empty: zero samples"
}

test_parse_probe_ignores_garbage_lines() {
  local f out
  f=$(_probe_log <<'LOG'
Defaulted container "client" out of: client
1250 500

1500 200
1750 200
2000 200
2250 200
2500 200
LOG
)
  out=$(parse_probe "$f" 1000)
  assert_eq 1250 "$(kv outage_start_ms <<<"$out")" "garbage: ignored"
  assert_eq 500 "$(kv recovery_ms <<<"$out")" "garbage: recovery unaffected"
}

test_stamp_lines_prefixes_epoch_ms() {
  local out before after ts
  before=$(date +%s%3N)
  out=$(printf '200\n000\n' | stamp_lines)
  after=$(date +%s%3N)
  assert_eq 2 "$(wc -l <<<"$out" | tr -d ' ')" "stamp: one output line per input line"
  assert_eq "200" "$(head -n1 <<<"$out" | cut -d' ' -f2)" "stamp: status kept"
  ts=$(head -n1 <<<"$out" | cut -d' ' -f1)
  assert_eq yes "$( ((ts >= before && ts <= after)) && echo yes || echo no)" "stamp: epoch ms within the call window"
}
