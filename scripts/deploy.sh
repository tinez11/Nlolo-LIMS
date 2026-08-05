#!/usr/bin/env bash
# Rolling deploy of the lifeplatform image to the target environment.
#
# NOT YET IMPLEMENTED: no deployment target (orchestrator, host, or platform)
# has been specified in the Phase 0 deliverables (see
# docs/08-implementation-roadmap.md §5, open item 4). Wire the real deploy
# mechanism here once that's decided -- do not guess at one.
#
# Usage: scripts/deploy.sh <staging|production> <image-tag>
set -euo pipefail

ENVIRONMENT="${1:?Usage: scripts/deploy.sh <staging|production> <image-tag>}"
IMAGE_TAG="${2:?Usage: scripts/deploy.sh <staging|production> <image-tag>}"

echo "Would deploy ghcr.io/<repo>:${IMAGE_TAG} to ${ENVIRONMENT}, but no deployment" >&2
echo "target has been configured yet. Resolve docs/08-implementation-roadmap.md" >&2
echo "§5 open item 4, then implement this script." >&2
exit 1
