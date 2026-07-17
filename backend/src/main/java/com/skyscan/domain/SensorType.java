package com.skyscan.domain;
/**
 * Payload configurations. The physical sensor is ONE front-facing RPLidar C1:
 * a 2D scanner whose 360° plane is VERTICAL (up/right/down/left around the
 * aircraft), pitchable ±30° about the lateral axis — the tilt angle, fixed per
 * flight, is what distinguishes the modes:
 *  FIXED_3D           — crude overview pass: ring near vertical, lawnmower
 *                       pushbroom over the zone (terrain swath below + sides).
 *  SPINNING_TILTED    — LANDSCAPE detail pass: ring tilted (typically -30°,
 *                       bottom leaning forward) for terrain profiling with
 *                       forward-down look-ahead.
 *  WILDLIFE_CAM       — WILDLIFE_SURVEY coverage pass: RGB + thermal cameras
 *                       feeding the on-board animal detector; lidar still runs.
 *  PLANT_INSPECT      — CROP_HEALTH detail pass: low/slow per-plant visit route,
 *                       camera close-ups + lidar at tilt 0 (canopy profile).
 *  FRONT_360_VERTICAL — STRUCTURE detail pass: vertical ring, tangent orbits;
 *                       the facade abeam is painted floor-to-roof each sweep
 *                       (tilt +30/-30 selects eaves-from-below / roofs-from-above).
 */
public enum SensorType { FIXED_3D, SPINNING_TILTED, FRONT_360_VERTICAL, PLANT_INSPECT, WILDLIFE_CAM }
