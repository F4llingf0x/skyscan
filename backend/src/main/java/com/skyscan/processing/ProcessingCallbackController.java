package com.skyscan.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.skyscan.domain.*;
import com.skyscan.mission.MissionService;
import com.skyscan.repo.PointCloudAssetRepo;
import com.skyscan.repo.ProcessingJobRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoint the C++ engine calls when a job finishes.
 * Guarded by the X-Internal-Token shared secret (see InternalTokenFilter).
 */
@RestController
@RequestMapping("/internal/jobs")
public class ProcessingCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ProcessingCallbackController.class);

    private final ProcessingJobRepo jobs;
    private final PointCloudAssetRepo assets;
    private final MissionService missionService;

    public ProcessingCallbackController(ProcessingJobRepo jobs, PointCloudAssetRepo assets,
                                        MissionService missionService) {
        this.jobs = jobs; this.assets = assets; this.missionService = missionService;
    }

    @PostMapping("/{jobId}/callback")
    @Transactional
    public Map<String, String> callback(@PathVariable UUID jobId, @RequestBody JsonNode body) {
        ProcessingJob job = jobs.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job"));
        String status = body.path("status").asText("FAILED");
        Mission mission = job.getMission();

        if ("COMPLETED".equals(status)) {
            job.setStatus(JobStatus.COMPLETED);
            job.setMessage(body.path("message").asText(""));
            job.setFinishedAt(Instant.now());
            jobs.save(job);

            JsonNode outputs;
            try { outputs = new com.fasterxml.jackson.databind.ObjectMapper().readTree(job.getOutputRefsJson()); }
            catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Bad output refs"); }

            if ("FINALIZE".equals(job.getJobType())) {
                // Operator-edited cloud reprocessed: update the existing asset in place.
                PointCloudAsset asset = assets.findById(job.getAssetId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset gone"));
                applyOutputs(asset, outputs, body);
                asset.setStatus("REVIEW");    // stays in review until the operator publishes
                assets.save(asset);
                log.info("FINALIZE job {} applied to asset {}: {} points", jobId, asset.getId(), asset.getPointCount());
            } else {
                PointCloudAsset asset = new PointCloudAsset();
                asset.setMission(mission);
                asset.setName(mission.getName() + " — merged point cloud");
                applyOutputs(asset, outputs, body);
                asset.setStatus("REVIEW");    // operator reviews/edits before the customer sees it
                assets.save(asset);
                missionService.onProcessingComplete(mission.getId());
                log.info("Job {} completed: {} points (awaiting operator review)", jobId, asset.getPointCount());
            }
        } else {
            String err = body.path("message").asText("unknown engine error");
            job.setStatus(JobStatus.FAILED);
            job.setMessage(err);
            job.setFinishedAt(Instant.now());
            jobs.save(job);
            missionService.onProcessingFailed(mission.getId(), err);
        }
        return Map.of("ok", "true");
    }

    private void applyOutputs(PointCloudAsset asset, JsonNode outputs, JsonNode body) {
        asset.setLasObjectKey(outputs.path("las").asText());
        asset.setLazObjectKey(outputs.path("laz").asText());
        asset.setCopcObjectKey(outputs.path("copc").asText());
        asset.setPointCount(body.path("pointCount").asLong(0));
        JsonNode b = body.path("bounds");
        asset.setMinX(b.path("minX").asDouble()); asset.setMinY(b.path("minY").asDouble());
        asset.setMinZ(b.path("minZ").asDouble()); asset.setMaxX(b.path("maxX").asDouble());
        asset.setMaxY(b.path("maxY").asDouble()); asset.setMaxZ(b.path("maxZ").asDouble());
        asset.setCrs(body.path("crs").asText(""));
    }
}
