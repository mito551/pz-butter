#!/usr/bin/env bash
# Task 7 checks: CD.data / CD.ownerKey per-frame memo. Run from anywhere.
set -e
cd "$(dirname "$0")"
lua memo_harness.lua