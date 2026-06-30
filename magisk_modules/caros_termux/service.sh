#!/system/bin/sh
# CarOS Termux Services — Magisk service script

sleep 30

# Grant com.termux RUN_COMMAND permission
pm grant com.termux com.termux.permission.RUN_COMMAND 2>/dev/null || true

# Start Termux boot scripts
run-as com.termux bash ~/.termux/boot/caros_services.sh 2>/dev/null &
