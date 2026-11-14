package com.skyscan.connector;

import com.skyscan.domain.PassType;
import com.skyscan.domain.SensorType;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Delivered by the connector after landing: the raw scan file on local disk
 * plus capture metadata. previewFile is non-null only for the crude pass.
 * photoFiles: geotagged customer photos captured by the onboard camera.
 */
public record ScanUpload(
        UUID missionId,
        PassType passType,
        SensorType sensorType,
        Path scanFile,
        Path previewFile,
        String gnssImuMetaJson,
        List<Path> photoFiles) {}
