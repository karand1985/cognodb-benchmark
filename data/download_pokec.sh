#!/usr/bin/env bash
# =============================================================================
# download_pokec.sh
#
# Downloads the SNAP soc-Pokec social network dataset and samples it down to
# a size that fits every free-tier graph database in this benchmark.
#
# Output files (written to the same directory as this script):
#   pokec_nodes.csv   — 50,000 user nodes  (id, age, gender, region)
#   pokec_edges.csv   — 200,000 FRIENDS_WITH edges  (source_id, target_id)
#
# Requirements: curl, gzip, awk, shuf (GNU coreutils — standard on Linux/macOS)
#
# Usage:
#   chmod +x download_pokec.sh
#   ./download_pokec.sh
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# --- Configuration -----------------------------------------------------------
TARGET_NODES=50000
TARGET_EDGES=200000

PROFILES_URL="https://snap.stanford.edu/data/soc-pokec-profiles.txt.gz"
RELATIONS_URL="https://snap.stanford.edu/data/soc-pokec-relationships.txt.gz"

PROFILES_GZ="soc-pokec-profiles.txt.gz"
RELATIONS_GZ="soc-pokec-relationships.txt.gz"
PROFILES_TXT="soc-pokec-profiles.txt"
RELATIONS_TXT="soc-pokec-relationships.txt"

NODES_CSV="pokec_nodes.csv"
EDGES_CSV="pokec_edges.csv"
SAMPLED_IDS="sampled_node_ids.txt"

# --- Helpers -----------------------------------------------------------------
log()  { echo "[$(date '+%H:%M:%S')] $*"; }
die()  { echo "ERROR: $*" >&2; exit 1; }

require_cmd() {
  command -v "$1" &>/dev/null || die "'$1' is required but not installed."
}

# --- Pre-flight checks -------------------------------------------------------
require_cmd curl
require_cmd gzip
require_cmd awk
require_cmd shuf

# --- Download ----------------------------------------------------------------
log "Step 1/5 — Downloading Pokec profiles (~400 MB, please wait)..."
if [[ ! -f "$PROFILES_GZ" ]]; then
  curl -L --progress-bar -o "$PROFILES_GZ" "$PROFILES_URL"
else
  log "  Already downloaded: $PROFILES_GZ — skipping."
fi

log "Step 2/5 — Downloading Pokec relationships (~200 MB, please wait)..."
if [[ ! -f "$RELATIONS_GZ" ]]; then
  curl -L --progress-bar -o "$RELATIONS_GZ" "$RELATIONS_URL"
else
  log "  Already downloaded: $RELATIONS_GZ — skipping."
fi

# --- Decompress --------------------------------------------------------------
log "Step 3/5 — Decompressing..."
if [[ ! -f "$PROFILES_TXT" ]]; then
  gzip -dk "$PROFILES_GZ"
  log "  Decompressed: $PROFILES_TXT"
else
  log "  Already decompressed: $PROFILES_TXT — skipping."
fi

if [[ ! -f "$RELATIONS_TXT" ]]; then
  gzip -dk "$RELATIONS_GZ"
  log "  Decompressed: $RELATIONS_TXT"
else
  log "  Already decompressed: $RELATIONS_TXT — skipping."
fi

# --- Sample nodes ------------------------------------------------------------
# Pokec profiles format (tab-separated, no header):
# user_id | public | completion_percentage | gender | region | ... (many columns)
# We extract: id (col1), gender (col4), region (col5), age (col10)

log "Step 4/5 — Sampling $TARGET_NODES nodes..."

# Extract node ids from profiles, shuffle, take first TARGET_NODES
awk -F'\t' 'NR>0 { print $1 }' "$PROFILES_TXT" \
  | shuf \
  | head -n "$TARGET_NODES" \
  > "$SAMPLED_IDS"

ACTUAL_NODES=$(wc -l < "$SAMPLED_IDS" | tr -d ' ')
log "  Sampled $ACTUAL_NODES node IDs."

# Build a lookup set and emit the nodes CSV with header
echo "id,gender,region,age" > "$NODES_CSV"

awk -F'\t' '
  NR == FNR {
    ids[$1] = 1
    next
  }
  ($1 in ids) {
    id     = $1
    gender = ($4 == "" || $4 == "null") ? "unknown" : $4
    region = ($5 == "" || $5 == "null") ? "unknown" : $5
    age    = ($10 == "" || $10 == "null") ? 0 : $10

    # Sanitise: strip commas and newlines from free-text fields
    gsub(/,/, ";", region)
    gsub(/\r/, "", region)

    print id "," gender "," region "," age
  }
' "$SAMPLED_IDS" "$PROFILES_TXT" \
>> "$NODES_CSV"

WRITTEN_NODES=$(( $(wc -l < "$NODES_CSV") - 1 ))
log "  Written $WRITTEN_NODES node rows to $NODES_CSV"

# --- Sample edges ------------------------------------------------------------
# Pokec relationships format (tab-separated, no header): source_id \t target_id
# Keep only edges where BOTH endpoints are in our sampled node set.

log "Step 5/5 — Sampling up to $TARGET_EDGES edges (both endpoints must be in node sample)..."

echo "source_id,target_id" > "$EDGES_CSV"

awk -F'\t' '
  NR == FNR {
    ids[$1] = 1
    next
  }
  ($1 in ids) && ($2 in ids) {
    print $1 "," $2
  }
' "$SAMPLED_IDS" "$RELATIONS_TXT" \
| shuf \
| head -n "$TARGET_EDGES" \
>> "$EDGES_CSV"

WRITTEN_EDGES=$(( $(wc -l < "$EDGES_CSV") - 1 ))
log "  Written $WRITTEN_EDGES edge rows to $EDGES_CSV"

# --- Cleanup temp files ------------------------------------------------------
rm -f "$SAMPLED_IDS"

# --- Summary -----------------------------------------------------------------
echo ""
echo "============================================================"
echo "  Dataset ready."
echo "  Nodes : $WRITTEN_NODES   →  $NODES_CSV"
echo "  Edges : $WRITTEN_EDGES   →  $EDGES_CSV"
echo ""
echo "  NOTE: The raw .gz and .txt files are large."
echo "  You may delete them once you have verified the CSVs:"
echo "    rm $PROFILES_GZ $RELATIONS_GZ $PROFILES_TXT $RELATIONS_TXT"
echo "============================================================"
