#!/usr/bin/env python3
"""
CarOS Python Bridge
- Přijímá telemetrii z Android přes local HTTP
- Zapisuje do InfluxDB
- Publishuje na MQTT
"""
import http.server
import json
import threading
import logging
import sys
from datetime import datetime

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
log = logging.getLogger('caros_bridge')

INFLUX_URL = "http://localhost:8086"
INFLUX_ORG = "caros"
INFLUX_BUCKET = "telemetry"
INFLUX_TOKEN = "caros-token"
MQTT_HOST = "localhost"
MQTT_PORT = 1883

# Try to import optional deps
try:
    from influxdb_client import InfluxDBClient, Point
    from influxdb_client.client.write_api import SYNCHRONOUS
    influx_client = InfluxDBClient(url=INFLUX_URL, token=INFLUX_TOKEN, org=INFLUX_ORG)
    write_api = influx_client.write_api(write_options=SYNCHRONOUS)
    INFLUX_AVAILABLE = True
    log.info("InfluxDB client initialized")
except ImportError:
    INFLUX_AVAILABLE = False
    log.warning("influxdb-client not available, data will not be stored")

try:
    import paho.mqtt.client as mqtt
    mqtt_client = mqtt.Client(client_id="caros_bridge")
    mqtt_client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
    mqtt_client.loop_start()
    MQTT_AVAILABLE = True
    log.info("MQTT client connected")
except Exception as e:
    MQTT_AVAILABLE = False
    log.warning(f"MQTT not available: {e}")


def write_to_influx(data: dict):
    if not INFLUX_AVAILABLE:
        return
    try:
        point = Point("caros_telemetry")
        for key, value in data.items():
            if key in ("topic", "value"):
                continue
            if isinstance(value, (int, float)):
                point = point.field(key, float(value))
        point = point.time(datetime.utcnow())
        write_api.write(bucket=INFLUX_BUCKET, org=INFLUX_ORG, record=point)
    except Exception as e:
        log.warning(f"InfluxDB write error: {e}")


def publish_mqtt(data: dict):
    if not MQTT_AVAILABLE:
        return
    # If message has explicit topic/value
    if "topic" in data and "value" in data:
        try:
            mqtt_client.publish(data["topic"], str(data["value"]))
        except Exception as e:
            log.warning(f"MQTT publish error: {e}")
        return
    # Otherwise publish each key as caros/can/<key>
    for key, value in data.items():
        try:
            mqtt_client.publish(f"caros/can/{key}", str(value))
        except Exception as e:
            log.warning(f"MQTT publish error for {key}: {e}")


def calculate_oil_life(session_data: dict) -> float:
    cold_starts = session_data.get('cold_starts', 0)
    idle_hours = session_data.get('idle_hours', 0)
    high_load_minutes = session_data.get('high_load_min', 0)
    short_trips = session_data.get('short_trips', 0)
    degradation = (cold_starts * 0.3 +
                   idle_hours * 0.5 +
                   high_load_minutes * 0.02 +
                   short_trips * 0.4)
    return max(0.0, 100.0 - degradation)


class TelemetryHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length)
            data = json.loads(body)

            write_to_influx(data)
            publish_mqtt(data)

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"ok":true}')
        except Exception as e:
            log.error(f"Handler error: {e}")
            self.send_response(500)
            self.end_headers()

    def do_GET(self):
        """Health check"""
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        status = {
            "status": "ok",
            "influx": INFLUX_AVAILABLE,
            "mqtt": MQTT_AVAILABLE
        }
        self.wfile.write(json.dumps(status).encode())

    def log_message(self, format, *args):
        pass  # suppress default access log


if __name__ == "__main__":
    host = "localhost"
    port = 8765
    server = http.server.HTTPServer((host, port), TelemetryHandler)
    log.info(f"CarOS Python Bridge listening on {host}:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("Bridge stopped")
        sys.exit(0)
