#!/usr/bin/env bash
# Builds everything you send to a client into ./dist
#
#   ./make_release.sh 1.0.0
#
# Produces:
#   dist/redis-connection-guide-<version>-amd64.tar.gz   ready-to-load container image
#   dist/redis-connection-guide-source-<version>.zip     source + Dockerfile (client builds it themselves)
#   dist/README.md                                        client instructions
#   dist/SHA256SUMS.txt                                   checksums
#
# Requires Docker and Python 3. Internal tooling (build_page.py, page_template.html,
# guide_content.py, this script) is never included in what you ship.
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-1.0.0}"
IMAGE="redis-connection-guide"
OUT="dist"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "==> Regenerating index.html"
python3 build_page.py

# Most OpenShift/Kubernetes clusters are amd64. Building on an Apple Silicon Mac without
# --platform would produce an arm64 image that fails to start there. The Dockerfile compiles the
# Java and .NET code on the build machine's own CPU, so this works on Apple Silicon too.
echo "==> Building image ${IMAGE}:${VERSION} for linux/amd64"
docker build --platform linux/amd64 -t "${IMAGE}:${VERSION}" .

echo "==> Saving image"
docker save "${IMAGE}:${VERSION}" | gzip > "${OUT}/${IMAGE}-${VERSION}-amd64.tar.gz"

echo "==> Packaging source"
zip -rq "${OUT}/${IMAGE}-source-${VERSION}.zip" \
  README.md index.html Dockerfile .dockerignore server openshift \
  Jedis Lettuce StackExchange Python \
  -x '*.DS_Store' '*/certs/*' '*/target/*' '*/bin/*' '*/obj/*' '*/out/*' \
     '*/.venv/*' '*/classpath.txt' '*/__pycache__/*'

cp README.md "${OUT}/README.md"
(cd "$OUT" && shasum -a 256 -- *.tar.gz *.zip > SHA256SUMS.txt)

echo
echo "Done. Send the contents of ./${OUT}:"
ls -lh "$OUT"
