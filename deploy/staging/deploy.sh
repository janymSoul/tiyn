#!/usr/bin/env bash
# Deploy a published image to the GCP staging VM.
#
# Usage: deploy/staging/deploy.sh [<commit sha>]   (default: the latest commit on origin/main)
#        deploy/staging/deploy.sh --dry-run [<sha>]
#
# Needs: gcloud (logged in; SSH goes through an IAP tunnel, so no IP allow-listing), curl, git. Run from a checkout whose
# deploy/helm/tiyn/values-staging.yaml is the one you want to apply.
set -euo pipefail

PROJECT="${GCP_PROJECT:-project-62417d3f-f0c0-4934-8f8}"
ZONE="${GCP_ZONE:-europe-west3-a}"
VM="${STAGING_VM:-tiyn-staging}"
HOST="${STAGING_HOST:-api-staging.tiyn.dev}"
IMAGE_REPO="janymsoul/tiyn"
REPO_URL="https://github.com/janymSoul/tiyn.git"
S3_KEYS_FILE="/tmp/s3-values.yaml"

dry_run=false
if [[ "${1:-}" == "--dry-run" ]]; then
  dry_run=true
  shift
fi

root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
values="$root/deploy/helm/tiyn/values-staging.yaml"
[[ -f "$values" ]] || { echo "missing $values" >&2; exit 1; }

sha="${1:-$(git -C "$root" ls-remote origin refs/heads/main | cut -f1)}"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "not a full 40-character commit sha: $sha" >&2; exit 1; }

echo "target: $sha"

# The image is built by CI on merge to main; refuse to deploy a sha that has none.
token="$(curl -fsS "https://ghcr.io/token?scope=repository:${IMAGE_REPO}:pull" | sed -E 's/.*"token":"([^"]+)".*/\1/')"
code="$(curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $token" \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
  "https://ghcr.io/v2/${IMAGE_REPO}/manifests/${sha}")"
[[ "$code" == "200" ]] || { echo "no image ghcr.io/${IMAGE_REPO}:${sha} (HTTP $code); has CI finished on main?" >&2; exit 1; }
echo "image ghcr.io/${IMAGE_REPO}:${sha} exists"

if $dry_run; then
  echo "dry run: would deploy $sha to $VM ($ZONE) and check https://$HOST/actuator/health"
  exit 0
fi

gcloud_ssh() {
  gcloud compute ssh "$VM" --project "$PROJECT" --zone "$ZONE" --tunnel-through-iap --quiet --command "$1"
}

gcloud compute scp "$values" "$VM:/tmp/values-staging.yaml" --project "$PROJECT" --zone "$ZONE" --tunnel-through-iap --quiet \
  || { echo "scp through IAP failed; check: gcloud auth list, and that the tiyn-ssh-iap firewall rule exists" >&2; exit 1; }

# --atomic rolls the release back if the new pod never becomes ready. It does not undo database migrations.
gcloud_ssh "set -euo pipefail
test -f $S3_KEYS_FILE
rm -rf ~/tiyn && git clone -q $REPO_URL ~/tiyn && git -C ~/tiyn checkout -q $sha
sudo helm upgrade --install tiyn ~/tiyn/deploy/helm/tiyn -n tiyn \
  -f /tmp/values-staging.yaml -f $S3_KEYS_FILE \
  --set image.tag=$sha --kubeconfig /etc/rancher/k3s/k3s.yaml --atomic --timeout 300s
sudo k3s kubectl -n tiyn get deploy tiyn -o jsonpath='{.spec.template.spec.containers[0].image}'; echo"

echo "checking https://$HOST/actuator/health"
for _ in 1 2 3 4 5 6; do
  if curl -fsS "https://$HOST/actuator/health" | grep -q '"status":"UP"'; then
    echo "deployed $sha: healthy"
    exit 0
  fi
  sleep 5
done
echo "deployed $sha but the health check did not report UP; inspect: kubectl -n tiyn logs deploy/tiyn" >&2
exit 1
