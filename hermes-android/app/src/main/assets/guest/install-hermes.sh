#!/bin/bash
# Runs inside Ubuntu: installs Hermes Agent with its official Linux installer, then prints its version.
set -euo pipefail
echo
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
