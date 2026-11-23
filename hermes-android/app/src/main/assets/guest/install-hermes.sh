#!/bin/bash
# Runs inside Ubuntu: installs Hermes Agent with its official Linux installer, then prints its version.
set -euo pipefail
echo
# Earlier attempts (before PROOT_L2S_DIR was set) left proot's hard-link placeholder files
# (.l2s.* / .proot.l2s.*) inside Hermes' package folders, and uv then fails trying to link them.
# Those folders are rebuilt by the installer, so clear them. Hermes' settings are not touched.
if [ -d /root/.hermes ] && find /root/.hermes \( -name '.l2s.*' -o -name '.proot.l2s.*' \) -print -quit 2>/dev/null | grep -q .; then
  echo "==> Clearing leftovers from an earlier failed install"
  rm -rf /root/.hermes/cache/uv /root/.hermes/installs
fi
echo "==> Downloading the Hermes installer"
curl -fsSL https://hermes-agent.nousresearch.com/install.sh -o /tmp/hermes-install.sh
echo "==> Installing Hermes Agent (this can take a while on a phone)"
bash /tmp/hermes-install.sh --skip-setup
echo
echo "==> Checking the install"
command -v hermes
hermes --version
echo
echo "==> Hermes is installed"
