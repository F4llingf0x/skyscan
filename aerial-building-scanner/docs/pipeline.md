# Mission pipeline

| # | Stage | Inputs | Outputs | Packages |
|---|---|---|---|---|
| 1 | Crude scan | Operator area, max building height, safety altitude | Coarse building model, crude-scan map | `bscan_crude_scan`, `bscan_trajectory`, `bscan_mapping`, `bscan_vehicle_interface` |
| 2 | Land and plan | Coarse model, mission config | Ordered viewpoints and lidar sweeps, trajectories | `bscan_view_planning`, `bscan_trajectory` |
| 3 | Survey flight | Planned trajectories, live sensors | Logged survey data with poses, online map | `bscan_state_estimation`, `bscan_mapping`, `bscan_obstacle_avoidance`, `bscan_vehicle_interface` |
| 4 | Gap check | Online map, logged data | Per-patch quality scores, gap list | `bscan_coverage_analysis` |
| 5 | Refill flights | Gap list (next-best-view requests) | Additional survey data; stop when the stopping rule is met | `bscan_coverage_analysis`, `bscan_view_planning`, `bscan_trajectory` |
| 6 | Offline processing | All logged data and poses, camera images | Refined trajectory, colored point cloud, exports, report | `offline/*` |

`bscan_mission_manager` sequences all stages; `bscan_safety_monitor` runs throughout.

Status: not started
