# SkyScan

| Folder | Contents |
|---|---|
| [`ground-station/`](ground-station/) | Operator web UI, backend and post-flight processing (point-cloud engine), sample data and `docker-compose.yml`. Run it from that folder: `cd ground-station && docker compose up --build`. |
| [`aerial-building-scanner/`](aerial-building-scanner/) | Drone side: digital twin, onboard drone logic and tests. Holds the current Jetson drone agent in `legacy_drone_agent/` and the scaffold of the new ROS 2 Aerial Building Scanner stack. |
