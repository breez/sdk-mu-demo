#!/usr/bin/env bash
#
# Phase 1 smoke test: create a user, read its balance.
#
# Prereqs: the server is running (`make up`) and NETWORK=regtest.

set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"

echo "→ POST $BASE/users"
create=$(curl -sS -X POST "$BASE/users" -H 'content-type: application/json')
echo "  $create"

user_id=$(printf '%s' "$create" | sed -nE 's/.*"user_id":"([^"]+)".*/\1/p')
api_key=$(printf '%s' "$create" | sed -nE 's/.*"api_key":"([^"]+)".*/\1/p')

if [[ -z "${user_id}" || -z "${api_key}" ]]; then
  echo "smoke: failed to parse user_id / api_key from response" >&2
  exit 1
fi

echo "→ GET $BASE/users/$user_id/info"
info=$(curl -sS -H "Authorization: Bearer $api_key" "$BASE/users/$user_id/info")
echo "  $info"

if ! printf '%s' "$info" | grep -q '"balance_sats"'; then
  echo "smoke: balance_sats missing from /info response" >&2
  exit 1
fi

echo "smoke: OK"
