#!/bin/bash
set -euo pipefail

# configurable: IPFS API base and whether to start peers
IPFS_API_BASE="${IPFS_API_BASE:-http://ipfs:5001}"
START_PEERS="${START_PEERS:-true}"
export IPFS_API_BASE START_PEERS

echo "Using IPFS_API_BASE=${IPFS_API_BASE}, START_PEERS=${START_PEERS}"

# wait for IPFS to be ready
echo "Waiting for IPFS at ${IPFS_API_BASE}..."
until curl -s "${IPFS_API_BASE}/api/v0/version" > /dev/null 2>&1; do
  echo "IPFS not ready at ${IPFS_API_BASE}, retrying..."
  sleep 2
done
echo "IPFS is ready at ${IPFS_API_BASE}!"

# pass IPFS base to JVM
JAVA_OPTS="-Dipfs.api.base=${IPFS_API_BASE}"

# start Leader
LEADER_JAR=$(ls target/leader-api-1.0-SNAPSHOT.jar | head -n 1 || true)
if [ -z "$LEADER_JAR" ]; then
  echo "ERROR: Leader JAR not found in target/"
  exit 1
fi
echo "==> starting leader: java $JAVA_OPTS -jar $LEADER_JAR"
java $JAVA_OPTS -jar "$LEADER_JAR" > /app/leader.log 2>&1 &
LEADER_PID=$!
echo "leader pid=$LEADER_PID"

# optionally start ClusterRunner (peers)
if [ "${START_PEERS}" = "true" ]; then
  sleep 2
  CLUSTER_JAR=$(ls target/leader-api-1.0-SNAPSHOT-cluster-runner.jar | head -n 1 || true)
  if [ -z "$CLUSTER_JAR" ]; then
    echo "ERROR: ClusterRunner JAR not found in target/"
    exit 1
  fi
  echo "==> starting ClusterRunner: java $JAVA_OPTS -jar $CLUSTER_JAR"
  java $JAVA_OPTS -jar "$CLUSTER_JAR" > /app/peers.log 2>&1 &
  PEERS_PID=$!
  echo "peers pid=$PEERS_PID"
else
  echo "START_PEERS=false -> skipping ClusterRunner"
fi

# tail logs
echo "==> Tailing logs. Ctrl+C to stop."
if [ "${START_PEERS}" = "true" ]; then
  tail -F /app/leader.log /app/peers.log
else
  tail -F /app/leader.log
fi