#!/usr/bin/env bash
set -ex

sbt \
-Dfork=true \
-Dakka.cluster.min-nr-of-members=2 \
-Dakka.cluster.seed-nodes.0="akka://MyAppSystem@127.0.0.1:25520" \
-Dakka.cluster.seed-nodes.1="akka://MyAppSystem@127.0.0.2:25520" \
-Dakka.remote.artery.canonical.hostname="127.0.0.1" \
-Dmyapp.server-mode=DEV \
-Dmyapp.public-internet.http.interface="127.0.0.1" \
-Dmyapp.private-internet.http.interface="127.0.0.1" \
-Dmyapp.management.http.interface="127.0.0.1" \
entrypoint/run
