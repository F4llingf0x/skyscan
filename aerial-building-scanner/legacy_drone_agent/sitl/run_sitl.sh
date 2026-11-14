#!/usr/bin/env bash
# Fly the whole stack with NO hardware: ArduPilot SITL stands in for the Pixhawk.
#   1. Install ArduPilot SITL (https://ardupilot.org/dev/docs/sitl-simulator-software-in-the-loop.html)
#   2. sim_vehicle.py -v ArduCopter -L <site-lat,lon,alt,hdg> --out=udp:127.0.0.1:14550
#   3. Run the agent with mavlink.device: udp:127.0.0.1:14550 and mock sensors:
SKYSCAN_MOCK_SENSORS=1 python3 -m skyscan_drone.agent --config ../config.yaml
# SKYSCAN_MOCK_SENSORS=1 replaces lidar/servo/ToF/camera with simulators so the
# full mission flow (upload -> legs -> battery swap -> scan upload) is testable.
