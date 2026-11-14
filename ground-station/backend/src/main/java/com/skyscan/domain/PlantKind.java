package com.skyscan.domain;
/** Vegetation classes the detector distinguishes.
 *  CROP  — an individually inspectable plant (orchard rows, vegetables).
 *  TREE  — an individual tree.
 *  CROP_FIELD — a ZONE: a contiguous row-crop patch (wheat, maize...) where
 *  per-plant inspection is meaningless; lat/lon is the zone center and
 *  canopyDiaM its diameter. Inspected via SAMPLE POINTS across the zone and
 *  reported with ZONE-LEVEL health ("affected at k of n sample points"). */
public enum PlantKind { CROP, TREE, CROP_FIELD }
