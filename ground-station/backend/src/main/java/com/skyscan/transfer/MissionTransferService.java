package com.skyscan.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skyscan.domain.*;
import com.skyscan.repo.*;
import com.skyscan.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Field → office data transfer ("sneakernet or later-upload sync").
 *
 * The field workflow runs the WHOLE stack offline on a site LAN (laptop +
 * router + drone). This service packages one finished mission into a single
 * portable ZIP and re-creates it on another SkyScan instance:
 *
 *   bundle layout:
 *     manifest.json            site/mission/areas/acquisitions/assets metadata
 *     objects/<objectKey>      every MinIO object the mission owns
 *                              (raw scans, preview, LAS/LAZ/COPC deliverables,
 *                              photos/{id}/*, logs/{id}/*)
 *
 * Import creates NEW entity ids (JPA generates them) and REWRITES the old
 * mission UUID inside every object key so photo/log prefix listings keep
 * working on the target instance. The original ids are preserved in the
 * manifest (and echoed in the import response) for traceability. Importing
 * the same bundle twice creates two independent copies — the target instance
 * is authoritative; dedupe by mission name if it matters.
 */
@Service
public class MissionTransferService {

    private static final Logger log = LoggerFactory.getLogger(MissionTransferService.class);
    private static final ObjectMapper M = new ObjectMapper();
    public static final int FORMAT_VERSION = 1;

    private final MissionRepo missions;
    private final SiteRepo sites;
    private final OrganizationRepo orgs;
    private final ScanAreaRepo areas;
    private final AcquisitionRepo acquisitions;
    private final PointCloudAssetRepo assets;
    private final StorageService storage;

    public MissionTransferService(MissionRepo missions, SiteRepo sites, OrganizationRepo orgs,
                                  ScanAreaRepo areas, AcquisitionRepo acquisitions,
                                  PointCloudAssetRepo assets, StorageService storage) {
        this.missions = missions; this.sites = sites; this.orgs = orgs;
        this.areas = areas; this.acquisitions = acquisitions;
        this.assets = assets; this.storage = storage;
    }

    // ------------------------------------------------------------------ export

    @Transactional(readOnly = true)
    public void export(UUID missionId, OutputStream out) throws IOException {
        Mission m = missions.findById(missionId)
                .orElseThrow(() -> new IllegalArgumentException("No such mission"));
        List<String> objectKeys = new ArrayList<>();
        ObjectNode manifest = manifest(m, objectKeys);

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(M.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
            for (String key : objectKeys) {
                zip.putNextEntry(new ZipEntry("objects/" + key));
                try (InputStream in = storage.download(key)) {
                    in.transferTo(zip);
                } catch (Exception e) {
                    // Bundle what exists; a missing object must not sink the export.
                    log.warn("export: skipping missing object {}: {}", key, e.getMessage());
                }
                zip.closeEntry();
            }
        }
        log.info("exported mission {} ({} objects)", missionId, objectKeys.size());
    }

    private ObjectNode manifest(Mission m, List<String> objectKeys) {
        ObjectNode root = M.createObjectNode();
        root.put("formatVersion", FORMAT_VERSION);
        root.put("exportedAt", Instant.now().toString());

        Site s = m.getSite();
        ObjectNode site = root.putObject("site");
        site.put("id", s.getId().toString());
        site.put("name", s.getName());
        site.put("description", s.getDescription());
        site.put("organization", s.getOrganization().getName());
        site.put("centerLat", s.getCenterLat());
        site.put("centerLon", s.getCenterLon());

        ObjectNode mission = root.putObject("mission");
        mission.put("id", m.getId().toString());
        mission.put("name", m.getName());
        mission.put("state", m.getState().name());
        mission.put("scanMode", m.getScanMode().name());
        mission.put("createdAt", m.getCreatedAt().toString());
        if (m.getPreviewObjectKey() != null) {
            mission.put("previewObjectKey", m.getPreviewObjectKey());
            objectKeys.add(m.getPreviewObjectKey());
        }

        ArrayNode areasN = root.putArray("scanAreas");
        for (PassType pt : PassType.values()) {
            areas.findByMissionIdAndPassType(m.getId(), pt).ifPresent(a -> {
                ObjectNode n = areasN.addObject();
                n.put("passType", a.getPassType().name());
                n.put("polygonGeoJson", a.getPolygonGeoJson());
                n.put("altitudeM", a.getAltitudeM());
                n.put("lineSpacingM", a.getLineSpacingM());
                n.put("speedMps", a.getSpeedMps());
                n.put("terrainFollowing", a.isTerrainFollowing());
                n.put("standoffM", a.getStandoffM());
                n.put("tiltDeg", a.getTiltDeg());
                if (a.getRouteJson() != null) n.put("routeJson", a.getRouteJson());
            });
        }

        ArrayNode acqN = root.putArray("acquisitions");
        for (Acquisition a : acquisitions.findByMissionIdOrderByCapturedAtAsc(m.getId())) {
            ObjectNode n = acqN.addObject();
            n.put("passType", a.getPassType().name());
            n.put("sensorType", a.getSensorType().name());
            n.put("rawObjectKey", a.getRawObjectKey());
            n.put("capturedAt", a.getCapturedAt().toString());
            if (a.getGnssImuMetaJson() != null) n.put("gnssImuMetaJson", a.getGnssImuMetaJson());
            objectKeys.add(a.getRawObjectKey());
        }

        ArrayNode assetN = root.putArray("assets");
        for (PointCloudAsset a : assets.findByMissionIdOrderByCreatedAtDesc(m.getId())) {
            ObjectNode n = assetN.addObject();
            n.put("name", a.getName());
            n.put("status", a.getStatus());
            n.put("pointCount", a.getPointCount());
            n.put("crs", a.getCrs());
            n.put("minX", a.getMinX()); n.put("minY", a.getMinY()); n.put("minZ", a.getMinZ());
            n.put("maxX", a.getMaxX()); n.put("maxY", a.getMaxY()); n.put("maxZ", a.getMaxZ());
            for (String k : new String[]{"las", "laz", "copc"}) {
                String key = switch (k) {
                    case "las" -> a.getLasObjectKey();
                    case "laz" -> a.getLazObjectKey();
                    default -> a.getCopcObjectKey();
                };
                if (key != null) { n.put(k + "ObjectKey", key); objectKeys.add(key); }
            }
        }

        List<String> extra = new ArrayList<>();
        extra.addAll(storage.listKeys("photos/" + m.getId() + "/"));
        extra.addAll(storage.listKeys("logs/" + m.getId() + "/"));
        ArrayNode extraN = root.putArray("extraObjects");
        for (String k : extra) { extraN.add(k); objectKeys.add(k); }
        return root;
    }

    // ------------------------------------------------------------------ import

    public record ImportResult(UUID missionId, UUID siteId, String missionName,
                               int objects, String sourceMissionId) {}

    @Transactional
    public ImportResult importBundle(InputStream zipStream, Organization fallbackOrg) throws IOException {
        // Spool: entries may arrive in any order; objects can be large.
        Path tmp = Files.createTempDirectory("skyscan-import-");
        JsonNode manifest = null;
        Map<String, Path> objects = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(zipStream)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.contains("..")) throw new IOException("Path traversal in bundle: " + name);
                if (name.equals("manifest.json")) {
                    manifest = M.readTree(zip.readAllBytes());
                } else if (name.startsWith("objects/")) {
                    Path f = tmp.resolve(UUID.randomUUID().toString());
                    Files.copy(zip, f);
                    objects.put(name.substring("objects/".length()), f);
                }
            }
        }
        if (manifest == null) throw new IOException("Bundle has no manifest.json");
        if (manifest.path("formatVersion").asInt(-1) != FORMAT_VERSION)
            throw new IOException("Unsupported bundle formatVersion");

        try {
            return materialize(manifest, objects, fallbackOrg);
        } finally {
            for (Path f : objects.values()) Files.deleteIfExists(f);
            Files.deleteIfExists(tmp);
        }
    }

    private ImportResult materialize(JsonNode man, Map<String, Path> objects,
                                     Organization fallbackOrg) {
        JsonNode sn = man.get("site");
        Organization org = orgs.findAll().stream()
                .filter(o -> o.getName().equals(sn.path("organization").asText()))
                .findFirst().orElse(fallbackOrg);
        if (org == null)
            throw new IllegalArgumentException("Bundle organization '"
                    + sn.path("organization").asText() + "' does not exist here and the "
                    + "importing user has none to fall back to — create the organization first");
        Site site = sites.findAll().stream()
                .filter(s -> s.getName().equals(sn.path("name").asText())
                        && s.getOrganization().getId().equals(org.getId()))
                .findFirst().orElseGet(() -> {
                    Site s = new Site();
                    s.setName(sn.path("name").asText("Imported site"));
                    s.setDescription(sn.path("description").asText(null));
                    s.setOrganization(org);
                    s.setCenterLat(sn.path("centerLat").asDouble());
                    s.setCenterLon(sn.path("centerLon").asDouble());
                    return sites.save(s);
                });

        JsonNode mn = man.get("mission");
        String oldMissionId = mn.path("id").asText();
        Mission m = new Mission();
        m.setName(mn.path("name").asText("Imported mission"));
        m.setSite(site);
        m.setScanMode(ScanMode.valueOf(mn.path("scanMode").asText("LANDSCAPE")));
        m.setState(MissionState.valueOf(mn.path("state").asText("COMPLETE")));
        m = missions.save(m);
        String newMissionId = m.getId().toString();

        // Old mission UUID -> new one inside every object key, so prefix
        // listings (photos/{id}/, logs/{id}/) keep working here.
        Map<String, String> keyMap = new LinkedHashMap<>();
        for (String oldKey : objects.keySet())
            keyMap.put(oldKey, oldKey.replace(oldMissionId, newMissionId));

        if (mn.hasNonNull("previewObjectKey"))
            m.setPreviewObjectKey(keyMap.getOrDefault(mn.get("previewObjectKey").asText(),
                    mn.get("previewObjectKey").asText()));

        for (JsonNode a : man.withArray("scanAreas")) {
            ScanArea sa = new ScanArea();
            sa.setMission(m);
            sa.setPassType(PassType.valueOf(a.path("passType").asText()));
            sa.setPolygonGeoJson(a.path("polygonGeoJson").asText());
            sa.setAltitudeM(a.path("altitudeM").asDouble());
            sa.setLineSpacingM(a.path("lineSpacingM").asDouble());
            sa.setSpeedMps(a.path("speedMps").asDouble());
            sa.setTerrainFollowing(a.path("terrainFollowing").asBoolean());
            sa.setStandoffM(a.path("standoffM").asDouble());
            sa.setTiltDeg(a.path("tiltDeg").asDouble());
            if (a.hasNonNull("routeJson")) sa.setRouteJson(a.get("routeJson").asText());
            areas.save(sa);
        }

        for (JsonNode a : man.withArray("acquisitions")) {
            Acquisition acq = new Acquisition();
            acq.setMission(m);
            acq.setPassType(PassType.valueOf(a.path("passType").asText()));
            acq.setSensorType(SensorType.valueOf(a.path("sensorType").asText()));
            acq.setRawObjectKey(keyMap.getOrDefault(a.path("rawObjectKey").asText(),
                    a.path("rawObjectKey").asText()));
            if (a.hasNonNull("gnssImuMetaJson")) acq.setGnssImuMetaJson(a.get("gnssImuMetaJson").asText());
            acquisitions.save(acq);
        }

        for (JsonNode a : man.withArray("assets")) {
            PointCloudAsset pa = new PointCloudAsset();
            pa.setMission(m);
            pa.setName(a.path("name").asText("Imported cloud"));
            pa.setStatus(a.path("status").asText("READY"));
            pa.setPointCount(a.path("pointCount").asLong());
            pa.setCrs(a.path("crs").asText(null));
            pa.setMinX(a.path("minX").asDouble()); pa.setMinY(a.path("minY").asDouble());
            pa.setMinZ(a.path("minZ").asDouble()); pa.setMaxX(a.path("maxX").asDouble());
            pa.setMaxY(a.path("maxY").asDouble()); pa.setMaxZ(a.path("maxZ").asDouble());
            if (a.hasNonNull("lasObjectKey"))
                pa.setLasObjectKey(keyMap.getOrDefault(a.get("lasObjectKey").asText(), a.get("lasObjectKey").asText()));
            if (a.hasNonNull("lazObjectKey"))
                pa.setLazObjectKey(keyMap.getOrDefault(a.get("lazObjectKey").asText(), a.get("lazObjectKey").asText()));
            if (a.hasNonNull("copcObjectKey"))
                pa.setCopcObjectKey(keyMap.getOrDefault(a.get("copcObjectKey").asText(), a.get("copcObjectKey").asText()));
            assets.save(pa);
        }

        int uploaded = 0;
        for (Map.Entry<String, Path> e : objects.entrySet()) {
            storage.uploadFile(keyMap.get(e.getKey()), e.getValue(), contentType(e.getKey()));
            uploaded++;
        }
        log.info("imported mission '{}' as {} (source {}, {} objects)",
                m.getName(), newMissionId, oldMissionId, uploaded);
        return new ImportResult(m.getId(), site.getId(), m.getName(), uploaded, oldMissionId);
    }

    private static String contentType(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.endsWith(".jpg") || k.endsWith(".jpeg")) return "image/jpeg";
        if (k.endsWith(".png")) return "image/png";
        if (k.endsWith(".jsonl") || k.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
