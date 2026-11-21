#!/system/bin/sh
# Runs as the app uid (via run-as). Reproduces the self-test's container launches.
LIB="$1"
APP=/data/data/dev.dshn.app
ROOTFS=$APP/files/linux/ubuntu
export BXROOT_LIB_PATH=$LIB/libbxroot-runtime.so
export BXROOT_LINKER_PATH=$LIB/libbxroot-ulx.so
export BXROOT_STUB_LOADER=$LIB/libbxroot-stub-loader.so
export BXROOT_TMP_DIR=$APP/cache/bxroot-tmp
export BXROOT_L2S_DIR=$ROOTFS/.l2s
export PATH=/opt/node24/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root TERM=xterm-256color LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8
mkdir -p $BXROOT_TMP_DIR
run() {
  "$LIB/libbxroot.so" -r "$ROOTFS" -0 -l -w /root -b /dev -b /proc -b /sys -b "$APP/files/bridge:/run/dshn-bridge" "$@" > /data/local/tmp/dshn_probe_out.txt 2>&1
  echo "exit=$? :: $*"
  head -c 300 /data/local/tmp/dshn_probe_out.txt | tr '\n' ' '; echo
}
echo "lib=$LIB"
run /bin/sh -c 'echo sh_ok'
run /opt/node24/bin/node -v
run /opt/node24/bin/node -e 'console.log("node_eval_ok")'
run /opt/node24/bin/node --check /opt/dshn/dshn-session-bridge.mjs
NODE_OPTIONS=--require=/opt/dshn/dshn-dns-preload.cjs run /opt/node24/bin/node -e 'console.log("with_preload_ok")'
UV_USE_IO_URING=0 run /opt/node24/bin/node -e 'console.log("no_io_uring_ok")'
run /opt/node24/bin/node --v8-pool-size=0 -e 'console.log("pool0_ok")'
