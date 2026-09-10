#!/bin/bash
# Measure which PL/pgSQL / SQL stored functions in the test database are
# actually executed by the DAO/BLL test suites.
#
# It relies on PostgreSQL's built-in per-function execution statistics
# (pg_stat_user_functions), which are populated when track_functions is
# enabled. Statistics are collected even for functions called inside
# transactions that are later rolled back (as the DAO tests do), so they
# accurately reflect what the tests exercised.
#
# Usage:
#   db-function-coverage.sh enable            # turn on tracking + zero counters
#   db-function-coverage.sh report [PREFIX]   # emit coverage report files
#
# Connection is taken from the standard libpq environment variables, with
# sensible defaults matching the CI Postgres service:
#   PGHOST (postgres) PGPORT (5432) PGUSER (postgres) PGDATABASE (engine_dao_tests)
#   PGPASSWORD must be provided in the environment.
set -euo pipefail

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-postgres}"
export PGDATABASE="${PGDATABASE:-engine_dao_tests}"

psql_q() { psql -v ON_ERROR_STOP=1 -qtAX "$@"; }

# CTE listing every user-defined function/procedure in the public schema,
# excluding objects owned by extensions (e.g. uuid-ossp), joined to their
# execution counters.
read -r -d '' COVERAGE_CTE <<'SQL' || true
WITH defined AS (
    SELECT p.oid                                        AS funcid,
           p.proname                                    AS name,
           pg_get_function_identity_arguments(p.oid)    AS args
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.prokind IN ('f', 'p')
      AND NOT EXISTS (
          SELECT 1 FROM pg_depend d
          WHERE d.objid = p.oid AND d.deptype = 'e'
      )
),
coverage AS (
    SELECT d.name,
           d.args,
           COALESCE(s.calls, 0) AS calls
    FROM defined d
    LEFT JOIN pg_stat_user_functions s ON s.funcid = d.funcid
)
SQL

cmd="${1:-}"
case "$cmd" in
    enable)
        # track_functions is a SIGHUP parameter; ALTER SYSTEM + reload makes it
        # take effect for the test sessions that start afterwards.
        psql_q -d postgres      -c "ALTER SYSTEM SET track_functions = 'all';"
        psql_q -d postgres      -c "SELECT pg_reload_conf();" >/dev/null
        # pg_stat_user_functions is per-database; reset counters in the test DB.
        psql_q -d "$PGDATABASE" -c "SELECT pg_stat_reset();" >/dev/null
        echo "track_functions='all' enabled; function stats reset for ${PGDATABASE}"
        ;;

    report)
        prefix="${2:-db-function-coverage}"
        # Give the stats collector a moment to flush the last calls.
        sleep 2

        # Full list of uncovered (never-called) functions.
        psql_q -d "$PGDATABASE" -c "
            ${COVERAGE_CTE}
            SELECT name || '(' || args || ')'
            FROM coverage
            WHERE calls = 0
            ORDER BY name, args;
        " > "${prefix}.uncovered.txt"

        # Full list of covered functions with call counts (highest first).
        psql_q -d "$PGDATABASE" -F $'\t' -c "
            ${COVERAGE_CTE}
            SELECT calls, name || '(' || args || ')'
            FROM coverage
            WHERE calls > 0
            ORDER BY calls DESC, name;
        " > "${prefix}.covered.tsv"

        # Aggregate numbers.
        read -r total covered uncovered pct < <(psql_q -d "$PGDATABASE" -F ' ' -c "
            ${COVERAGE_CTE}
            SELECT count(*),
                   count(*) FILTER (WHERE calls > 0),
                   count(*) FILTER (WHERE calls = 0),
                   round(100.0 * count(*) FILTER (WHERE calls > 0) / NULLIF(count(*), 0), 1)
            FROM coverage;
        ")

        {
            echo "Database stored-function coverage (${PGDATABASE})"
            echo "  defined:   ${total}"
            echo "  covered:   ${covered}"
            echo "  uncovered: ${uncovered}"
            echo "  coverage:  ${pct}%"
        } | tee "${prefix}.summary.txt"

        echo
        echo "Uncovered functions written to ${prefix}.uncovered.txt (${uncovered} entries)"
        ;;

    *)
        echo "usage: $0 {enable|report [prefix]}" >&2
        exit 2
        ;;
esac
