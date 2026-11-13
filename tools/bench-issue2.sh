#!/usr/bin/env bash
# Issue #2 experiment runner (E0-E5). One invocation = one run; raw output
# lands in docs/benchmarks/issue-2/raw/<label>.txt.
#
# Prereqs: docker compose up -d; mvn -q package -DskipTests;
#          javac -d target/bench tools/LoadDriver.java
#
#   tools/bench-issue2.sh app <label> <clients> <wallets> <timings:true|false> <sampler:yes|no>
#   tools/bench-issue2.sh pgbench <label>
#   tools/bench-issue2.sh sync <on|off>      # ALTER SYSTEM + reload, prints SHOW
#   tools/bench-issue2.sh reset              # end of session: empty stores, drop E5 sequence, verify
#
# Every app run: TRUNCATEs all service tables + FLUSHALL Redis (dev stores are
# disposable), starts the shaded jar, runs one LoadDriver level
# (warmup 10 s, measure MEASURE_SEC, default 60 s), records SHOW
# synchronous_commit before and after, stops the service.
# pgbench runs use the same MEASURE_SEC.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RAW="$ROOT/docs/benchmarks/issue-2/raw"
# JAR / JAR_REV: run a jar saved from another commit (E5 interleaves two
# builds). JAR_REV must then name the commit that jar was built from; it is
# what the raw file records instead of the checked-out HEAD.
JAR="${JAR:-$ROOT/target/fault-tolerant-payment-service-0.1.0-SNAPSHOT.jar}"
if [ -n "${JAR_REV:-}" ]; then
    REV_LINE="git: $JAR_REV (jar $(basename "$JAR"); checked out: $(git -C "$ROOT" rev-parse --short HEAD))"
else
    REV_LINE="git: $(git -C "$ROOT" rev-parse --short HEAD) $(git -C "$ROOT" rev-parse --abbrev-ref HEAD) (modified tracked files: $(git -C "$ROOT" status --porcelain --untracked-files=no | wc -l | tr -d ' '))"
fi
LOGDIR="$ROOT/target/bench-logs"
MEASURE_SEC="${MEASURE_SEC:-60}"
# sampler runs for the window minus a margin for `docker compose exec` startup,
# so every sample falls inside the measured window
SAMPLER_SEC=$(( MEASURE_SEC > 5 ? MEASURE_SEC - 3 : MEASURE_SEC - 1 ))
mkdir -p "$RAW" "$LOGDIR"

psql_db() { docker compose -f "$ROOT/docker-compose.yml" exec -T postgres psql -U user123 -d userdb "$@"; }
show_sync() { psql_db -At -c "SHOW synchronous_commit"; }

# Cumulative per-table autovacuum/autoanalyze counters (read-only, no config
# change). Snapshotted at MEASURE_START and MEASURE_END; the delta says whether
# autovacuum ran inside the measured window (its short runs are not logged).
av_counts() {
    psql_db -At -F' ' -c "SELECT relname, autovacuum_count, autoanalyze_count FROM pg_stat_user_tables ORDER BY relname"
}
av_delta() { # $1 = start snapshot, $2 = end snapshot
    awk 'NR==FNR { av[$1]=$2; aa[$1]=$3; next }
         { printf "%-18s autovacuum +%d  autoanalyze +%d\n", $1, $2-av[$1], $3-aa[$1] }' \
        <(printf '%s\n' "$1") <(printf '%s\n' "$2")
}

wait_for_line() { # $1 = pattern, $2 = file, $3 = pid that must still be alive
    until grep -q "$1" "$2"; do
        kill -0 "$3" 2>/dev/null || { echo "driver exited before $1" >&2; cat "$2" >&2; return 1; }
        sleep 0.1
    done
}

# RESET_MODE=drop: drop the tables instead of truncating them, so the jar
# about to start creates ITS schema.sql layout. Needed when interleaving jars
# whose schemas differ (e.g. main vs per-wallet chains, #4).
reset_stores() {
    if [ "${RESET_MODE:-truncate}" = "drop" ]; then
        psql_db -q -c "DROP TABLE IF EXISTS idempotency_keys, outbox, ledger_entries, holds, wallets CASCADE" >/dev/null
    else
        psql_db -q -c "TRUNCATE idempotency_keys, outbox, ledger_entries, holds, wallets CASCADE" >/dev/null
    fi
    docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli FLUSHALL >/dev/null
}

service_pid() { netstat -ano | awk '$2 ~ /:8080$/ && $4 == "LISTENING" {print $5; exit}'; }

stop_service() {
    local pid; pid="$(service_pid || true)"
    if [ -n "$pid" ]; then taskkill //F //PID "$pid" >/dev/null 2>&1 || true; fi
    for _ in $(seq 1 50); do [ -z "$(service_pid || true)" ] && return 0; sleep 0.2; done
    echo "service did not stop" >&2; return 1
}

start_service() { # $1 = BENCH_TIMING value, $2 = log file
    stop_service
    BENCH_TIMING="$1" java -jar "$JAR" > "$2" 2>&1 &
    for _ in $(seq 1 150); do
        if curl -sf http://localhost:8080/health >/dev/null 2>&1; then return 0; fi
        sleep 0.2
    done
    echo "service did not become healthy" >&2; return 1
}

