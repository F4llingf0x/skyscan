package com.skyscan.dto;

import com.skyscan.domain.*;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

/** Request/response DTOs for the public API. */
public final class Dtos {
    private Dtos() {}

    // ---- requests
    public record CreateSiteRequest(@NotBlank String name, String description,
                                    @NotNull UUID organizationId, double centerLat, double centerLon) {}

    public record CreateMissionRequest(@NotBlank String name, @NotNull UUID siteId,
                                       @NotBlank String polygonGeoJson,
                                       @Positive double altitudeM, @Positive double lineSpacingM,
                                       @Positive double speedMps) {}

    public record RefineRequest(@NotBlank String polygonGeoJson,
                                @Positive double altitudeM, @Positive double lineSpacingM,
                                @Positive double speedMps, boolean terrainFollowing) {}

    public record AbortRequest(String reason) {}

    // ---- responses
    public record SiteView(UUID id, String name, String description, String organization,
                           double centerLat, double centerLon, Instant createdAt) {
        public static SiteView of(Site s) {
            return new SiteView(s.getId(), s.getName(), s.getDescription(),
                    s.getOrganization().getName(), s.getCenterLat(), s.getCenterLon(), s.getCreatedAt());
        }
    }

    public record ScanAreaView(PassType passType, String polygonGeoJson, double altitudeM,
                               double lineSpacingM, double speedMps, boolean terrainFollowing,
                               String routeJson) {
        public static ScanAreaView of(ScanArea a) {
            return new ScanAreaView(a.getPassType(), a.getPolygonGeoJson(), a.getAltitudeM(),
                    a.getLineSpacingM(), a.getSpeedMps(), a.isTerrainFollowing(), a.getRouteJson());
        }
    }

    public record RefineResponse(MissionView mission, String routeGeoJson,
                                 double routeLengthM, int routeLineCount, double estDurationS) {}

    public record MissionView(UUID id, String name, UUID siteId, String siteName, MissionState state,
                              String lastError, Instant createdAt, Instant updatedAt) {
        public static MissionView of(Mission m) {
            return new MissionView(m.getId(), m.getName(), m.getSite().getId(), m.getSite().getName(),
                    m.getState(), m.getLastError(), m.getCreatedAt(), m.getUpdatedAt());
        }
    }

    public record AssetView(UUID id, UUID missionId, String name, long pointCount, String crs,
                            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            String status, Instant createdAt) {
        public static AssetView of(PointCloudAsset a) {
            return new AssetView(a.getId(), a.getMission().getId(), a.getName(), a.getPointCount(), a.getCrs(),
                    a.getMinX(), a.getMinY(), a.getMinZ(), a.getMaxX(), a.getMaxY(), a.getMaxZ(),
                    a.getStatus(), a.getCreatedAt());
        }
    }
}
