"""Reference tables and formulas of the technical appendix. Data plus tiny pure functions.

Every number in the optimiser and in the validator must come from here.
"""
from __future__ import annotations

# (DN mm, capacity t/h, max run length m, new construction UNI/m, reconstruction UNI/m)  -- appendix 4.1
DN_TABLE = [
    (50, 3.5, 181, 74_023, 96_180),
    (65, 8.3, 245, 78_631, 109_989),
    (80, 13.2, 327, 83_530, 117_582),
    (100, 22.3, 419, 89_748, 133_694),
    (125, 40.2, 554, 97_275, 148_030),
    (150, 65.1, 696, 105_507, 152_295),
    (200, 152.3, 1042, 120_275, 181_766),
    (250, 274.9, 1379, 135_323, 202_030),
    (300, 437.4, 1718, 150_022, 228_707),
    (400, 943.1, 2477, 190_299, 271_317),
    (500, 1663.4, 3245, 224_137, 333_884),
    (600, 2627.7, 4037, 264_790, 372_703),
    (700, 3735.1, 4775, 324_298, 439_571),
    (800, 5296.8, 5644, 325_996, 489_918),
    (900, 7165.0, 6518, 327_693, 553_607),
    (1000, 9391.8, 7419, 418_777, 606_679),
    (1200, 15012.8, 9288, 428_074, 825_692),
    (1400, 22501.9, 11276, 683_417, 978_584),
]
DNS = [r[0] for r in DN_TABLE]
_CAP = {r[0]: r[1] for r in DN_TABLE}
_MAXLEN = {r[0]: float(r[2]) for r in DN_TABLE}
_CNEW = {r[0]: float(r[3]) for r in DN_TABLE}
_CREC = {r[0]: float(r[4]) for r in DN_TABLE}

# pipe-pair design width, m  -- appendix 4.2
DESIGN_WIDTH = {50: 0.400, 65: 0.430, 80: 0.470, 100: 0.510, 125: 0.600, 150: 0.650, 200: 0.880,
                250: 1.050, 300: 1.150, 400: 1.370, 500: 1.670, 600: 1.850, 700: 2.050, 800: 2.250,
                900: 2.450, 1000: 2.650, 1200: 3.100, 1400: 3.450}


def dn_for_flow(flow: float) -> int | None:
    """Smallest DN whose capacity is at least the flow; None if above the table."""
    for dn in DNS:
        if _CAP[dn] >= flow - 1e-9:
            return dn
    return None


def capacity(dn: int) -> float:
    return _CAP[dn]


def max_len(dn: int) -> float:
    return _MAXLEN[dn]


def cost_new(dn: int) -> float:
    return _CNEW[dn]


def cost_recon(dn: int) -> float:
    return _CREC[dn]


def next_dn(dn: int) -> int | None:
    i = DNS.index(dn)
    return DNS[i + 1] if i + 1 < len(DNS) else None


def dn_ge(dn: int) -> int:
    """Round an off-table DN up to the next table DN (used only for comparisons)."""
    for d in DNS:
        if d >= dn:
            return d
    return DNS[-1]


def chamber_cost(dn: int) -> float:  # appendix 8.2
    if dn <= 200:
        return 3_000_000.0
    if dn <= 500:
        return 5_000_000.0
    if dn <= 1000:
        return 8_000_000.0
    return 12_000_000.0


TIE_IN_COST = 5_000_000.0  # appendix 8.2


def penalty(flow: float) -> float:  # appendix 8.3
    return 100_000_000.0 + 500_000.0 * flow


COST_BASE, LEN_BASE, W_COST, W_LEN = 25_000_000.0, 100.0, 0.7, 0.3  # appendix 9


def score(cost: float, length: float) -> float:
    return W_COST * cost / COST_BASE + W_LEN * length / LEN_BASE


LAMBDA = (W_LEN / LEN_BASE) / (W_COST / COST_BASE)  # UNI per metre equivalent of the length term


def oks_clearance(dn: int) -> float:  # appendix 5.1, existing OKS
    if dn < 500:
        return 5.0
    if dn <= 800:
        return 7.0
    return 9.0


# Restriction type registry (appendix 5.1) with aliases seen in the sample dataset.
# mode: "prohibited" (bypass with clearance) or "special" (crossable as a special section).
RESTRICTIONS: dict[str, dict] = {
    "oks": {"mode": "prohibited", "clearance": "oks"},
    "oks_existing": {"mode": "prohibited", "clearance": "oks"},
    "park": {"mode": "prohibited", "clearance": 1.0},
    "social_area": {"mode": "prohibited", "clearance": 1.0},
    "prohibited_site": {"mode": "prohibited", "clearance": 1.0},
    "water": {"mode": "prohibited", "clearance": 1.0},
    "road": {"mode": "special", "clearance": 1.5, "angle": 45, "zone": 3.0, "k": 1.60},
    "tram_tracks": {"mode": "special", "clearance": 1.5, "angle": 45, "zone": 3.0, "k": 1.75},
     # Current appendix: railway is prohibited.
    "railway": {"mode": "prohibited", "clearance": 1.0},
    "gas_pipeline": {"mode": "special", "clearance": 2.0, "zone": 2.0, "k": 1.25},
    "power_cable": {"mode": "special", "clearance": 2.0, "zone": 2.0, "k": 1.15},
    "heat_network": {"mode": "special", "clearance": 1.0, "zone": 2.0, "k": 1.05},
}
UNKNOWN_RESTRICTION = {"mode": "prohibited", "clearance": 1.0, "unknown": True}


def restriction_rule(rtype: str) -> dict:
    return RESTRICTIONS.get(rtype, UNKNOWN_RESTRICTION)


# Current written specification. Historical reconstruction columns above are retained
# only to read archived experiments; current evaluation never charges them.
SPEC_VERSION = "2026-09-27"
K_BEND = 1.0
BEND_TOL_DEG = 0.01
MAX_LEN_UPGRADE_STEPS = len(DNS)
GEOM_TOL = 0.01  # metres: serialization tolerance, not a clearance relaxation
DESIGN_HEIGHT = dict(zip(DNS, [.125, .140, .160, .180, .225, .250, .315, .400,
                                    .450, .560, .710, .800, .900, 1., 1.1, 1.2, 1.425, 1.6]))
EXISTING_WIDTH = {"gas_pipeline": .4, "power_cable": .2}
