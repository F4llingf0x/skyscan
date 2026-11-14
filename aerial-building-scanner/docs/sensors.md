# Sensors

## Roles

- **Pixhawk IMU + GNSS (RTK planned) + barometer**: navigation backbone via PX4 EKF2.
- **Depth sensors**: local odometry and obstacle avoidance.
- **Tilted 2D lidar**: main geometry; localization against the crude-scan map.
- **Camera**: point cloud coloring and quality only, not odometry.

## Notes

- Tilted 2D lidar slices barely overlap, so scan-to-scan matching is weak; scan-to-map matching against the crude scan is used instead.
- Depth sensor accuracy drops with distance.
- GNSS degrades near facades (multipath, occlusion).

Status: not started
