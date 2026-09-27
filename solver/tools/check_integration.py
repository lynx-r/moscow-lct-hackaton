"""Exercise a running Java backend and independently validate its solver result."""

import argparse
import json
from pathlib import Path
import sys
from urllib.request import Request, build_opener, ProxyHandler
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from heatrouting.validate import validate

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("input")
parser.add_argument("--url", default="http://localhost:8080")
parser.add_argument("--multipart", action="store_true")
parser.add_argument("--out", default="out/integration_api")
args = parser.parse_args()
payload = Path(args.input).read_bytes()
content_type = "application/json"
endpoint = "/routing/process-json"
if args.multipart:
    boundary = "heat-routing-" + uuid.uuid4().hex
    payload = (
        (
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="input.geojson"\r\n'
            "Content-Type: application/geo+json\r\n\r\n"
        ).encode()
        + payload
        + f"\r\n--{boundary}--\r\n".encode()
    )
    content_type = "multipart/form-data; boundary=" + boundary
    endpoint = "/routing/process"
request = Request(
    args.url.rstrip("/") + endpoint,
    data=payload,
    headers={"Content-Type": content_type},
)
with build_opener(ProxyHandler({})).open(request, timeout=910) as response:
    result = json.load(response)["featureCollection"]
report = validate(args.input, result)
folder = Path(args.out)
folder.mkdir(parents=True, exist_ok=True)
(folder / "result.geojson").write_text(
    json.dumps(result, ensure_ascii=False) + "\n", encoding="utf-8"
)
(folder / "validation.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
raise SystemExit(0 if report["valid"] else 1)
