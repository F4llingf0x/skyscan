#include "pipeline.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <map>
#include <stdexcept>
#include <tuple>

namespace skyscan {

static void log(const std::string& msg) { std::cout << "[pipeline] " << msg << std::endl; }

void Cloud::recomputeBounds() {
    if (pts.empty()) { minx=miny=minz=maxx=maxy=maxz=0; return; }
    minx=miny=minz= std::numeric_limits<double>::max();
    maxx=maxy=maxz=-std::numeric_limits<double>::max();
    for (const auto& p : pts) {
        minx=std::min(minx,p.x); maxx=std::max(maxx,p.x);
        miny=std::min(miny,p.y); maxy=std::max(maxy,p.y);
        minz=std::min(minz,p.z); maxz=std::max(maxz,p.z);
    }
}

// ---------------------------------------------------------------- LAS 1.x I/O

template <typename T> static T rd(const char* buf) { T v; std::memcpy(&v, buf, sizeof(T)); return v; }

Cloud readLas(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot open " + path);
    char hdr[375];
    f.read(hdr, 227);
    if (f.gcount() < 227 || std::strncmp(hdr, "LASF", 4) != 0)
        throw std::runtime_error(path + " is not a LAS file");

    uint32_t pointOffset = rd<uint32_t>(hdr + 96);
    uint8_t  format      = static_cast<uint8_t>(hdr[104]) & 0x3F;
    uint16_t recLen      = rd<uint16_t>(hdr + 105);
    uint32_t nPoints     = rd<uint32_t>(hdr + 107);
    double sx = rd<double>(hdr + 131), sy = rd<double>(hdr + 139), sz = rd<double>(hdr + 147);
    double ox = rd<double>(hdr + 155), oy = rd<double>(hdr + 163), oz = rd<double>(hdr + 171);

    if (format > 3) throw std::runtime_error("unsupported LAS point format " + std::to_string(format));

    Cloud c;
    c.pts.reserve(nPoints);
    f.seekg(pointOffset, std::ios::beg);
    std::vector<char> rec(recLen);
    for (uint32_t i = 0; i < nPoints && f.read(rec.data(), recLen); ++i) {
        int32_t X = rd<int32_t>(rec.data());
        int32_t Y = rd<int32_t>(rec.data() + 4);
        int32_t Z = rd<int32_t>(rec.data() + 8);
        uint16_t inten = rd<uint16_t>(rec.data() + 12);
        c.pts.push_back({X*sx+ox, Y*sy+oy, Z*sz+oz, inten});
    }
    c.recomputeBounds();
    log("read " + std::to_string(c.pts.size()) + " points from " + path);
    return c;
}

template <typename T> static void wr(std::ofstream& f, T v) { f.write(reinterpret_cast<const char*>(&v), sizeof(T)); }

void writeLas(const std::string& path, const Cloud& cloud) {
    std::ofstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot write " + path);
    const double scale = 0.001;
    const uint32_t n = static_cast<uint32_t>(cloud.pts.size());

    f.write("LASF", 4);
    wr<uint16_t>(f, 0); wr<uint16_t>(f, 0); wr<uint32_t>(f, 0);          // source, encoding, guid1
    wr<uint16_t>(f, 0); wr<uint16_t>(f, 0);                              // guid2, guid3
    char zeros[32] = {0};
    f.write(zeros, 8);                                                   // guid4
    wr<uint8_t>(f, 1); wr<uint8_t>(f, 2);                                // LAS 1.2
    char sysid[32] = "SkyScan Engine";      f.write(sysid, 32);
    char sw[32]    = "skyscan-engine 0.1";  f.write(sw, 32);
    wr<uint16_t>(f, 186); wr<uint16_t>(f, 2026);                         // day, year
    wr<uint16_t>(f, 227); wr<uint32_t>(f, 227); wr<uint32_t>(f, 0);      // hdr size, point offset, VLRs
    wr<uint8_t>(f, 0); wr<uint16_t>(f, 20);                              // format 0, 20 bytes
    wr<uint32_t>(f, n);
    wr<uint32_t>(f, n); wr<uint32_t>(f, 0); wr<uint32_t>(f, 0); wr<uint32_t>(f, 0); wr<uint32_t>(f, 0);
    wr<double>(f, scale); wr<double>(f, scale); wr<double>(f, scale);
    wr<double>(f, cloud.minx); wr<double>(f, cloud.miny); wr<double>(f, cloud.minz);
    wr<double>(f, cloud.maxx); wr<double>(f, cloud.minx);                // max x, min x
    wr<double>(f, cloud.maxy); wr<double>(f, cloud.miny);
    wr<double>(f, cloud.maxz); wr<double>(f, cloud.minz);

    for (const auto& p : cloud.pts) {
        wr<int32_t>(f, static_cast<int32_t>(std::llround((p.x - cloud.minx) / scale)));
        wr<int32_t>(f, static_cast<int32_t>(std::llround((p.y - cloud.miny) / scale)));
        wr<int32_t>(f, static_cast<int32_t>(std::llround((p.z - cloud.minz) / scale)));
        wr<uint16_t>(f, p.intensity);
        wr<uint8_t>(f, 0x09);   // return 1 of 1
        wr<uint8_t>(f, 1);      // classification: unclassified
        wr<int8_t>(f, 0); wr<uint8_t>(f, 0); wr<uint16_t>(f, 0);
    }
    log("wrote " + std::to_string(n) + " points to " + path);
}

// ---------------------------------------------------------------- cleaning (real)

Cloud removeOutliers(const Cloud& in, double zThreshold) {
    // Grid-based elevation statistics: points far from their cell's median z are dropped.
    if (in.pts.size() < 100) return in;
    const double cell = 4.0;
    std::map<std::pair<long,long>, std::vector<double>> zs;
    for (const auto& p : in.pts)
        zs[{ (long)std::floor(p.x/cell), (long)std::floor(p.y/cell) }].push_back(p.z);

    std::map<std::pair<long,long>, std::pair<double,double>> stats; // median, MAD
    for (auto& [k, v] : zs) {
        std::sort(v.begin(), v.end());
        double med = v[v.size()/2];
        std::vector<double> dev;
        dev.reserve(v.size());
        for (double z : v) dev.push_back(std::abs(z - med));
        std::sort(dev.begin(), dev.end());
        double mad = std::max(0.05, dev[dev.size()/2]);
        stats[k] = {med, mad};
    }

    Cloud out;
    out.pts.reserve(in.pts.size());
    for (const auto& p : in.pts) {
        auto key = std::make_pair((long)std::floor(p.x/cell), (long)std::floor(p.y/cell));
        const auto& [med, mad] = stats[key];
        double limit = std::max(1.5, zThreshold * mad);   // never clip legitimate structure (walls, edges)
        if (std::abs(p.z - med) <= limit) out.pts.push_back(p);
    }
    out.recomputeBounds();
    log("outlier removal: " + std::to_string(in.pts.size() - out.pts.size()) + " points removed");
    return out;
}

Cloud voxelDownsample(const Cloud& in, double voxelSize) {
    std::map<std::tuple<long,long,long>, Point> grid;
    for (const auto& p : in.pts) {
        auto key = std::make_tuple((long)std::floor(p.x/voxelSize),
                                   (long)std::floor(p.y/voxelSize),
                                   (long)std::floor(p.z/voxelSize));
        grid.emplace(key, p);   // keep first point per voxel
    }
    Cloud out;
    out.pts.reserve(grid.size());
    for (const auto& [k, p] : grid) out.pts.push_back(p);
    out.recomputeBounds();
    log("voxel downsample @" + std::to_string(voxelSize) + "m: " +
        std::to_string(in.pts.size()) + " -> " + std::to_string(out.pts.size()));
    return out;
}

// ---------------------------------------------------------------- stubbed geometry

Cloud georeference(const Cloud& in, const std::string& targetCrs) {
    // STUB: sample data is already generated in projected coordinates, so this
    // applies only a tiny RTK "correction". A production build replaces this with
    // PDAL filters.reprojection + a real trajectory/boresight solution.
    log("STUB georeference into " + targetCrs + " (applying fabricated RTK correction only)");
    Cloud out = in;
    for (auto& p : out.pts) { p.x += 0.012; p.y -= 0.008; }
    out.recomputeBounds();
    return out;
}

Cloud reconstructSpinningTilted(const Cloud& in) {
    // STUB: real system runs SLAM / continuous-time trajectory optimization on the
    // spinning-tilted stream. Here the branch is exercised but geometry passes through.
    log("STUB spinning-tilted reconstruction (SLAM placeholder, pass-through)");
    return in;
}

// ---------------------------------------------------------------- co-registration + merge (real, simplified)

Cloud coregisterAndMerge(const Cloud& crude, const Cloud& detailed) {
    // Estimate a vertical + horizontal shift over the overlap bbox by comparing
    // gridded median elevations, then apply to the detailed cloud and concatenate.
    double ox = std::max(crude.minx, detailed.minx), oX = std::min(crude.maxx, detailed.maxx);
    double oy = std::max(crude.miny, detailed.miny), oY = std::min(crude.maxy, detailed.maxy);
    double dz = 0;
    if (oX > ox && oY > oy) {
        auto medianZ = [&](const Cloud& c) {
            std::vector<double> zs;
            for (const auto& p : c.pts)
                if (p.x >= ox && p.x <= oX && p.y >= oy && p.y <= oY) zs.push_back(p.z);
            if (zs.empty()) return 0.0;
            std::nth_element(zs.begin(), zs.begin()+zs.size()/2, zs.end());
            return zs[zs.size()/2];
        };
        double zc = medianZ(crude), zd = medianZ(detailed);
        if (zc != 0.0 && zd != 0.0) dz = zc - zd;
    }
    log("co-registration: overlap dz = " + std::to_string(dz) + " m");

    Cloud out;
    out.pts.reserve(crude.pts.size() + detailed.pts.size());
    for (const auto& p : crude.pts) out.pts.push_back(p);
    for (auto p : detailed.pts) { p.z += dz; out.pts.push_back(p); }
    out.recomputeBounds();
    return out;
}

// ---------------------------------------------------------------- format conversion

bool convertWithPdal(const std::string& lasPath, const std::string& outPath, bool copc) {
    // Try the PDAL CLI (installed in the Docker image). If unavailable or it fails,
    // fall back to a STUB copy so the pipeline still delivers an artifact end-to-end.
    std::string writer = copc ? "writers.copc" : "writers.las";
    std::string cmd = "pdal translate \"" + lasPath + "\" \"" + outPath + "\" --writer " + writer +
                      (copc ? "" : " --writers.las.compression=laszip") + " >/dev/null 2>&1";
    int rc = std::system(cmd.c_str());
    if (rc == 0) { log(std::string(copc ? "COPC" : "LAZ") + " written via PDAL: " + outPath); return true; }

    log("STUB " + std::string(copc ? "COPC" : "LAZ") + " conversion (pdal unavailable, rc=" +
        std::to_string(rc) + "): copying LAS bytes to " + outPath);
    std::ifstream src(lasPath, std::ios::binary);
    std::ofstream dst(outPath, std::ios::binary);
    dst << src.rdbuf();
    return false;
}

// ---------------------------------------------------------------- full job

PipelineResult runPipeline(const std::vector<JobInput>& inputs, const std::string& targetCrs,
                           const std::string& lasOut, const std::string& lazOut, const std::string& copcOut) {
    Cloud crude, detailed;
    bool haveCrude = false, haveDetailed = false;

    for (const auto& in : inputs) {
        Cloud c = readLas(in.localPath);
        c = removeOutliers(c);

        if (in.sensorType == "SPINNING_TILTED") {
            c = reconstructSpinningTilted(c);           // STUB SLAM branch (kept real in flow)
            c = voxelDownsample(c, 0.10);               // dense detail cloud: fine voxels
        } else {
            c = voxelDownsample(c, 0.50);               // crude overview: coarser voxels
        }
        c = georeference(c, targetCrs);                 // STUB georeference

        if (in.passType == "DETAILED") { detailed = std::move(c); haveDetailed = true; }
        else                           { crude = std::move(c);    haveCrude = true; }
    }

    Cloud merged;
    if (haveCrude && haveDetailed) merged = coregisterAndMerge(crude, detailed);
    else merged = haveDetailed ? detailed : crude;

    writeLas(lasOut, merged);
    bool realLaz  = convertWithPdal(lasOut, lazOut, false);
    bool realCopc = convertWithPdal(lasOut, copcOut, true);

    PipelineResult r;
    r.pointCount = merged.pts.size();
    r.minx = merged.minx; r.miny = merged.miny; r.minz = merged.minz;
    r.maxx = merged.maxx; r.maxy = merged.maxy; r.maxz = merged.maxz;
    r.crs = targetCrs;
    r.message = std::string("cleaned+merged ") + std::to_string(inputs.size()) + " acquisitions" +
                (realCopc ? "; COPC via PDAL" : "; COPC STUB copy") +
                (realLaz ? "; LAZ via PDAL" : "; LAZ STUB copy");
    return r;
}

PipelineResult runFinalize(const std::string& inputPath, const std::string& targetCrs,
                           const std::string& lasOut, const std::string& lazOut, const std::string& copcOut) {
    log("FINALIZE: operator-edited cloud, pass-through rewrite + format regeneration");
    Cloud c = readLas(inputPath);
    writeLas(lasOut, c);
    bool realLaz  = convertWithPdal(lasOut, lazOut, false);
    bool realCopc = convertWithPdal(lasOut, copcOut, true);

    PipelineResult r;
    r.pointCount = c.pts.size();
    r.minx = c.minx; r.miny = c.miny; r.minz = c.minz;
    r.maxx = c.maxx; r.maxy = c.maxy; r.maxz = c.maxz;
    r.crs = targetCrs;
    r.message = std::string("finalized edited cloud") +
                (realCopc ? "; COPC via PDAL" : "; COPC STUB copy") +
                (realLaz ? "; LAZ via PDAL" : "; LAZ STUB copy");
    return r;
}

} // namespace skyscan
