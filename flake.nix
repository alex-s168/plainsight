{
  description = "Plainsight — Fabric 26.3 client mod dev shell (JBR 25 for hot-swap, headless sway for hidden clients)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};

      # Minecraft's LWJGL natives are glibc builds that dlopen GL, GLFW's X11/Wayland backends and
      # audio libraries. On a musl host nothing provides these, so everything comes from nixpkgs.
      runtimeLibs = with pkgs; [
        libglvnd
        mesa
        libx11
        libxcursor
        libxrandr
        libxi
        libxext
        libxxf86vm
        libxrender
        libxcb
        wayland
        libxkbcommon
        libpulseaudio
        alsa-lib
        openal
        udev
        flite
        stdenv.cc.cc.lib
      ];
      jdk = pkgs.jetbrains.jdk;
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          jdk
          sway # headless compositor (WLR_BACKENDS=headless) for hidden clients
          xwayland
          grim
          xdotool
          imagemagick
          jq
          curl
          python3
        ];

        shellHook = ''
          export JAVA_HOME=${jdk}/lib/openjdk
          [ -d "$JAVA_HOME" ] || export JAVA_HOME=${jdk}
          # Replace, never append: the host LD_LIBRARY_PATH may contain incompatible (musl/foreign) libs.
          export LD_LIBRARY_PATH=${pkgs.lib.makeLibraryPath runtimeLibs}
          # Mesa from nixpkgs drives the GPU, since the host's drivers are built against musl.
          export LIBGL_DRIVERS_PATH=${pkgs.mesa}/lib/dri
          export __EGL_VENDOR_LIBRARY_FILENAMES=${pkgs.mesa}/share/glvnd/egl_vendor.d/50_mesa.json
          export __GLX_VENDOR_LIBRARY_NAME=mesa
          export GBM_BACKENDS_PATH=${pkgs.mesa}/lib/gbm
          export VK_ICD_FILENAMES=$(ls ${pkgs.mesa}/share/vulkan/icd.d/*.json 2>/dev/null | tr '\n' ':')
          export XKB_CONFIG_ROOT=${pkgs.xkeyboard-config}/share/X11/xkb
        '';
      };
    };
}
