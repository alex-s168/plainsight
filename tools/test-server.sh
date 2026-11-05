#!/bin/sh
# A local vanilla server for multiplayer tests (e.g. Distant Horizons on our worldgen server, #61):
# run-server/, 127.0.0.1:25599, offline mode, no whitelist, seed 12345, creative. Uses the server jar
# Loom already has; Mojang's EULA was accepted by the user for this test server.
#
#   tools/test-server.sh          start (in the background; log in run-server/console.log)
#   tools/test-server.sh stop     stop it
set -eu
cd "$(dirname "$0")/.."
ROOT="$PWD"
DIR="$ROOT/run-server"
if [ "${1:-}" = stop ]; then
	for pid in $(pgrep -f "[m]inecraft-server.jar"); do
		[ "$(readlink /proc/$pid/cwd)" = "$DIR" ] && kill "$pid"
	done
	exit 0
fi
JAR="$HOME/.gradle/caches/fabric-loom/26.3/minecraft-server.jar"
[ -f "$JAR" ] || { echo "no server jar at $JAR (build once so Loom fetches it)"; exit 1; }
mkdir -p "$DIR"
cp -n "$JAR" "$DIR/minecraft-server.jar" 2>/dev/null || true
echo "eula=true" > "$DIR/eula.txt"
cat > "$DIR/server.properties" <<P
server-ip=127.0.0.1
server-port=25599
online-mode=false
enforce-secure-profile=false
white-list=false
enforce-whitelist=false
level-seed=12345
gamemode=creative
force-gamemode=true
spawn-protection=0
view-distance=8
simulation-distance=6
spawn-monsters=false
motd=plainsight test server
max-players=4
P
nohup ./dev sh -c "cd '$DIR' && exec java -Xmx2G -jar minecraft-server.jar nogui" > "$DIR/console.log" 2>&1 &
i=0
until grep -q "Done (" "$DIR/console.log" 2>/dev/null; do
	i=$((i + 1)); [ $i -gt 120 ] && { echo "server did not start, see $DIR/console.log"; exit 1; }
	sleep 1
done
echo "test server on 127.0.0.1:25599"
