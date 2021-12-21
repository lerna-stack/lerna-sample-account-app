#!/usr/bin/env bash
set -ex

sbt \
-Dfork=true \
-Dakka.cluster.min-nr-of-members=2 \
-Dakka.cluster.roles.0="replica-group-2" \
-Dakka.remote.artery.canonical.hostname="127.0.0.2" \
-Dmyapp.server-mode=DEV \
-Dmyapp.private-internet.http.interface="127.0.0.2" \
-Dmyapp.management.http.interface="127.0.0.2" \
-Dakka-entity-replication.eventsourced.persistence.cassandra.events-by-tag.first-time-bucket="$(date '+%Y%m%dT%H:%M' --utc)" \
-Dmyapp.readmodel.rdbms.tenants.tenant-a.db.url='jdbc:mysql://127.0.0.1:3306/myapp-tenant-a' \
-Dmyapp.readmodel.rdbms.tenants.tenant-a.db.user='dbuser_a' \
-Dmyapp.readmodel.rdbms.tenants.tenant-a.db.password='dbpass@a' \
-Dmyapp.readmodel.rdbms.tenants.tenant-b.db.url='jdbc:mysql://127.0.0.2:3306/myapp-tenant-b' \
-Dmyapp.readmodel.rdbms.tenants.tenant-b.db.user='dbuser_b' \
-Dmyapp.readmodel.rdbms.tenants.tenant-b.db.password='dbpass@b' \
entrypoint/run
