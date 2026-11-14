# digital_twin

Simulation environment (PX4 SITL + Gazebo) where all development and testing happens first, with known ground truth for objective scoring.

## Contents

- `building_generator/`: Procedural building generation, e.g.
- `environment/`: Environmental effects: wind, GNSS degradation and multipath near facades, lighting conditions.
- `ground_truth/`: Exports true building meshes and true vehicle trajectories for evaluation.
- `sensor_models/`: Simulated tilted 2D lidar, depth sensors and camera with configurable noise, range limits and timing.
- `vehicle_models/`: Simulated drone with sensor mounts matching `config/sensors/`.
- `worlds/`: Gazebo worlds containing buildings and surroundings (trees, poles, cables, neighboring structures).

Status: not started
