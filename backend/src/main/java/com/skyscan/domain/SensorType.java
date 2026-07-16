package com.skyscan.domain;
/**
 * Physical LiDAR payloads the drone can be equipped with:
 *  FIXED_3D           — rigid downward 3D lidar (crude overview pass).
 *  SPINNING_TILTED    — downward-facing, ±30° tilt spinning payload (detail pass, LANDSCAPE mode).
 *  FRONT_360_VERTICAL — front-facing 360° scan in the vertical plane, ±30° tilt
 *                       (detail pass, STRUCTURE mode: full scans of buildings, statues, facades).
 */
public enum SensorType { FIXED_3D, SPINNING_TILTED, FRONT_360_VERTICAL }
