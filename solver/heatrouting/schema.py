"""Input contract checks. No optimizer state or geometric repairs are allowed here."""

from __future__ import annotations

import json
import math
from pathlib import Path

from shapely.geometry import shape
from shapely.validation import explain_validity

from . import rules


class InputError(ValueError):
    pass


def scalar_id(value):
    return (
        isinstance(value, (str, int, float))
        and not isinstance(value, bool)
        and (not isinstance(value, float) or math.isfinite(value))
    )


def number(value):
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
    )


def read_input(source):
    if isinstance(source, (str, Path)):
        try:
            with open(source, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError) as exc:
            raise InputError(f"Cannot read GeoJSON: {exc}") from exc
    else:
        data = source
    if (
        not isinstance(data, dict)
        or data.get("type") != "FeatureCollection"
        or not isinstance(data.get("features"), list)
    ):
        raise InputError(
            "Input must be a GeoJSON FeatureCollection with a features array"
        )
    kinds = {
        "source": {"Point"},
        "heat_network": {"LineString"},
        "heat_chamber": {"Point"},
        "oks_connection_point": {"Point"},
        "restriction": {"LineString", "MultiLineString", "Polygon", "MultiPolygon"},
    }
    seen, counts = set(), {}
    for i, feature in enumerate(data["features"]):
        if not isinstance(feature, dict) or feature.get("type") != "Feature":
            raise InputError(f"Feature {i}: expected a Feature")
        p = feature.get("properties")
        if not isinstance(p, dict) or not scalar_id(p.get("id")):
            raise InputError(f"Feature {i}: id must be a finite number or string")
        fid = p["id"]
        if fid in seen:
            raise InputError(f"Feature {fid!r}: duplicate id")
        seen.add(fid)
        kind = p.get("object_type")
        if kind not in kinds:
            raise InputError(f"Feature {fid!r}: unsupported object_type {kind!r}")
        counts[kind] = counts.get(kind, 0) + 1
        try:
            g = shape(feature["geometry"])
        except Exception as exc:
            raise InputError(f"Feature {fid!r}: malformed geometry ({exc})") from exc
        if g.geom_type not in kinds[kind] or g.is_empty or not g.is_valid:
            raise InputError(
                f"Feature {fid!r}: invalid {g.geom_type} geometry: {explain_validity(g)}"
            )

        def coordinates(c):
            if isinstance(c[0], (int, float)):
                if (
                    len(c) != 2
                    or not all(number(x) for x in c)
                    or not (-180 <= c[0] <= 180 and -90 <= c[1] <= 90)
                ):
                    raise InputError(
                        f"Feature {fid!r}: expected finite 2D WGS84 coordinates"
                    )
            else:
                for item in c:
                    coordinates(item)

        coordinates(feature["geometry"]["coordinates"])
        if kind == "heat_network" and (
            not number(p.get("diameter")) or p["diameter"] not in rules.DNS
        ):
            raise InputError(
                f"Feature {fid!r}: diameter must belong to the reference table"
            )
        if kind == "oks_connection_point" and (
            not number(p.get("flow_tph")) or p["flow_tph"] <= 0
        ):
            raise InputError(
                f"Feature {fid!r}: flow_tph must be a positive finite number"
            )
        if (
            kind == "restriction"
            and p.get("restriction_type") == "oks"
            and g.geom_type not in {"Polygon", "MultiPolygon"}
        ):
            raise InputError(f"Feature {fid!r}: an OKS restriction must be a polygon")
        if kind == "restriction" and not isinstance(p.get("restriction_type"), str):
            raise InputError(f"Feature {fid!r}: restriction_type must be a string")
    if counts.get("source") != 1:
        raise InputError("Expected exactly one source")
    if not counts.get("heat_network"):
        raise InputError("Expected at least one existing heat_network")
    return data
