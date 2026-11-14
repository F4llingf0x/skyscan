# Architecture

## Parts

- **Onboard ROS 2 stack** (`ros2_ws/`): real-time software on the Jetson Orin Nano, talking to PX4 on the Pixhawk 6C.
- **Offline processing** (`offline/`): post-flight trajectory refinement, reconstruction, colorization and export.
- **Digital twin** (`digital_twin/`): PX4 SITL + Gazebo with known ground truth.
- **Evaluation** (`evaluation/`): metrics and scenario tests scored against ground truth.

## Data flow

- Onboard: sensors → state estimation and mapping → planning (crude scan, view planning, trajectory, obstacle avoidance) → vehicle interface → PX4. The safety monitor watches all of it independently.
- Post-flight: logged sensor data and poses → offline pipeline → exports (LAS/LAZ, PLY, E57) and survey report.
- Evaluation: digital twin ground truth (meshes, trajectories) → evaluation metrics, compared with the exported point clouds and flight logs.

Status: not started
