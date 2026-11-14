# src

ROS 2 packages of the onboard stack, all prefixed `bscan_`.

## Contents

- `bscan_bringup/`: Launch files and configuration that start the full stack, either in the digital twin or on real hardware.
- `bscan_coverage_analysis/`: Quality scoring per surface patch (point density, incidence angle, holes), gap detection, next-best-view requests for refill flights, a stopping rule, and a report of unreachable areas.
- `bscan_crude_scan/`: Generates a safe first-pass flight pattern above the operator-defined area and maximum building height, and builds the coarse building model used for planning and localization.
- `bscan_interfaces/`: Custom ROS 2 messages, services and actions shared across packages (mission stage, viewpoint, sweep segment, coverage report, localization quality).
- `bscan_mapping/`: Online voxel/occupancy map for planning and obstacle avoidance, plus logging of raw survey data with poses for offline reconstruction.
- `bscan_mission_manager/`: State machine for the full mission: crude scan → land → plan → survey flight → gap check → refill flights → land → hand-off to offline processing.
- `bscan_obstacle_avoidance/`: Local reactive planner using depth sensors and the online map to avoid obstacles missing from the coarse model (trees, cables, balconies).
- `bscan_safety_monitor/`: Independent safety layer: geofence, battery limits, link and heartbeat watchdogs, minimum obstacle clearance.
- `bscan_sensors/`: Adapters for the tilted 2D lidar, depth sensors and camera.
- `bscan_state_estimation/`: Real-time navigation pose.
- `bscan_trajectory/`: Converts the ordered viewpoints and sweeps into smooth, dynamically feasible, collision-free trajectories, with speed limits that keep lidar sweep density sufficient.
- `bscan_vehicle_interface/`: Link to the Pixhawk 6C running PX4 via uXRCE-DDS: arming, takeoff, landing, offboard trajectory following and telemetry.
- `bscan_view_planning/`: Survey route planning from the coarse model: surface and normal extraction, candidate camera viewpoints and lidar sweep segments, set-cover selection, efficient ordering (TSP), and localization-aware cost terms.

Status: not started
