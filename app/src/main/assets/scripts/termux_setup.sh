#!/data/data/com.termux/files/usr/bin/bash
set -e
cd ~

echo "STATUS:Aktualizuji balíčky..."
pkg update -y -o Dpkg::Options::="--force-confold" 2>/dev/null || true

echo "STATUS:Instaluji Python..."
pkg install -y python 2>/dev/null

echo "STATUS:Instaluji MQTT broker..."
pkg install -y mosquitto 2>/dev/null

echo "STATUS:Instaluji databázi InfluxDB..."
pkg install -y influxdb 2>/dev/null || echo "WARN:InfluxDB unavailable, skipping"

echo "STATUS:Instaluji SSH server..."
pkg install -y openssh 2>/dev/null

echo "STATUS:Instaluji Syncthing..."
pkg install -y syncthing 2>/dev/null || echo "WARN:Syncthing unavailable"

echo "STATUS:Instaluji Python knihovny..."
pip install influxdb-client paho-mqtt requests 2>/dev/null || pip3 install paho-mqtt requests 2>/dev/null || true

echo "STATUS:Nastavuji SSH klíče..."
if [ ! -f ~/.ssh/id_ed25519 ]; then
    mkdir -p ~/.ssh
    ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N "" -q
    cat ~/.ssh/id_ed25519.pub >> ~/.ssh/authorized_keys
    chmod 700 ~/.ssh
    chmod 600 ~/.ssh/authorized_keys
fi

echo "STATUS:Nastavuji MQTT..."
mkdir -p ~/.config/mosquitto
cat > ~/.config/mosquitto/mosquitto.conf << 'EOF'
listener 1883 127.0.0.1
allow_anonymous true
persistence true
persistence_location /data/data/com.termux/files/home/.config/mosquitto/
EOF

echo "STATUS:Kopíruji CarOS bridge..."
mkdir -p ~/caros
# bridge.py will be pushed separately by CarOS app

echo "STATUS:Nastavuji autostart..."
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/caros_services.sh << 'BOOTEOF'
#!/data/data/com.termux/files/usr/bin/bash
sshd
mosquitto -d -c ~/.config/mosquitto/mosquitto.conf 2>/dev/null || true
influxd run --config ~/.config/influxdb/influxdb.conf > /dev/null 2>&1 &
[ -f ~/caros/bridge.py ] && python3 ~/caros/bridge.py > /tmp/bridge.log 2>&1 &
syncthing serve --no-browser > /dev/null 2>&1 &
BOOTEOF
chmod +x ~/.termux/boot/caros_services.sh

echo "DONE:Systémové služby jsou připraveny. Restartuj Termux pro aplikaci změn."
