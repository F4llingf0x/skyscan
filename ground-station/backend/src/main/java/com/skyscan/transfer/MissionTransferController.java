package com.skyscan.transfer;

import com.skyscan.auth.CurrentUser;
import com.skyscan.domain.User;
import com.skyscan.repo.MissionRepo;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;
import java.util.UUID;

/**
 * Field → office transfer endpoints.
 *
 *   GET  /api/missions/{id}/export   stream the mission as a portable ZIP
 *                                    (metadata + every stored object)
 *   POST /api/missions/import        multipart "file": re-create the mission
 *                                    on this instance (new ids, keys rewritten)
 *
 * Intended flow: run the full stack offline on the site LAN, export the
 * finished mission to a file (from the mission page or curl), bring it back,
 * import it into the office/central instance. Staff only.
 */
@RestController
@RequestMapping("/api/missions")
public class MissionTransferController {

    private final MissionTransferService transfer;
    private final MissionRepo missions;
    private final CurrentUser current;

    public MissionTransferController(MissionTransferService transfer, MissionRepo missions,
                                     CurrentUser current) {
        this.transfer = transfer; this.missions = missions; this.current = current;
    }

    @GetMapping(value = "/{id}/export", produces = "application/zip")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable UUID id) {
        var mission = missions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such mission"));
        String filename = "skyscan_%s_%s.zip".formatted(
                mission.getName().replaceAll("[^A-Za-z0-9_-]", "_"), id.toString().substring(0, 8));
        StreamingResponseBody body = out -> transfer.export(id, out);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Map<String, Object> importBundle(@RequestParam("file") MultipartFile file) {
        User u = current.require();
        try (var in = file.getInputStream()) {
            var r = transfer.importBundle(in, u.getOrganization());
            return Map.of("missionId", r.missionId(), "siteId", r.siteId(),
                          "missionName", r.missionName(), "objects", r.objects(),
                          "sourceMissionId", r.sourceMissionId());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Import failed: " + e.getMessage(), e);
        }
    }
}
