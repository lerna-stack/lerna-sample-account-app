#!/usr/bin/env bash
set -ex

sbt \
-Dfork=true \
-Dakka.cluster.min-nr-of-members=3 \
-Dakka.cluster.seed-nodes.0="akka://MyAppSystem@127.0.0.1:25520" \
-Dakka.cluster.seed-nodes.1="akka://MyAppSystem@127.0.0.2:25520" \
-Dakka.cluster.seed-nodes.2="akka://MyAppSystem@127.0.0.3:25520" \
-Dakka.cluster.roles.0="replica-group-3" \
-Dakka.remote.artery.canonical.hostname="127.0.0.3" \
-Dmyapp.server-mode=DEV \
-Dmyapp.private-internet.http.interface="127.0.0.3" \
-Dmyapp.management.http.interface="127.0.0.3" \
-Dakka-entity-replication.eventsourced.persistence.cassandra.events-by-tag.first-time-bucket="$(date '+%Y%m%dT%H:%M' --utc)" \
entrypoint/run
