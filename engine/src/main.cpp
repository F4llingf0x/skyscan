// =============================================================================
// SkyScan C++ processing engine — asynchronous worker.
//
// Two entry modes:
//   * service (default): HTTP server on ENGINE_PORT (9090).
//       POST /jobs   -> 202 Accepted; job runs on a worker thread; on finish the
//                       engine POSTs the result to the job's callbackUrl with the
//                       X-Internal-Token shared secret.
//       GET  /health -> {"status":"UP"}
//   * CLI:  skyscan-engine --cli <crude.las|-> <detailed.las|-> <outdir>
//       runs the pipeline directly on local files (no backend needed).
//
// Transport uses presigned object-storage URLs (libcurl), so the engine holds
// no storage credentials. JSON via nlohmann/json, HTTP server via cpp-httplib
// (both fetched into the Docker image at build time).
// =============================================================================

#include "pipeline.hpp"

#include <httplib.h>
#include <nlohmann/json.hpp>
#include <curl/curl.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

using json = nlohmann::json;
namespace fs = std::filesystem;
using namespace skyscan;

static void logln(const std::string& m) { std::cout << "[engine] " << m << std::endl; }

// ---------------------------------------------------------------- curl helpers

static size_t writeToFile(void* ptr, size_t size, size_t nmemb, void* stream) {
    return fwrite(ptr, size, nmemb, static_cast<FILE*>(stream));
}
static size_t readFromFile(char* buffer, size_t size, size_t nitems, void* stream) {
    return fread(buffer, size, nitems, static_cast<FILE*>(stream));
}
static size_t discard(void*, size_t size, size_t nmemb, void*) { return size * nmemb; }

static bool httpDownload(const std::string& url, const std::string& dest) {
    CURL* curl = curl_easy_init();
    if (!curl) return false;
    FILE* f = fopen(dest.c_str(), "wb");
    if (!f) { curl_easy_cleanup(curl); return false; }
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeToFile);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, f);
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L);
    CURLcode rc = curl_easy_perform(curl);
    fclose(f);
    curl_easy_cleanup(curl);
    return rc == CURLE_OK;
}

static bool httpUpload(const std::string& url, const std::string& src, const std::string& contentType) {
    CURL* curl = curl_easy_init();
    if (!curl) return false;
    FILE* f = fopen(src.c_str(), "rb");
    if (!f) { curl_easy_cleanup(curl); return false; }
    curl_off_t size = static_cast<curl_off_t>(fs::file_size(src));
    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, ("Content-Type: " + contentType).c_str());
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_UPLOAD, 1L);
    curl_easy_setopt(curl, CURLOPT_READFUNCTION, readFromFile);
    curl_easy_setopt(curl, CURLOPT_READDATA, f);
    curl_easy_setopt(curl, CURLOPT_INFILESIZE_LARGE, size);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, discard);
    curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L);
    CURLcode rc = curl_easy_perform(curl);
    fclose(f);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    return rc == CURLE_OK;
}

static bool httpPostJson(const std::string& url, const std::string& body, const std::string& internalToken) {
    CURL* curl = curl_easy_init();
    if (!curl) return false;
    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    headers = curl_slist_append(headers, ("X-Internal-Token: " + internalToken).c_str());
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, discard);
    curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L);
    CURLcode rc = curl_easy_perform(curl);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    return rc == CURLE_OK;
}

// ---------------------------------------------------------------- job execution

static std::atomic<int> jobCounter{0};

