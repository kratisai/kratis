#!/usr/bin/env bash
# Boots a control-plane image against a throwaway pgvector database and fails unless it reports
# healthy. Catches native-image runtime regressions (e.g. an unreadable harness catalogue) that an
# image build cannot see.
#
# Usage: smoke-test-image.sh <image-ref> [timeout-seconds]
set -euo pipefail

IMAGE="${1:?usage: smoke-test-image.sh <image-ref> [timeout-seconds]}"
TIMEOUT="${2:-180}"
NETWORK="kratis-smoke-net-$$"
DB="kratis-smoke-db-$$"
APP="kratis-smoke-app-$$"

cleanup() {
	docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
	docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
	echo "smoke test failed: $1" >&2
	docker logs "$APP" >&2 2>/dev/null || true
	exit 1
}

docker network create "$NETWORK" >/dev/null
docker run -d --name "$DB" --network "$NETWORK" \
	-e POSTGRES_DB=kratis -e POSTGRES_USER=kratis -e POSTGRES_PASSWORD=kratis \
	pgvector/pgvector:pg16 >/dev/null

deadline=$((SECONDS + TIMEOUT))
until docker exec "$DB" pg_isready -U kratis >/dev/null 2>&1; do
	[ "$SECONDS" -lt "$deadline" ] || fail "database did not become ready"
	sleep 1
done

docker run -d --name "$APP" --network "$NETWORK" \
	-e SPRING_DATASOURCE_URL="jdbc:postgresql://$DB:5432/kratis" \
	-e SPRING_DATASOURCE_USERNAME=kratis \
	-e SPRING_DATASOURCE_PASSWORD=kratis \
	-e JWT_SECRET=smoke-test-secret-that-is-at-least-32-characters \
	-e KRATIS_TELEMETRY_DISABLED=1 \
	"$IMAGE" >/dev/null

while [ "$SECONDS" -lt "$deadline" ]; do
	status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$APP" 2>/dev/null || echo missing)
	case "$status" in
		healthy)
			echo "control-plane image $IMAGE is healthy"
			exit 0
			;;
		unhealthy) fail "container became unhealthy" ;;
		exited | dead) fail "container exited during startup" ;;
	esac
	sleep 2
done

fail "container did not become healthy within ${TIMEOUT}s"
