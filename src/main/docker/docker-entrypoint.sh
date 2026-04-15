#!/bin/sh
set -eu

APP_HOME="${APP_HOME:-/app}"
APP_USER="${APP_USER:-muyun}"
APP_GROUP="${APP_GROUP:-muyun}"
APP_CHOWN_ON_START="${APP_CHOWN_ON_START:-true}"

mkdir -p "$APP_HOME/var/data" "$APP_HOME/var/storage" "$APP_HOME/var/tmp"
if [ "$APP_CHOWN_ON_START" = "true" ]; then
  chown -R "$APP_USER:$APP_GROUP" "$APP_HOME/var"
fi

exec gosu "$APP_USER:$APP_GROUP" java -Dquarkus.http.host=0.0.0.0 -jar /app/quarkus-app/quarkus-run.jar
