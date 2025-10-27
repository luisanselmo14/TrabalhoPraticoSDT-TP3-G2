#!/bin/bash
set -euo pipefail

# aguarda IPFS ficar disponível
until curl -s http://ipfs:5001/api/v0/version > /dev/null; do
  echo "Waiting for IPFS to be ready..."
  sleep 2
done
echo "IPFS is ready!"

# start Leader
LEADER_JAR=$(ls target/leader-api-1.0-SNAPSHOT.jar | head -n 1 || true)
if [ -z "$LEADER_JAR" ]; then
  echo "ERROR: Leader JAR not found in target/"
  exit 1
fi
echo "==> starting leader: java -jar $LEADER_JAR"
java -jar "$LEADER_JAR" > /app/leader.log 2>&1 &
LEADER_PID=$!
echo "leader pid=$LEADER_PID"

sleep 2

# start ClusterRunner (peers)
CLUSTER_JAR=$(ls target/leader-api-1.0-SNAPSHOT-cluster-runner.jar | head -n 1 || true)
if [ -z "$CLUSTER_JAR" ]; then
  echo "ERROR: ClusterRunner JAR not found in target/"
  exit 1
fi
echo "==> starting ClusterRunner: java -jar $CLUSTER_JAR"
java -jar "$CLUSTER_JAR" > /app/peers.log 2>&1 &
PEERS_PID=$!
echo "peers pid=$PEERS_PID"

# tail logs
echo "==> Tailing logs (leader.log, peers.log). Ctrl+C to stop."
tail -F /app/leader.log /app/peers.log
