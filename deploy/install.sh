#!/usr/bin/env sh
# Kratis installer: downloads the Docker Compose stack into the current directory
# and generates the required secrets in .env. Does not run Docker.
# Usage: curl -fsSL https://raw.githubusercontent.com/kratisai/kratis/main/deploy/install.sh | sh
set -eu

BASE="https://raw.githubusercontent.com/kratisai/kratis/main/deploy"

for cmd in curl openssl; do
	command -v "$cmd" >/dev/null 2>&1 || {
		echo "install.sh: $cmd is required but not installed." >&2
		exit 1
	}
done

if [ -e compose.yaml ] || [ -e .env ]; then
	echo "install.sh: compose.yaml or .env already exists here; refusing to overwrite." >&2
	exit 1
fi

curl -fsSL "$BASE/compose.yaml" -o compose.yaml
mkdir -p litellm
curl -fsSL "$BASE/litellm/patch_response_logging.py" -o litellm/patch_response_logging.py
curl -fsSL "$BASE/litellm/healthcheck.py" -o litellm/healthcheck.py
curl -fsSL "$BASE/.env.example" -o .env

sed -i.bak \
	-e "s|^JWT_SECRET=.*|JWT_SECRET=$(openssl rand -hex 32)|" \
	-e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 16)|" \
	-e "s|^LITELLM_MASTER_KEY=.*|LITELLM_MASTER_KEY=sk-$(openssl rand -hex 24)|" \
	-e "s|^LITELLM_SALT_KEY=.*|LITELLM_SALT_KEY=sk-$(openssl rand -hex 24)|" \
	.env
rm -f .env.bak

# Grant the container access to the host docker socket without running as root.
if [ -S /var/run/docker.sock ]; then
	sock_gid=$(stat -c %g /var/run/docker.sock 2>/dev/null || stat -f %g /var/run/docker.sock)
	sed -i.bak "s|^DOCKER_GID=.*|DOCKER_GID=$sock_gid|" .env && rm -f .env.bak
fi

if command -v docker >/dev/null 2>&1; then
	project=$(printf '%s' "${PWD##*/}" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')
	db_volume="${project}_kratis-data"
	if docker volume inspect "$db_volume" >/dev/null 2>&1; then
		echo
		echo "WARNING: existing DB volume '$db_volume'. New DB_PASSWORD won't match it."
		echo "Either set DB_PASSWORD in .env to your old password, or start fresh:"
		echo "  docker volume rm $db_volume"
	fi
fi

echo
echo "Kratis is ready in $(pwd). Review .env, then start the stack:"
echo "  docker compose up -d --wait"
echo
echo "To upgrade to the latest:"
echo "  docker compose pull"
echo
echo "UI and API: http://localhost:8080"