run_app() {
    local label="$1" clients="$2" wallets="$3" timings="$4" sampler="$5"
    local out="$RAW/$label.txt" drv="$LOGDIR/$label.driver.txt" smp="$LOGDIR/$label.sampler.txt"
    reset_stores
    local pre; pre="$(show_sync)"
    start_service "$timings" "$LOGDIR/$label.service.log"

    java -cp "$ROOT/target/bench" LoadDriver http://localhost:8080 "$clients" 10 "$MEASURE_SEC" 0 "$wallets" "$timings" > "$drv" 2>&1 &
    local drv_pid=$!
    : > "$smp"
    wait_for_line MEASURE_START "$drv" "$drv_pid"
    local av_start; av_start="$(av_counts)"
    if [ "$sampler" = "yes" ]; then
        psql_db -v duration_s="$SAMPLER_SEC" -v interval_ms=10 < "$ROOT/tools/pg-wait-sampler.sql" > "$smp" 2>&1
    fi
    wait_for_line MEASURE_END "$drv" "$drv_pid"
    local av_end; av_end="$(av_counts)"
    local avd; avd="$(av_delta "$av_start" "$av_end")"
    wait "$drv_pid"
    local post; post="$(show_sync)"
    stop_service

    {
        echo "# $label"
        echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "$REV_LINE"
        echo "config: clients=$clients wallets=$wallets BENCH_TIMING=$timings sampler=$sampler measureSec=$MEASURE_SEC"
        echo "synchronous_commit before=$pre after=$post"
        echo
        echo "## LoadDriver"
        cat "$drv"
        echo
        echo "## autovacuum inside the measure window (pg_stat_user_tables delta)"
        echo "$avd"
        if [ "$sampler" = "yes" ]; then
            echo
            echo "## pg-wait-sampler"
            cat "$smp"
        fi
    } > "$out"
    grep -E '^clients=|^busy' "$drv" | sed "s/^/[$label] /"
    echo "[$label] autovacuum in window: $(echo "$avd" | awk '{v+=$3; a+=$5} END {printf "vacuum +%d, analyze +%d", v, a}')"
}

run_pgbench() {
    local label="$1" out="$RAW/$1.txt" smp="$LOGDIR/$1.sampler.txt"
    stop_service
    reset_stores
    local pre; pre="$(show_sync)"
    psql_db -q -c "DROP TABLE IF EXISTS bench_commit_control; CREATE TABLE bench_commit_control (id bigserial PRIMARY KEY, v bigint NOT NULL)"
    docker compose -f "$ROOT/docker-compose.yml" cp "$ROOT/tools/commit-control.pgbench" postgres:/tmp/commit-control.pgbench >/dev/null
    # MSYS2_ARG_CONV_EXCL: stop Git Bash rewriting the in-container path
    # /tmp/... into a Windows path before docker sees it (other args, like
    # the compose file path, still need the conversion)
    MSYS2_ARG_CONV_EXCL="/tmp/" docker compose -f "$ROOT/docker-compose.yml" exec -T postgres \
        pgbench -n -c 16 -j 4 -T "$MEASURE_SEC" -f /tmp/commit-control.pgbench -U user123 userdb > "$LOGDIR/$label.pgbench.txt" 2>&1 &
    local pb_pid=$!
    sleep 1
    psql_db -v duration_s="$SAMPLER_SEC" -v interval_ms=10 < "$ROOT/tools/pg-wait-sampler.sql" > "$smp" 2>&1
    wait "$pb_pid"
    psql_db -q -c "DROP TABLE bench_commit_control"
    local post; post="$(show_sync)"
    {
        echo "# $label"
        echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "$REV_LINE"
        echo "config: pgbench -c 16 -j 4 -T $MEASURE_SEC tools/commit-control.pgbench (5 inserts + COMMIT, no shared lock)"
        echo "synchronous_commit before=$pre after=$post"
        echo
        echo "## pgbench"
        cat "$LOGDIR/$label.pgbench.txt"
        echo
        echo "## pg-wait-sampler"
        cat "$smp"
    } > "$out"
    grep -E '^tps' "$LOGDIR/$label.pgbench.txt" | sed "s/^/[$label] /"
}

case "${1:-}" in
    app)     shift; run_app "$@" ;;
    pgbench) shift; run_pgbench "$@" ;;
    sync)
        if [ "$2" = "off" ]; then psql_db -q -c "ALTER SYSTEM SET synchronous_commit = off"
        else psql_db -q -c "ALTER SYSTEM RESET synchronous_commit"; fi
        psql_db -q -At -c "SELECT pg_reload_conf()" >/dev/null
        echo "synchronous_commit=$(show_sync)" ;;
    reset)
        reset_stores
        psql_db -q -c "DROP SEQUENCE IF EXISTS ledger_seq_e5"
        echo "ledger rows=$(psql_db -At -c "SELECT count(*) FROM ledger_entries")" \
             "e5 sequence=$(psql_db -At -c "SELECT coalesce(to_regclass('ledger_seq_e5')::text, 'dropped')")" \
             "redis keys=$(docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli DBSIZE)" \
             "synchronous_commit=$(show_sync)" ;;
    *) sed -n '2,17p' "$0"; exit 2 ;;
esac
