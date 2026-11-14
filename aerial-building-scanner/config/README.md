# config

Parameter files shared by simulation and real hardware. Single source of truth so the digital twin and the real drone use identical settings.

## Contents

- `missions/`: Mission parameters: survey area, maximum building height, safety altitude, stand-off distances, coverage and quality thresholds.
- `sensors/`: Sensor parameters and mounting: poses of the tilted 2D lidar (including tilt angle), depth sensors and camera relative to the vehicle; calibration results are stored here.
- `vehicles/`: Vehicle parameters: frame, mass, speed and acceleration limits, minimum clearance, flight envelope.

Status: not started
