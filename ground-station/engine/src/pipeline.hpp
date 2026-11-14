#pragma once
// =============================================================================
// SkyScan processing pipeline.
//
// Real work done here in plain C++ on LAS point data:
//   * statistical outlier removal (z-score on local neighborhood elevation)
//   * voxel downsampling
//   * co-registration + merge of crude and detailed clouds
//   * LAS 1.2 read/write
//
// Clearly-marked STUBs (heavy geometry that a production build wires to
// PDAL/PCL or a SLAM stack — each still moves a real file through the pipe):
//   * STUB georeference: applies the (fabricated) GNSS/IMU offsets instead of
//     a full trajectory/boresight solution
//   * STUB spinning-tilted reconstruction: pass-through instead of SLAM
//   * COPC/LAZ conversion: uses the `pdal` CLI when present in the image;
//     otherwise falls back to a STUB copy so the flow stays exercisable.
// =============================================================================

#include <cstdint>
#include <string>
#include <vector>

namespace skyscan {

struct Point {
    double x, y, z;
    uint16_t intensity;
};

struct Cloud {
    std::vector<Point> pts;
    double minx=0, miny=0, minz=0, maxx=0, maxy=0, maxz=0;
    void recomputeBounds();
};

struct JobInput {
    std::string acquisitionId;
    std::string sensorType;   // FIXED_3D | SPINNING_TILTED
    std::string passType;     // CRUDE | DETAILED
    std::string localPath;    // downloaded raw scan
};

struct PipelineResult {
    uint64_t pointCount = 0;
    double minx, miny, minz, maxx, maxy, maxz;
    std::string crs;
    std::string message;
};

// LAS 1.x I/O (point formats 0-3 read; format 0 written).
Cloud readLas(const std::string& path);
void writeLas(const std::string& path, const Cloud& cloud);

// Cleaning: remove gross elevation outliers, then voxel-downsample.
Cloud removeOutliers(const Cloud& in, double zThreshold = 3.5);
Cloud voxelDownsample(const Cloud& in, double voxelSize);

// STUB georeference: nudges the cloud by the "GNSS/IMU" solution. A real build
// replaces this with a PDAL reprojection + trajectory/boresight correction.
Cloud georeference(const Cloud& in, const std::string& targetCrs);

// STUB SLAM reconstruction for SPINNING_TILTED input: identity pass-through
// with a log line. Keeps the sensorType branch real without shipping SLAM.
Cloud reconstructSpinningTilted(const Cloud& in);

// ICP-lite co-registration: aligns detailed onto crude via centroid + median-z
// shift over the overlap region, then merges. (Real system: full ICP/NDT.)
Cloud coregisterAndMerge(const Cloud& crude, const Cloud& detailed);

// Convert LAS -> LAZ and LAS -> COPC. Uses the `pdal` CLI if available,
// otherwise writes a STUB copy so downstream steps still have an artifact.
// Returns true if real conversion happened.
bool convertWithPdal(const std::string& lasPath, const std::string& outPath, bool copc);

// Full job: process each input by sensor type, merge, write the three outputs.
PipelineResult runPipeline(const std::vector<JobInput>& inputs,
                           const std::string& targetCrs,
                           const std::string& lasOut,
                           const std::string& lazOut,
                           const std::string& copcOut);

// FINALIZE job: the input LAS is an operator-edited, already-processed cloud.
// The edit is authoritative — no cleaning/downsampling/merging — just validate,
// rewrite, and regenerate the LAZ + COPC artifacts.
PipelineResult runFinalize(const std::string& inputPath,
                           const std::string& targetCrs,
                           const std::string& lasOut,
                           const std::string& lazOut,
                           const std::string& copcOut);

} // namespace skyscan
