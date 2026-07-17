package com.skyscan.domain;
/**
 * What the detail pass is optimized for — selected by the operator to match the
 * physically mounted payload; drives sensor selection and route-planning strategy.
 *  LANDSCAPE — downward ±30° payload, boustrophedon coverage route over terrain.
 *  STRUCTURE — front-facing 360° vertical payload, stacked orbit loops around the
 *              target footprint at standoff distance (buildings, statues).
 */
public enum ScanMode { LANDSCAPE, STRUCTURE }
