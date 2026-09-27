"""Validate an exported 2D GeoJSON independently of the optimizer."""

import argparse
import json
from heatrouting.validate import validate

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input")
    parser.add_argument("result")
    parser.add_argument("--report")
    args = parser.parse_args()
    report = validate(args.input, args.result)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    raise SystemExit(0 if report["valid"] else 1)
