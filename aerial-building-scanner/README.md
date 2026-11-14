# Aerial Building Scanner

## Goal

An autonomous drone that produces complete, accurate, colored point clouds of buildings. Developed simulation-first in a digital twin, then validated on real hardware.

## Mission flow

1. **Crude scan**: safe high pass over the operator-defined area to get a coarse model.
2. **Land and plan**: compute the optimal survey route (camera viewpoints + lidar sweeps).
3. **Survey flight**: fly the route with real-time localization and obstacle avoidance.
4. **Gap check**: score coverage and quality per surface patch.
5. **Refill flights**: targeted flights for missing areas until the stopping rule is met.
6. **Offline processing**: refine the trajectory, rebuild, colorize and export the point cloud.

## Hardware

Jetson Orin Nano (companion computer), Pixhawk 6C with PX4, tilted 2D lidar, depth sensors, camera.

## Sensor roles

- **Pixhawk IMU + GNSS (RTK planned) + barometer**: navigation backbone via PX4 EKF2.
- **Depth sensors**: local odometry and obstacle avoidance.
- **Tilted 2D lidar**: main geometry; localization against the crude-scan map.
- **Camera**: point cloud coloring and quality only, not odometry.

## Principles

- Simulation first.
- Objective scoring against ground truth.
- Navigation pose (real time) and mapping pose (offline) are separate problems.
- Solve one problem at a time.

## Repository map

| Folder | Purpose |
|---|---|
| `docs/` | Architecture, pipeline, sensors, roadmap, decision records. |
| `config/` | Shared vehicle, sensor and mission parameters for sim and hardware. |
| `ros2_ws/` | ROS 2 onboard real-time stack (Jetson Orin Nano). |
| `offline/` | Post-flight trajectory refinement, reconstruction, colorization, export. |
| `digital_twin/` | PX4 SITL + Gazebo simulation with ground truth. |
| `evaluation/` | Metrics, scenario tests and benchmarks against ground truth. |
| `calibration/` | Intrinsic/extrinsic calibration and time sync procedures. |
| `operator_ui/` | Minimal operator interface (later phase). |
| `datasets/` | Recorded ROS 2 bags (MCAP, Git LFS). |
| `tests/` | Integration and bag-replay regression tests. |
| `tools/` | Developer utilities. |
| `docker/` | Dev, sim and Jetson (ARM64) containers. |
| `ci/` | CI pipeline definitions. |

## Status

Scaffolding only; no implementation yet.
