import concurrent.futures
import datetime as dt
import json
import logging
import os
import random
import sqlite3
import time
import urllib.request
import uuid
from pathlib import Path


def utcnow():
    return dt.datetime.now(dt.timezone.utc).isoformat()


class Spool:
    def __init__(self, path, limit=10000):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.limit = limit
        self.db.execute("CREATE TABLE IF NOT EXISTS batches(id INTEGER PRIMARY KEY, body TEXT NOT NULL)")

    def push(self, body):
        if self.db.execute("SELECT COUNT(*) FROM batches").fetchone()[0] >= self.limit:
            raise RuntimeError("Spool full; collection paused")
        with self.db:
            self.db.execute("INSERT INTO batches(body) VALUES(?)", (json.dumps(body),))

    def flush(self, url, token):
        while row := self.db.execute("SELECT id,body FROM batches ORDER BY id LIMIT 1").fetchone():
            body = json.loads(row[1])
            # The server replay window is 24 hours. Expire older samples explicitly.
            if body["samples"] and any(dt.datetime.fromisoformat(s["observedAt"]) <
                    dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=23) for s in body["samples"]):
                logging.warning("Expiring batch outside replay window: %s", body["batchId"])
            else:
                request = urllib.request.Request(url, data=row[1].encode(), headers={
                    "Content-Type": "application/json", "Authorization": "Bearer " + token})
                with urllib.request.urlopen(request, timeout=10) as response:
                    if response.status != 200:
                        raise RuntimeError("Unexpected ingestion response")
            with self.db:
                self.db.execute("DELETE FROM batches WHERE id=?", (row[0],))


def probe(mac, ip, interface):
    from scapy.all import ARP, Ether, ICMP, IP, srp, sr1
    start = time.perf_counter_ns()
    result = sr1(IP(dst=ip)/ICMP(), timeout=1, verbose=False)
    elapsed = (time.perf_counter_ns() - start) // 1000
    ok = result is not None and result.haslayer(ICMP) and result[ICMP].type == 0
    reachable = ok
    if not ok:
        answers, _ = srp(Ether(dst="ff:ff:ff:ff:ff:ff")/ARP(pdst=ip),
                         iface=interface, timeout=1, verbose=False)
        reachable = any(a.psrc == ip and a.hwsrc.lower() == mac.lower() for _, a in answers)
    base = {"mac": mac, "ip": ip, "observedAt": utcnow(), "hostname": None}
    samples = [{**base, "probeKind": "ICMP", "resultCode": "OK" if ok else "TIMEOUT",
                "latencyUs": elapsed if ok else None, "reachable": reachable}]
    return samples


def dns_probe(mac, ip, resolver):
    # Explicit UDP DNS query gives a bounded timeout, unlike OS gethostbyaddr.
    from scapy.all import DNS, DNSQR, IP, UDP, sr1
    reverse = ".".join(reversed(ip.split("."))) + ".in-addr.arpa"
    start = time.perf_counter_ns()
    result = sr1(IP(dst=resolver)/UDP(dport=53)/DNS(rd=1, qd=DNSQR(qname=reverse, qtype="PTR")),
                 timeout=1, verbose=False)
    elapsed = (time.perf_counter_ns()-start)//1000
    ok = result is not None and result.haslayer(DNS) and result[DNS].rcode == 0
    hostname = None
    if ok and result[DNS].ancount:
        value = result[DNS].an[0].rdata
        if isinstance(value, bytes):
            hostname = value.decode(errors="replace").rstrip(".")[:253]
    return {"mac": mac, "ip": ip, "observedAt": utcnow(), "hostname": hostname,
            "probeKind": "DNS", "resultCode": "OK" if ok else "TIMEOUT" if result is None else "ERROR",
            "latencyUs": elapsed if ok else None, "reachable": None}


def synthetic():
    return [{"mac": f"02:00:00:00:00:{i:02x}", "ip": f"192.168.1.{i}",
             "hostname": f"demo-{i}", "observedAt": utcnow(), "probeKind": "ICMP",
             "resultCode": "OK", "latencyUs": random.randint(500, 15000), "reachable": True}
            for i in range(1, 13)]


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    mode = os.getenv("AGENT_MODE", "synthetic")
    interface = os.getenv("LAN_INTERFACE", "eth0")
    cidr = os.getenv("LAN_CIDR", "192.168.1.0/24")
    if mode == "raw":
        import ipaddress
        network = ipaddress.ip_network(cidr)
        if network.version != 4 or network.num_addresses > 4096:
            raise ValueError("ARP mode requires IPv4 subnet of at most 4096 addresses")
        from scapy.all import conf
        conf.iface = interface
    spool = Spool(os.getenv("SPOOL_PATH", "/data/spool.sqlite"))
    url = os.getenv("BACKEND_URL", "http://backend:8080") + "/api/v1/telemetry/batches"
    token = os.environ["AGENT_TOKEN"]
    known = {}
    dns_cache = {}
    backoff = 5
    while True:
        started = time.monotonic()
        try:
            # Drain backlog before adding a batch; a full spool can recover.
            try:
                spool.flush(url, token)
            except Exception:
                logging.warning("Upload unavailable; retaining backlog and continuing collection")
            if mode == "synthetic":
                samples = synthetic()
            else:
                from scapy.all import ARP, Ether, srp
                answers, _ = srp(Ether(dst="ff:ff:ff:ff:ff:ff")/ARP(pdst=cidr),
                                 iface=interface, timeout=2, verbose=False)
                for _, answer in answers:
                    known[answer.hwsrc.lower()] = answer.psrc
                samples = []
                with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
                    futures = [pool.submit(probe, mac, ip, interface) for mac, ip in known.items()]
                    for future in futures:
                        samples.extend(future.result())
                for sample in list(samples):
                    mac, ip = sample["mac"], sample["ip"]
                    cached = dns_cache.get(ip)
                    if cached is None or time.monotonic()-cached[0] > 300:
                        dns = dns_probe(mac, ip, os.getenv("DNS_RESOLVER", "192.168.1.1"))
                        dns_cache[ip] = (time.monotonic(), dns["hostname"])
                        samples.append(dns)
                    sample["hostname"] = dns_cache[ip][1]
            chunks = [samples[i:i+500] for i in range(0, len(samples), 500)] or [[]]
            for chunk in chunks:
                spool.push({"agentId": os.getenv("AGENT_ID", "agent-1"),
                            "segment": os.getenv("LAN_SEGMENT", "default"),
                            "batchId": str(uuid.uuid4()), "samples": chunk})
            spool.flush(url, token)
            backoff = 5
        except Exception:
            logging.exception("Collection or upload failed")
            backoff = min(backoff*2, 60)
        time.sleep(max(1, backoff-(time.monotonic()-started)))


if __name__ == "__main__":
    main()
