#!/bin/sh
# Runs a dev client in a private headless sway session: nothing appears on the desktop and no real
# input devices are attached. Control it through the dev bridge or tools/mc.py.
#
#   tools/hidden-client.sh                         plainsight test client in run-hidden/ (bridge port 25612)
#   TASK=runAxiomClient tools/hidden-client.sh     vanilla + Axiom in run-axiom/ (bridge port 25611)
#   GPU=/dev/dri/renderD128 tools/hidden-client.sh  use another GPU (default: integrated GPU)
#   GL=1 tools/hidden-client.sh                    OpenGL instead of Vulkan (SDL's Wayland driver; the
#                                                  XWayland fallback has no working OpenGL here), e.g. for Voxy
set -eu
cd "$(dirname "$0")/.."
[ -n "${IN_NIX_SHELL:-}" ] || exec ./dev "$0" "$@"

TASK="${TASK:-runHiddenClient}"
GPU="${GPU:-/dev/dri/renderD129}"
case "$TASK" in
	runAxiomClient) RUN=run-axiom; NAME=axiom; PORT=25611 ;;
	*) RUN=run-hidden; NAME=main; PORT=25612 ;;
esac
STATE="${XDG_RUNTIME_DIR:-/tmp}/plainsight-hidden-$NAME"
mkdir -p "$STATE" "$RUN"
LOG="$PWD/$RUN/hidden-client.log"
rm -f "$STATE/exit"

# headless-friendly defaults (narrator/audio would need a sound server)
OPTS=$RUN/options.txt
touch "$OPTS"
for kv in narrator:0 onboardAccessibility:false pauseOnLostFocus:false soundCategory_master:0.0 tutorialStep:none skipMultiplayerWarning:true 'inactivityFpsLimit:"minimized"' enableVsync:false; do
	k=${kv%%:*}
	grep -v "^$k:" "$OPTS" > "$OPTS.tmp" || true
	echo "$kv" >> "$OPTS.tmp"
	mv "$OPTS.tmp" "$OPTS"
done
export ALSOFT_DRIVERS=null
[ -n "${GL:-}" ] && export SDL_VIDEO_DRIVER=wayland
export PLAINSIGHT_BRIDGE_PORT=$PORT

cat > "$STATE/sway.conf" <<CONF
output HEADLESS-1 resolution 1600x900 position 0 0
default_border none
xwayland enable
exec sh -c 'cd "$PWD" && ./gradlew --no-configuration-cache $TASK > "$LOG" 2>&1; echo \$? > "$STATE/exit"; swaymsg exit'
CONF

unset WAYLAND_DISPLAY DISPLAY SWAYSOCK
export WLR_BACKENDS=headless
export WLR_LIBINPUT_NO_DEVICES=1
export WLR_RENDER_DRM_DEVICE="$GPU"
export WLR_RENDERER=gles2
PCI=$(basename "$(readlink -f /sys/class/drm/$(basename "$GPU")/device)")
export DRI_PRIME="pci-$(echo "$PCI" | tr ':.' '__')"
export XDG_RUNTIME_DIR="$STATE"
chmod 700 "$STATE"
echo "hidden sway ($TASK) on $GPU ($PCI), bridge :$PORT, client log: $LOG"
exec sway --unsupported-gpu -c "$STATE/sway.conf" > "$STATE/sway.log" 2>&1
