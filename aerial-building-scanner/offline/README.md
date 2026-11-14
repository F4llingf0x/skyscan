# offline

Post-flight processing that runs after landing, not in real time: trajectory refinement, final point cloud reconstruction, colorization and export.

## Contents

- `colorization/`: Colors the point cloud from camera images: camera–lidar calibration use, time sync, visibility (occlusion) checks, best-view selection, exposure correction and seam blending.
- `export/`: Exports results to LAS/LAZ, PLY and E57, and generates a survey report with coverage and quality statistics.
- `reconstruction/`: Rebuilds the final point cloud from the refined trajectory: merging, filtering, outlier removal, downsampling.
- `trajectory_refinement/`: Batch optimization of the whole flight trajectory so overlapping lidar sweeps and depth frames align, anchored to GNSS positions.

Status: not started
