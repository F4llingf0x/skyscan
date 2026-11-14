# Roadmap

Build order:

1. Digital twin world, drone and sensor models, running in Docker.
2. Fly a hand-written facade route using ground-truth pose; build the point cloud.
3. Scoring pipeline against the true mesh.
4. Route planner from a known building model.
5. Route planner from the crude scan.
6. Gap detection and refill loop.
7. Replace ground truth with the real navigation pose (EKF2 + depth odometry + lidar scan-to-map); measure the quality drop.
8. Offline trajectory refinement; measure the improvement.
9. Camera colorization.
10. Obstacle avoidance, safety layer and failsafes.
11. Realism and benchmarks: noise, wind, GNSS multipath, many generated buildings.
12. Hardware: bench tests, recorded data, real flights.

Status: not started
