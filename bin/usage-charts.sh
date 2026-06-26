#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: $0 <run-dir>"
    echo "  Generate CPU, memory, and context-switch charts from mpstat/pidstat logs."
    echo "  Example: $0 data/20260624/1433"
    exit 1
}

[[ $# -lt 1 ]] && usage
RUN_DIR="$1"
[[ ! -d "$RUN_DIR" ]] && echo "Error: '$RUN_DIR' is not a directory" && exit 1

command -v gnuplot >/dev/null 2>&1 || { echo "Error: gnuplot not found"; exit 1; }

GENERATED=0

for SERVER_DIR in "$RUN_DIR"/*/; do
    [[ ! -d "$SERVER_DIR" ]] && continue

    HAS_DATA=0
    [[ -f "$SERVER_DIR/mpstat.log" ]] && HAS_DATA=1
    [[ -f "$SERVER_DIR/pidstat.log" ]] && HAS_DATA=1
    [[ $HAS_DATA -eq 0 ]] && continue

    HOST=$(basename "$SERVER_DIR" | sed 's/\.compute\.amazonaws\.com//' | sed 's/\.compute\.internal//')
    echo "Processing $HOST ..."

    TMPDIR_WORK=$(mktemp -d)
    trap "rm -rf $TMPDIR_WORK" EXIT

    # --- mpstat: CPU usage chart ---
    if [[ -f "$SERVER_DIR/mpstat.log" ]]; then
        awk '
        /^[0-9][0-9]:[0-9][0-9]:[0-9][0-9]/ && $2 == "all" {
            split($1, t, ":")
            secs = t[1]*3600 + t[2]*60 + t[3]
            if (NR_DATA == 0) base = secs
            elapsed = secs - base
            if (elapsed < 0) elapsed += 86400
            usr  = $3
            sys  = $5
            iowait = $6
            sirq = $7 + $8
            print elapsed, usr, sys, iowait, sirq
            NR_DATA++
        }
        ' "$SERVER_DIR/mpstat.log" > "$TMPDIR_WORK/mpstat.dat"

        if [[ -s "$TMPDIR_WORK/mpstat.dat" ]]; then
            gnuplot <<-GNUPLOT
set terminal pngcairo size 2400,900 enhanced font "sans,18"
set output "${SERVER_DIR}cpu-usage.png"
set title "CPU Usage — ${HOST}" font "sans,22"
set xlabel "Time (s)"
set ylabel "CPU %"
set yrange [0:100]
set grid ytics
set key outside right top
set style fill solid 0.6 noborder

plot "${TMPDIR_WORK}/mpstat.dat" using 1:(\$2+\$3+\$4+\$5) with filledcurves x1 title "%iowait+sirq" lc rgb "#e6a820", \
     "" using 1:(\$2+\$3) with filledcurves x1 title "%sys" lc rgb "#cc4444", \
     "" using 1:2 with filledcurves x1 title "%usr" lc rgb "#4477aa"
GNUPLOT
            GENERATED=$((GENERATED + 1))
        fi
    fi

    # --- pidstat: memory chart ---
    if [[ -f "$SERVER_DIR/pidstat.log" ]]; then
        awk '
        /minflt\/s/ { section = "mem"; next }
        /%usr/      { section = "cpu"; next }
        /cswch\/s/  { section = "csw"; next }
        /^$/        { next }
        /^Linux/    { next }

        section == "mem" && $4 == "-" {
            split($1, t, ":")
            secs = t[1]*3600 + t[2]*60 + t[3]
            if (MEM_BASE == 0) MEM_BASE = secs
            elapsed = secs - MEM_BASE
            if (elapsed < 0) elapsed += 86400
            rss_mb = $8 / 1024.0
            print elapsed, rss_mb
        }
        ' "$SERVER_DIR/pidstat.log" > "$TMPDIR_WORK/memory.dat"

        if [[ -s "$TMPDIR_WORK/memory.dat" ]]; then
            gnuplot <<-GNUPLOT
set terminal pngcairo size 2400,900 enhanced font "sans,18"
set output "${SERVER_DIR}memory-usage.png"
set title "RSS Memory — ${HOST}" font "sans,22"
set xlabel "Time (s)"
set ylabel "RSS (MB)"
set grid ytics
set key outside right top

plot "${TMPDIR_WORK}/memory.dat" using 1:2 with lines lw 3 lc rgb "#4477aa" title "RSS"
GNUPLOT
            GENERATED=$((GENERATED + 1))
        fi

        # --- pidstat: context switches chart ---
        # TGID aggregate rows report 0 for cswch — sum per-thread rows per timestamp
        awk '
        /minflt\/s/ { section = "mem"; next }
        /%usr/      { section = "cpu"; next }
        /cswch\/s/  { section = "csw"; next }
        /^$/        {
            if (section == "csw" && cur_time != "") {
                if (CSW_BASE == 0) CSW_BASE = cur_secs
                elapsed = cur_secs - CSW_BASE
                if (elapsed < 0) elapsed += 86400
                print elapsed, vol, nvol
            }
            section = ""; cur_time = ""; vol = 0; nvol = 0
            next
        }
        /^Linux/    { next }

        section == "csw" && $3 == "-" {
            split($1, t, ":")
            cur_secs = t[1]*3600 + t[2]*60 + t[3]
            cur_time = $1
            vol  += $5
            nvol += $6
        }
        END {
            if (section == "csw" && cur_time != "") {
                if (CSW_BASE == 0) CSW_BASE = cur_secs
                elapsed = cur_secs - CSW_BASE
                if (elapsed < 0) elapsed += 86400
                print elapsed, vol, nvol
            }
        }
        ' "$SERVER_DIR/pidstat.log" > "$TMPDIR_WORK/cswch.dat"

        if [[ -s "$TMPDIR_WORK/cswch.dat" ]]; then
            gnuplot <<-GNUPLOT
set terminal pngcairo size 2400,900 enhanced font "sans,18"
set output "${SERVER_DIR}context-switches.png"
set title "Context Switches — ${HOST}" font "sans,22"
set xlabel "Time (s)"
set ylabel "switches/s"
set grid ytics
set key outside right top

plot "${TMPDIR_WORK}/cswch.dat" using 1:2 with lines lw 2.5 lc rgb "#4477aa" title "voluntary", \
     "" using 1:3 with lines lw 2.5 lc rgb "#cc4444" title "involuntary"
GNUPLOT
            GENERATED=$((GENERATED + 1))
        fi
    fi

    rm -rf "$TMPDIR_WORK"
done

echo "Done. Generated $GENERATED chart(s)."
