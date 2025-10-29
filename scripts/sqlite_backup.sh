#!/usr/bin/env bash
set -euo pipefail

DB_PATH="${DB_PATH:-data/chatbotchef.sqlite}"
BACKUP_DIR="${BACKUP_DIR:-/root/data/backup}"
KEEP_DAYS="${KEEP_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"

if [[ ! -f "$DB_PATH" ]]; then
  echo "DB-BACKUP-ERR: database file not found at $DB_PATH" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"

DEST="$BACKUP_DIR/chatbotchef-${STAMP}.sqlite"

if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "$DB_PATH" "VACUUM INTO '$DEST'" || {
    echo "DB-BACKUP-ERR: sqlite3 VACUUM failed" >&2
    rm -f "$DEST"
    exit 1
  }
else
  cp "$DB_PATH" "$DEST"
fi

find "$BACKUP_DIR" -maxdepth 1 -type f -name 'chatbotchef-*.sqlite' -mtime "+$KEEP_DAYS" -print -delete

echo "DB-BACKUP-OK: stored $DEST"
