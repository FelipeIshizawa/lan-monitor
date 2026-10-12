"""Run against a disposable stack. Reports measurements, never invented scores."""
import argparse
import concurrent.futures
import datetime as dt
import json
import os
from pathlib import Path
import statistics
import time
import urllib.request
import uuid


def run(index, size, url, token):
    samples = [{"mac": f"02:aa:00:00:{i//256:02x}:{i%256:02x}", "ip": f"10.20.{i//254}.{i%254+1}",
                "hostname": None, "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                "probeKind": "ICMP", "resultCode": "OK", "latencyUs": 1000, "reachable": True}
               for i in range(size)]
    body = {"agentId": "benchmark", "segment": "benchmark", "batchId": str(uuid.uuid4()), "samples": samples}
    request = urllib.request.Request(url+"/api/v1/telemetry/batches",data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer "+token})
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=60) as response:
        assert json.load(response)["accepted"]
    return (time.perf_counter()-started)*1000


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--batches",type=int,default=100)
    parser.add_argument("--size",type=int,default=200)
    parser.add_argument("--workers",type=int,default=4)
    args = parser.parse_args()
    if not 1<=args.size<=1000 or args.batches<1 or args.workers<1:
        parser.error("size: 1..1000; batches/workers: positive")
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(run,i,args.size,os.getenv("BACKEND_URL","http://localhost:8080"),os.environ["AGENT_TOKEN"]) for i in range(args.batches)]
        measurements = sorted(f.result() for f in futures)
    elapsed = time.perf_counter()-started
    result = {"batches":args.batches,"batch_size":args.size,"workers":args.workers,
              "seconds":elapsed,"samples_per_second":args.batches*args.size/elapsed,
              "mean_ms":statistics.mean(measurements),
              "p95_ms":measurements[min(len(measurements)-1,int(len(measurements)*.95))],
              "p99_ms":measurements[min(len(measurements)-1,int(len(measurements)*.99))]}
    Path("benchmarks/results").mkdir(parents=True,exist_ok=True)
    Path("benchmarks/results/latest.json").write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))


if __name__ == "__main__":
    main()
