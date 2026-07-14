package com.skyscan.connector;

import com.skyscan.domain.PassType;
import com.skyscan.domain.SensorType;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Delivered by the connector after landing: the raw scan file on local disk
 * plus capture metadata. previewFile is non-null only for the crude pass.
 */
public record ScanUpload(
        UUID missionId,
        PassType passType,
        SensorType sensorType,
        Path scanFile,
        Path previewFile,
        String gnssImuMetaJson) {}
