"""Integration checks against a running Compose stack; standard library only."""
import datetime as dt
import json
import os
import time
import urllib.error
import urllib.request
import uuid

URL = os.getenv("BACKEND_URL", "http://localhost:8080")
TOKEN = os.environ["AGENT_TOKEN"]
RUN_ID = "smoke-" + uuid.uuid4().hex[:12]


def request(path, body=None, token=TOKEN):
    req = urllib.request.Request(URL+"/api/v1"+path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": "Bearer "+token})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)


def batch(reachable=True):
    return {"agentId": RUN_ID, "segment": RUN_ID, "batchId": str(uuid.uuid4()), "samples": [{
        "mac": "02:00:00:ff:ff:01", "ip": "192.0.2.1", "hostname": "integration-test",
        "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "probeKind": "ICMP",
        "resultCode": "OK" if reachable else "TIMEOUT", "latencyUs": 1234 if reachable else None,
        "reachable": reachable}]}


def main():
    for attempt in range(60):
        try:
            request("/snapshot")
            break
        except (OSError, urllib.error.URLError):
            time.sleep(2)
    else:
        raise RuntimeError("Backend did not become ready")
    first = batch()
    assert request("/telemetry/batches", first)["duplicate"] is False
    assert request("/telemetry/batches", first)["duplicate"] is True
    first["samples"][0]["hostname"] = "changed-payload"
    try:
        request("/telemetry/batches", first)
        raise AssertionError("Conflicting replay accepted")
    except urllib.error.HTTPError as error:
        assert error.code == 409
    for _ in range(3):
        request("/telemetry/batches", batch(False))
    snapshot = request("/snapshot")
    device = next(d for d in snapshot["devices"] if d["segment"] == RUN_ID)
    assert device["status"] == "DOWN"
    rows = request(f'/devices/{device["device_id"]}/latency')
    assert len(rows) == 4, "Replay duplicated samples"
    request("/telemetry/batches", batch())
    assert next(d for d in request("/snapshot")["devices"] if d["segment"] == RUN_ID)["status"] == "UP"
    try:
        request("/snapshot", token="invalid")
        raise AssertionError("Unauthenticated snapshot accepted")
    except urllib.error.HTTPError as error:
        assert error.code == 401
    req = urllib.request.Request(URL+"/api/v1/stream", headers={"Authorization": "Bearer "+TOKEN})
    with urllib.request.urlopen(req, timeout=10) as response:
        lines = [response.readline().decode() for _ in range(3)]
        assert any("snapshot" in line for line in lines)
        assert any("devices" in line for line in lines)
    print("PASS: ingestion, replay, conflict, transitions, history, auth, SSE")


if __name__ == "__main__":
    main()
