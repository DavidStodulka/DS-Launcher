#!/system/bin/sh
# CarOS Audio Foundation — Magisk service script

MODDIR=${0%/*}

# Wait for system to be ready
sleep 10

# Allow audio device access
chmod 666 /dev/snd/* 2>/dev/null || true

# Set SELinux context for audio libs
chcon u:object_r:system_file:s0 /system/lib/soundfx/*.so 2>/dev/null || true

# Detect and set backend property
if pm list packages | grep -q "james.dsp"; then
    setprop persist.caros.audio.backend "jamesdsp"
    setprop persist.caros.audio.jamesdsp_available "1"
elif pm list packages | grep -q "pittvandewitt.viperfx\|audlabs.viperfx"; then
    setprop persist.caros.audio.backend "viper4android"
    setprop persist.caros.audio.jamesdsp_available "0"
else
    setprop persist.caros.audio.backend "native"
    setprop persist.caros.audio.jamesdsp_available "0"
fi
