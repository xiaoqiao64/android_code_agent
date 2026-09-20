#!/bin/bash
# Placed into /root/setup_agents.sh by SetupScreen; also kept as reference.
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y curl ca-certificates git nodejs npm
mkdir -p /root/workspace
echo SETUP_BASE_OK
