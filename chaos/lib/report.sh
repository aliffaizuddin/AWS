# shellcheck shell=bash
# Results/metrics recording and Markdown report rendering. Pure file I/O
# under $RUN_DIR — unit-tested offline. Requires kv (lib/probe.sh).
#
#   $RUN_DIR/results.tsv  scenario<TAB>check<TAB>PASS|FAIL|WARN|INFO<TAB>detail
#   $RUN_DIR/metrics.tsv  scenario<TAB>key<TAB>value
#   $RUN_DIR/keys         S3 object keys created this run (for teardown)

init_run_dir() {
  mkdir -p "$RUN_DIR"
  : > "$RUN_DIR/results.tsv"
  : > "$RUN_DIR/metrics.tsv"
  : > "$RUN_DIR/keys"
}

# Tabs and newlines in the detail are flattened so every result stays one TSV row.
record_result() {
  local detail=${4//$'\n'/ }
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${detail//$'\t'/ }" >> "$RUN_DIR/results.tsv"
}
record_metric() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$RUN_DIR/metrics.tsv"; }

# metric <scenario> <key> — last recorded value, or empty.
metric() { awk -F'\t' -v s="$1" -v k="$2" '$1 == s && $2 == k { v = $3 } END { print v }' "$RUN_DIR/metrics.tsv"; }

# scenario_status <scenario> — worst non-INFO result, or NOT RUN.
scenario_status() {
  awk -F'\t' -v s="$1" '
    $1 == s && $3 != "INFO" { n++; if ($3 == "FAIL") f = 1; else if ($3 == "WARN") w = 1 }
    END { print (n == 0 ? "NOT RUN" : f ? "FAIL" : w ? "WARN" : "PASS") }' "$RUN_DIR/results.tsv"
}

# overall_exit_code <aborted:0|1>
overall_exit_code() {
  if (($1)); then
    echo 2
  elif awk -F'\t' '$3 == "FAIL" { f = 1 } END { exit !f }' "$RUN_DIR/results.tsv"; then
    echo 1
  else
    echo 0
  fi
}

# check_recovery <scenario> <parse_probe output> <limit_s> <label>
check_recovery() {
  local id=$1 parsed=$2 limit_s=$3 label=$4 rec
  if [[ $(kv samples <<<"$parsed") == 0 ]]; then
    record_result "$id" "recovery-$label" FAIL "the $label probe produced no samples after the kill (probe never ran)"
    return 0
  fi
  if [[ $(kv outage_seen <<<"$parsed") == no ]]; then
    record_result "$id" "recovery-$label" WARN "no outage observed for $label after the kill"
    return 0
  fi
  if [[ $(kv recovered <<<"$parsed") != yes ]]; then
    record_result "$id" "recovery-$label" FAIL "$label did not recover within the probe timeout"
    return 0
  fi
  rec=$(kv recovery_ms <<<"$parsed")
  record_metric "$id" "recovery_ms_$label" "$rec"
  if ((rec <= limit_s * 1000)); then
    record_result "$id" "recovery-$label" PASS "$label recovered in $(fmt_secs "$rec") (limit ${limit_s}s)"
  else
    record_result "$id" "recovery-$label" FAIL "$label recovered in $(fmt_secs "$rec"), over the ${limit_s}s limit"
  fi
}

# Probe-timing skew tolerated between the S3 and IAM probe loops, which
# sample independently every 250ms.
CHAOS_FAIL_CLOSED_SKEW_MS=${CHAOS_FAIL_CLOSED_SKEW_MS:-500}

# The in-pod probe's per-request curl --max-time, in ms.
CHAOS_PROBE_TIMEOUT_MS=${CHAOS_PROBE_TIMEOUT_MS:-2000}

# check_fail_closed <scenario> <s3 parse_probe output> <iam parse_probe output>
# S3 must not serve a 2xx while IAM is observed down. "Observed down" means a
# fast failure from the IAM probe (see down_until_ms in parse_probe).
check_fail_closed() {
  local id=$1 s3=$2 iam=$3 flap s3_rec iam_down iam_rec bound=""
  flap=$(kv flap_2xx <<<"$s3")
  iam_down=$(kv down_until_ms <<<"$iam")
  iam_rec=$(kv recovery_ms <<<"$iam")
  # IAM was down at least until its last fast refusal, and at least until one
  # probe timeout before its first stable 2xx (that sample may have hung).
  [[ -n $iam_down && $iam_down != - ]] && bound=$iam_down
  if [[ $(kv recovered <<<"$iam") == yes && -n $iam_rec && $iam_rec != - ]]; then
    local from_recovery=$((iam_rec - CHAOS_PROBE_TIMEOUT_MS))
    if [[ -z $bound ]] || ((from_recovery > bound)); then bound=$from_recovery; fi
  fi
  if [[ $(kv samples <<<"$s3") == 0 ]]; then
    record_result "$id" fail-closed FAIL "the S3 probe produced no samples after the kill"
  elif [[ $(kv outage_seen <<<"$s3") == no ]]; then
    record_result "$id" fail-closed FAIL "S3 kept answering 2xx after IAM was killed (possible fail-open)"
  elif ((flap > 0)); then
    record_result "$id" fail-closed FAIL "$flap 2xx response(s) between the first error and stable recovery"
  elif [[ $(kv recovered <<<"$s3") != yes ]]; then
    record_result "$id" fail-closed PASS "no 2xx after the first error (S3 did not recover within the probe)"
  elif [[ -z $bound ]]; then
    record_result "$id" fail-closed WARN "IAM was neither seen refusing nor seen recovering, so S3's recovery can't be ordered against it"
  else
    s3_rec=$(kv recovery_ms <<<"$s3")
    if ((s3_rec + CHAOS_FAIL_CLOSED_SKEW_MS < bound)); then
      record_result "$id" fail-closed FAIL "S3 served stable 2xx at $(fmt_secs "$s3_rec") while IAM was still down until at least $(fmt_secs "$bound") (fail-open)"
    else
      record_result "$id" fail-closed PASS "no 2xx while IAM was known down (until at least $(fmt_secs "$bound")); S3 back at $(fmt_secs "$s3_rec")"
    fi
  fi
}

fmt_secs() { if [[ $1 == - ]]; then echo -; else awk -v ms="$1" 'BEGIN { printf "%.1fs\n", ms / 1000 }'; fi; }
fmt_utc() { date -u -d "@$(($1 / 1000))" '+%Y-%m-%d %H:%M:%S'; }

md_escape() {
  local s=${1//|/\\|}
  echo "${s//$'\n'/ }"
}

meta_get() { sed -n "s/^$2=//p" "$1" | tail -n1; }

# recovery_summary <scenario> — "s3 41.3s, iam 9.1s", or -.
recovery_summary() {
  local parts=() key val out
  while IFS=$'\t' read -r key val; do
    parts+=("${key#recovery_ms_} $(fmt_secs "$val")")
  done < <(awk -F'\t' -v s="$1" '$1 == s && $2 ~ /^recovery_ms_/ { print $2 "\t" $3 }' "$RUN_DIR/metrics.tsv")
  if ((${#parts[@]} == 0)); then
    echo -
  else
    out=$(printf '%s, ' "${parts[@]}")
    echo "${out%, }"
  fi
}

# render_report <meta_file> <scenario ids...>
render_report() {
  local meta=$1 id title start end window_utc window_ms check status detail
  shift
  echo "# Chaos run $(meta_get "$meta" run_id)"
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| Context | \`$(meta_get "$meta" context)\` |"
  echo "| Git SHA | \`$(meta_get "$meta" git_sha)\` |"
  echo "| Started | $(meta_get "$meta" started_utc) |"
  echo "| Images | s3 \`$(meta_get "$meta" image_s3)\`, iam \`$(meta_get "$meta" image_iam)\`, postgres \`$(meta_get "$meta" image_postgres)\` |"
  echo
  if [[ $(meta_get "$meta" aborted) == yes ]]; then
    echo "**Run aborted** — the stack did not return to healthy after a scenario; later scenarios did not run."
    echo
  fi
  echo "## Summary"
  echo
  echo "| Scenario | Result | Recovery | Window (UTC) | Window (epoch ms) |"
  echo "|---|---|---|---|---|"
  for id in "$@"; do
    title=$(metric "$id" title)
    start=$(metric "$id" window_start_ms)
    end=$(metric "$id" window_end_ms)
    if [[ -n $start && -n $end ]]; then
      window_utc="$(fmt_utc "$start") → $(fmt_utc "$end")"
      window_ms="$start–$end"
    else
      window_utc=-
      window_ms=-
    fi
    echo "| $id${title:+ $title} | $(scenario_status "$id") | $(recovery_summary "$id") | $window_utc | $window_ms |"
  done
  for id in "$@"; do
    title=$(metric "$id" title)
    echo
    echo "## $id${title:+ $title}"
    echo
    echo "| Check | Result | Detail |"
    echo "|---|---|---|"
    while IFS=$'\t' read -r check status detail; do
      echo "| $check | $status | $(md_escape "$detail") |"
    done < <(awk -F'\t' -v s="$id" '$1 == s { print $2 "\t" $3 "\t" $4 }' "$RUN_DIR/results.tsv")
  done
}
