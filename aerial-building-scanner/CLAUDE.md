# Working on this repository

- Each folder's README.md is its specification. Read it before working in that folder, and update its "Status" line when work starts or finishes.
- Read docs/architecture.md and docs/pipeline.md before larger changes.
- Development is simulation-first: every feature must be runnable and testable in the digital twin without hardware.
- Every change needs tests: unit tests inside the package, scenario or bag-replay tests in tests/ or evaluation/scenarios/.
- Sensor mounts, limits and thresholds come from config/, never hard-coded.
- Record significant design decisions as a new file in docs/decisions/.
- Never commit secrets, credentials or large data files outside Git LFS.
