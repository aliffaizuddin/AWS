# shellcheck shell=bash
# Probe-log parsing. Pure: no kubectl, no network — unit-tested offline.
#
# A probe log has one line per request: "<epoch_ms> <http_status>", where
# status 000 means curl got no HTTP response (refused or timed out).

# Consecutive 2xx responses required before a service counts as recovered.
CHAOS_STABLE_RUN=${CHAOS_STABLE_RUN:-5}

# parse_probe <raw_file> <kill_ms>
# Prints key=value lines:
#   samples          valid probe lines at or after kill_ms (0 = the probe never ran)
#   outage_seen      yes|no — any non-2xx at or after kill_ms
#   outage_start_ms  first non-2xx at or after kill_ms, or -
#   recovered        yes|no — a run of CHAOS_STABLE_RUN 2xx after the outage began; - if no outage
#   recovery_ms      kill_ms to the first request of that run, or -
#   flap_2xx         2xx after the outage began that were not part of the stable run
#   outage_statuses  distinct non-2xx statuses after kill_ms, first-seen order, or -
#   down_until_ms    kill_ms to the last *fast* failure before recovery, or -.
#                    A fast failure (refused, 5xx: answered within
#                    CHAOS_FAST_FAIL_MS of the previous sample) proves the
#                    service was down then; a timed-out sample proves nothing
#                    (e.g. a connection routed to a terminating pod).
CHAOS_FAST_FAIL_MS=${CHAOS_FAST_FAIL_MS:-1000}

parse_probe() {
  local raw=$1 kill_ms=$2
  awk -v kill="$kill_ms" -v need="$CHAOS_STABLE_RUN" -v fast="$CHAOS_FAST_FAIL_MS" '
    function is2xx(s) { return s ~ /^2[0-9][0-9]$/ }
    function note(s) { if (!(s in seen)) { seen[s] = 1; order = order (order == "" ? "" : ",") s } }
    NF != 2 || $1 !~ /^[0-9]+$/ { next }
    $1 + 0 < kill + 0 { prev = $1; next }
    {
      ts = $1; st = $2; samples++
      gap = (prev == "" ? 0 : ts - prev); prev = ts
      if (!recovered && !is2xx(st) && gap <= fast) { down_until = ts }
      if (!outage) {
        if (!is2xx(st)) { outage = 1; outage_start = ts; note(st) }
        next
      }
      if (recovered) next
      if (is2xx(st)) {
        if (run == 0) run_start = ts
        run++
        if (run >= need) { recovered = 1; recovery_point = run_start }
      } else {
        flap += run; run = 0; note(st)
      }
    }
    END {
      print "samples=" (samples + 0)
      if (!outage) {
        print "outage_seen=no"; print "outage_start_ms=-"; print "recovered=-"
        print "recovery_ms=-"; print "flap_2xx=0"; print "outage_statuses=-"
        print "down_until_ms=-"
        exit
      }
      print "outage_seen=yes"
      print "outage_start_ms=" outage_start
      print "recovered=" (recovered ? "yes" : "no")
      print "recovery_ms=" (recovered ? recovery_point - kill : "-")
      print "flap_2xx=" (flap + 0)
      print "outage_statuses=" order
      print "down_until_ms=" (down_until == "" ? "-" : down_until - kill)
    }' "$raw"
}

# kv <key> — read one value out of parse_probe output on stdin.
kv() { sed -n "s/^$1=//p"; }

# stamp_lines — prefix each stdin line with the host's epoch ms as it arrives.
# The probe loop runs in a busybox pod whose date has no sub-second support,
# so probe lines are timestamped here, on the same clock as the kill time.
stamp_lines() {
  local line t
  while IFS= read -r line; do
    t=${EPOCHREALTIME/./}
    echo "${t:0:-3} $line"
  done
}
