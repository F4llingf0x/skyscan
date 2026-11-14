package com.skyscan.asset;

import com.skyscan.auth.CurrentUser;
import com.skyscan.domain.PointCloudAsset;
import com.skyscan.dto.Dtos.AssetView;
import com.skyscan.processing.ProcessingService;
import com.skyscan.repo.PointCloudAssetRepo;
import com.skyscan.repo.SiteRepo;
import com.skyscan.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AssetController {

    private static final int VIEW_TTL_MIN = 60;

    private final PointCloudAssetRepo assets;
    private final SiteRepo sites;
    private final StorageService storage;
    private final CurrentUser current;
    private final ProcessingService processing;

    public AssetController(PointCloudAssetRepo assets, SiteRepo sites, StorageService storage,
                           CurrentUser current, ProcessingService processing) {
        this.assets = assets; this.sites = sites; this.storage = storage; this.current = current;
        this.processing = processing;
    }

    @GetMapping("/sites/{siteId}/assets")
    public List<AssetView> listForSite(@PathVariable UUID siteId) {
        var site = sites.findById(siteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
        var user = current.require();
        current.checkSiteAccess(user, site);
        boolean staff = current.isStaff(user);
        return assets.findByMissionSiteIdOrderByCreatedAtDesc(siteId).stream()
                .filter(a -> staff || "READY".equals(a.getStatus()))   // customers see only published clouds
                .map(AssetView::of).toList();
    }

    @GetMapping("/assets/{assetId}")
    public AssetView get(@PathVariable UUID assetId) { return AssetView.of(require(assetId)); }

    /**
     * Operator review flow: upload the edited cloud (raw LAS bytes) — stored to object
     * storage, then a FINALIZE job regenerates LAZ/COPC and updates the asset in place.
     */
    @PostMapping(value = "/assets/{assetId}/edited", consumes = "application/octet-stream")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Map<String, Object> uploadEdited(@PathVariable UUID assetId, @RequestBody byte[] lasBytes) {
        PointCloudAsset a = require(assetId);
        try {
            Path tmp = Files.createTempFile("skyscan-edited-", ".las");
            Files.write(tmp, lasBytes);
            String key = "edited/%s/%s.las".formatted(assetId, System.currentTimeMillis());
            storage.uploadFile(key, tmp, "application/octet-stream");
            Files.deleteIfExists(tmp);
            var job = processing.dispatchFinalizeJob(a, key);
            return Map.of("jobId", job.getId(), "status", "DISPATCHED");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Edited upload failed: " + e.getMessage());
        }
    }

    /** Operator sign-off: the cloud becomes visible to the customer. */
    @PostMapping("/assets/{assetId}/publish")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public AssetView publish(@PathVariable UUID assetId) {
        PointCloudAsset a = require(assetId);
        a.setStatus("READY");
        return AssetView.of(assets.save(a));
    }

    /**
     * Presigned, range-request-capable URLs for streaming into the viewer and downloading.
     *  - copcUrl: COPC stream (Potree / COPC-aware viewers)
     *  - lasUrl:  processed LAS for the built-in fallback viewer
     *  - lazUrl:  LAZ download
     */
    @GetMapping("/assets/{assetId}/urls")
    public Map<String, Object> urls(@PathVariable UUID assetId) {
        PointCloudAsset a = require(assetId);
        return Map.of(
                "copcUrl", storage.presignExternalGet(a.getCopcObjectKey(), VIEW_TTL_MIN),
                "lasUrl", storage.presignExternalGet(a.getLasObjectKey(), VIEW_TTL_MIN),
                "lazUrl", storage.presignExternalGet(a.getLazObjectKey(), VIEW_TTL_MIN),
                "asset", AssetView.of(a));
    }

    private PointCloudAsset require(UUID assetId) {
        PointCloudAsset a = assets.findById(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        var user = current.require();
        current.checkSiteAccess(user, a.getMission().getSite());
        // Unpublished clouds are operator-only.
        if (!current.isStaff(user) && !"READY".equals(a.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not yet published");
        return a;
    }
}
