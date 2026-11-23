#!/bin/bash
# Runs inside Ubuntu (as fake root) once, right after the root filesystem is unpacked.
set -euo pipefail
echo
echo "==> Preparing Ubuntu"
cat > /etc/apt/apt.conf.d/99hermes-mobile <<'CONF'
APT::Sandbox::User "root";
Acquire::Retries "3";
Dpkg::Use-Pty "0";
APT::Install-Recommends "0";
CONF
export DEBIAN_FRONTEND=noninteractive
echo "==> Updating package lists"
apt-get update
echo "==> Installing basic tools (curl, git, certificates)"
apt-get install -y ca-certificates curl git xz-utils less nano procps
echo
echo "==> Linux is ready"