static void runJob(const json& job, const std::string& internalToken) {
    const std::string jobId = job.value("jobId", "unknown");
    const std::string callbackUrl = job.value("callbackUrl", "");
    json result;
    try {
        fs::path work = fs::temp_directory_path() / ("skyscan-job-" + jobId);
        fs::create_directories(work);

        std::vector<JobInput> inputs;
        int i = 0;
        for (const auto& in : job.at("inputs")) {
            JobInput ji;
            ji.acquisitionId = in.value("acquisitionId", "");
            ji.sensorType    = in.value("sensorType", "FIXED_3D");
            ji.passType      = in.value("passType", "CRUDE");
            ji.localPath     = (work / ("input_" + std::to_string(i++) + ".las")).string();
            logln("job " + jobId + ": downloading input " + ji.acquisitionId + " (" + ji.sensorType + ")");
            if (!httpDownload(in.at("getUrl").get<std::string>(), ji.localPath))
                throw std::runtime_error("download failed for acquisition " + ji.acquisitionId);
            inputs.push_back(std::move(ji));
        }

        std::string lasOut  = (work / "merged.las").string();
        std::string lazOut  = (work / "merged.laz").string();
        std::string copcOut = (work / "merged.copc.laz").string();

        const std::string mode = job.value("mode", "MERGE");
        PipelineResult r = (mode == "FINALIZE" && !inputs.empty())
            ? runFinalize(inputs.front().localPath, job.value("targetCrs", "EPSG:32634"), lasOut, lazOut, copcOut)
            : runPipeline(inputs, job.value("targetCrs", "EPSG:32634"), lasOut, lazOut, copcOut);

        const auto& outs = job.at("outputs");
        logln("job " + jobId + ": uploading outputs");
        if (!httpUpload(outs.at("lasPutUrl"),  lasOut,  "application/octet-stream")) throw std::runtime_error("LAS upload failed");
        if (!httpUpload(outs.at("lazPutUrl"),  lazOut,  "application/octet-stream")) throw std::runtime_error("LAZ upload failed");
        if (!httpUpload(outs.at("copcPutUrl"), copcOut, "application/octet-stream")) throw std::runtime_error("COPC upload failed");

        result = {
            {"status", "COMPLETED"},
            {"pointCount", r.pointCount},
            {"crs", r.crs},
            {"message", r.message},
            {"bounds", {{"minX", r.minx}, {"minY", r.miny}, {"minZ", r.minz},
                        {"maxX", r.maxx}, {"maxY", r.maxy}, {"maxZ", r.maxz}}}
        };
        fs::remove_all(work);
        logln("job " + jobId + " COMPLETED (" + std::to_string(r.pointCount) + " points)");
    } catch (const std::exception& e) {
        result = {{"status", "FAILED"}, {"message", e.what()}};
        logln("job " + jobId + " FAILED: " + std::string(e.what()));
    }

    if (!callbackUrl.empty()) {
        if (!httpPostJson(callbackUrl, result.dump(), internalToken))
            logln("job " + jobId + ": WARNING — callback to " + callbackUrl + " failed");
    }
}

// ---------------------------------------------------------------- entry points

static int runCli(int argc, char** argv) {
    if (argc < 5) {
        std::cerr << "usage: skyscan-engine --cli <crude.las|-> <detailed.las|-> <outdir>\n";
        return 2;
    }
    std::vector<JobInput> inputs;
    if (std::string(argv[2]) != "-") inputs.push_back({"cli-crude", "FIXED_3D", "CRUDE", argv[2]});
    if (std::string(argv[3]) != "-") inputs.push_back({"cli-detail", "SPINNING_TILTED", "DETAILED", argv[3]});
    fs::path out(argv[4]);
    fs::create_directories(out);
    PipelineResult r = runPipeline(inputs, "EPSG:32634",
                                   (out / "merged.las").string(),
                                   (out / "merged.laz").string(),
                                   (out / "merged.copc.laz").string());
    std::cout << json({{"pointCount", r.pointCount}, {"message", r.message}}).dump(2) << std::endl;
    return 0;
}

int main(int argc, char** argv) {
    curl_global_init(CURL_GLOBAL_DEFAULT);
    if (argc > 1 && std::string(argv[1]) == "--cli") return runCli(argc, argv);

    const char* portEnv = std::getenv("ENGINE_PORT");
    const int port = portEnv ? std::atoi(portEnv) : 9090;
    const char* tokenEnv = std::getenv("INTERNAL_TOKEN");
    const std::string internalToken = tokenEnv ? tokenEnv : "skyscan-internal-demo-token";

    httplib::Server server;

    server.Get("/health", [](const httplib::Request&, httplib::Response& res) {
        res.set_content("{\"status\":\"UP\",\"service\":\"skyscan-engine\"}", "application/json");
    });

    server.Post("/jobs", [&](const httplib::Request& req, httplib::Response& res) {
        json job;
        try { job = json::parse(req.body); }
        catch (...) { res.status = 400; res.set_content("{\"error\":\"bad json\"}", "application/json"); return; }
        int n = ++jobCounter;
        logln("accepted job " + job.value("jobId", "?") + " (#" + std::to_string(n) + ")");
        std::thread([job, internalToken] { runJob(job, internalToken); }).detach();
        res.status = 202;
        res.set_content("{\"accepted\":true}", "application/json");
    });

    logln("SkyScan engine listening on :" + std::to_string(port));
    server.listen("0.0.0.0", port);
    curl_global_cleanup();
    return 0;
}
