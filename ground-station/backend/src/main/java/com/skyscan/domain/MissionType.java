package com.skyscan.domain;
/**
 * What kind of drone activity a mission performs. The platform is built to
 * grow more of these (inspection, delivery, thermal survey, ...): each type
 * reuses the same state machine, connector boundary, storage and live stream,
 * and plugs its own planning + post-flight analysis into MissionService.
 *  SURVEY_3D   — the original two-pass LiDAR point-cloud survey.
 *  WILDLIFE_SURVEY — single-pass animal search: the coverage flight runs the
 *                RGB camera + thermal camera through a YOLO-class detector;
 *                every animal is tagged with SPECIES + TIME + LOCATION the
 *                moment it is found (animals move — no detail pass), streamed
 *                live to the console, and delivered as a sightings map+report.
 *  CROP_HEALTH — agronomy: crude pass detects every crop plant and tree in
 *                the zone; the detail pass visits each plant for close-up
 *                disease inspection (battery swaps supported between visits);
 *                the deliverable is a findings report (diseases, locations,
 *                possible cures, what to check) plus the per-plant map.
 */
public enum MissionType { SURVEY_3D, CROP_HEALTH, WILDLIFE_SURVEY }
