package com.skyscan.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyscan.domain.*;
import com.skyscan.repo.ProcessingJobRepo;
import com.skyscan.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Dispatches processing jobs to the C++ engine over REST (asynchronous: the engine
 * replies 202 and calls back /internal/jobs/{id}/callback when done or failed).
 * Inputs/outputs travel as presigned object-storage URLs, so the engine needs no
 * storage credentials of its own.
 */
@Service
public class ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);
    private static final int URL_TTL_MIN = 120;

    private final ProcessingJobRepo jobs;
    private final StorageService storage;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String engineUrl;
    private final String callbackBase;
    private final String crs;

    public ProcessingService(ProcessingJobRepo jobs, StorageService storage,
                             @Value("${skyscan.engine.url}") String engineUrl,
                             @Value("${skyscan.engine.callback-base}") String callbackBase,
                             @Value("${skyscan.processing.crs}") String crs) {
        this.jobs = jobs; this.storage = storage;
        this.engineUrl = engineUrl; this.callbackBase = callbackBase; this.crs = crs;
    }

    /** Create + dispatch a clean/georeference/convert/merge job covering all acquisitions of a mission. */
    public ProcessingJob dispatchMergeJob(Mission mission, List<Acquisition> acqs) {
        ProcessingJob job = new ProcessingJob();
        job.setMission(mission);
        job.setStatus(JobStatus.PENDING);

        String base = "processed/%s/%s".formatted(mission.getId(), System.currentTimeMillis());
        String lasKey = base + "/merged.las";
        String lazKey = base + "/merged.laz";
        String copcKey = base + "/merged.copc.laz";

        ObjectNode payload = mapper.createObjectNode();
        ArrayNode inputs = payload.putArray("inputs");
        ArrayNode inputRefs = mapper.createArrayNode();
        for (Acquisition a : acqs) {
            ObjectNode in = inputs.addObject();
            in.put("acquisitionId", a.getId().toString());
            in.put("sensorType", a.getSensorType().name());
            in.put("passType", a.getPassType().name());
            in.put("getUrl", storage.presignInternalGet(a.getRawObjectKey(), URL_TTL_MIN));
            in.put("gnssImuMeta", a.getGnssImuMetaJson() == null ? "{}" : a.getGnssImuMetaJson());
            ObjectNode ref = inputRefs.addObject();
            ref.put("acquisitionId", a.getId().toString());
            ref.put("objectKey", a.getRawObjectKey());
        }
        job.setInputRefsJson(inputRefs.toString());

        ObjectNode outRefs = mapper.createObjectNode();
        outRefs.put("las", lasKey); outRefs.put("laz", lazKey); outRefs.put("copc", copcKey);
        job.setOutputRefsJson(outRefs.toString());
        job = jobs.save(job);

        payload.put("jobId", job.getId().toString());
        payload.put("missionId", mission.getId().toString());
        payload.put("mode", "MERGE");
        payload.put("targetCrs", crs);
        ObjectNode outs = payload.putObject("outputs");
        outs.put("lasPutUrl", storage.presignInternalPut(lasKey, URL_TTL_MIN));
        outs.put("lazPutUrl", storage.presignInternalPut(lazKey, URL_TTL_MIN));
        outs.put("copcPutUrl", storage.presignInternalPut(copcKey, URL_TTL_MIN));
        payload.put("callbackUrl", callbackBase + "/internal/jobs/" + job.getId() + "/callback");

        sendToEngine(job.getId().toString(), payload.toString());
        job.setStatus(JobStatus.DISPATCHED);
        return jobs.save(job);
    }

    /**
     * FINALIZE: the operator edited a delivered cloud in the review editor; the edited
     * LAS is already in object storage. The engine re-reads it and regenerates the
     * LAZ + COPC (no cleaning/merging — the edit is authoritative), then calls back.
     */
    public ProcessingJob dispatchFinalizeJob(com.skyscan.domain.PointCloudAsset asset, String editedObjectKey) {
        Mission mission = asset.getMission();
        ProcessingJob job = new ProcessingJob();
        job.setMission(mission);
        job.setStatus(JobStatus.PENDING);
        job.setJobType("FINALIZE");
        job.setAssetId(asset.getId());

        String base = "processed/%s/%s-edited".formatted(mission.getId(), System.currentTimeMillis());
        String lasKey = base + "/final.las";
        String lazKey = base + "/final.laz";
        String copcKey = base + "/final.copc.laz";

        ObjectNode payload = mapper.createObjectNode();
        payload.put("mode", "FINALIZE");
        payload.put("missionId", mission.getId().toString());
        payload.put("targetCrs", crs);
        ArrayNode inputs = payload.putArray("inputs");
        ObjectNode in = inputs.addObject();
        in.put("acquisitionId", asset.getId().toString());
        in.put("sensorType", "FIXED_3D");     // ignored in FINALIZE mode
        in.put("passType", "DETAILED");
        in.put("getUrl", storage.presignInternalGet(editedObjectKey, URL_TTL_MIN));

        ObjectNode inputRefs = mapper.createObjectNode();
        inputRefs.put("editedObjectKey", editedObjectKey);
        job.setInputRefsJson(inputRefs.toString());
        ObjectNode outRefs = mapper.createObjectNode();
        outRefs.put("las", lasKey); outRefs.put("laz", lazKey); outRefs.put("copc", copcKey);
        job.setOutputRefsJson(outRefs.toString());
        job = jobs.save(job);

        payload.put("jobId", job.getId().toString());
        ObjectNode outs = payload.putObject("outputs");
        outs.put("lasPutUrl", storage.presignInternalPut(lasKey, URL_TTL_MIN));
        outs.put("lazPutUrl", storage.presignInternalPut(lazKey, URL_TTL_MIN));
        outs.put("copcPutUrl", storage.presignInternalPut(copcKey, URL_TTL_MIN));
        payload.put("callbackUrl", callbackBase + "/internal/jobs/" + job.getId() + "/callback");

        sendToEngine(job.getId().toString(), payload.toString());
        job.setStatus(JobStatus.DISPATCHED);
        return jobs.save(job);
    }

    private void sendToEngine(String jobId, String payloadJson) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(engineUrl + "/jobs"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2)
                throw new RuntimeException("Engine returned HTTP " + res.statusCode() + ": " + res.body());
            log.info("Job {} dispatched to engine", jobId);
        } catch (Exception e) {
            // Callback controller path also covers failures; mark here so the UI is not stuck.
            log.error("Failed to dispatch job {}: {}", jobId, e.getMessage());
            jobs.findById(java.util.UUID.fromString(jobId)).ifPresent(j -> {
                j.setStatus(JobStatus.FAILED);
                j.setMessage("Dispatch failed: " + e.getMessage());
                j.setFinishedAt(java.time.Instant.now());
                jobs.save(j);
            });
            throw new RuntimeException(e);
        }
    }
}
