# ---- build stage
FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
        g++ cmake make libcurl4-openssl-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Vendored-at-build-time single-header dependencies (JSON + HTTP server)
ADD https://raw.githubusercontent.com/nlohmann/json/v3.11.3/single_include/nlohmann/json.hpp third_party/nlohmann/json.hpp
ADD https://raw.githubusercontent.com/yhirose/cpp-httplib/v0.15.3/httplib.h third_party/httplib.h
COPY CMakeLists.txt .
COPY src ./src
RUN cmake -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j

# ---- runtime stage
FROM debian:bookworm-slim
# Required runtime deps only. PDAL is installed best-effort below: with it, the
# engine does real LAZ/COPC conversion; without it, it falls back to the
# clearly-marked STUB byte-copy at runtime — the build never fails on PDAL.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libcurl4 ca-certificates \
    && (apt-get install -y --no-install-recommends pdal \
        || echo "NOTE: pdal package unavailable on this mirror/arch — engine will use STUB LAZ/COPC conversion") \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/skyscan-engine /usr/local/bin/skyscan-engine
ENV ENGINE_PORT=9090
EXPOSE 9090
ENTRYPOINT ["skyscan-engine"]
