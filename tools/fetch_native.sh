#!/usr/bin/env bash
# Thin wrapper — prefer tools/fetch_native.py
exec python3 "$(dirname "$0")/fetch_native.py" "$@"
