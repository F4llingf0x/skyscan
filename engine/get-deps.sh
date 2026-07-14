#!/usr/bin/env bash
# Fetch single-header dependencies for a local (non-Docker) engine build.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p third_party/nlohmann
curl -fsSL -o third_party/nlohmann/json.hpp \
  https://raw.githubusercontent.com/nlohmann/json/v3.11.3/single_include/nlohmann/json.hpp
curl -fsSL -o third_party/httplib.h \
  https://raw.githubusercontent.com/yhirose/cpp-httplib/v0.15.3/httplib.h
echo "deps fetched into engine/third_party/"
