import copy
import json
import unittest
from types import SimpleNamespace as NS

from pyproj import Transformer
from shapely.geometry import Point

from heatrouting import rules, schema, model, world, routing, search, network, export
from heatrouting.sizing import assign
from heatrouting.validate import validate

TO_WGS = Transformer.from_crs(32637, 4326, always_xy=True).transform


def feature(fid, kind, coords, geom="Point", **props):
    def xy(v):
        if isinstance(v[0], (int, float)):
            return TO_WGS(414000 + v[0], 6173000 + v[1])
        return [xy(x) for x in v]

    return dict(
        type="Feature",
        geometry=dict(type=geom, coordinates=xy(coords)),
        properties=dict(id=fid, object_type=kind, **props),
    )


def fixture(extra=(), flow=20):
    return dict(
        type="FeatureCollection",
        features=[
            feature("src", "source", (0, 0)),
            feature(
                "old", "heat_network", [(0, 0), (0, 200)], "LineString", diameter=200
            ),
            feature("ch", "heat_chamber", (0, 100)),
            feature(1, "oks_connection_point", (100, 100), flow_tph=flow),
            *extra,
        ],
    )


def simple_output(data):
    # Analytically known route to an existing chamber, no optimizer involved.
    return dict(
        type="FeatureCollection",
        features=[
            feature(
                "new",
                "heat_network",
                [(100, 100), (0, 100)],
                "LineString",
                variant_id="1",
                start_node_id=1,
                end_node_id="ch",
                flow_tph=20,
                diameter=100,
                length=100,
                laying_method="base",
                depth_start=None,
                depth_end=None,
                cost=8974800,
            ),
            dict(
                type="Feature",
                geometry=None,
                properties=dict(
                    id="summary",
                    object_type="variant_summary",
                    variant_id="1",
                    rank=1,
                    construction_cost=13974800,
                    chamber_construction_cost=0,
                    existing_chamber_tie_in_count=1,
                    existing_chamber_tie_in_cost=5000000,
                    unconnected_penalty=0,
                    calculated_cost=13974800,
                    new_network_length=100,
                    score=0.6912944,
                    unconnected_oks_ids=[],
                ),
            ),
        ],
    )


class ContractTests(unittest.TestCase):
    def test_appendix_example(self):
        data = fixture()
        report = validate(data, simple_output(data))
        self.assertTrue(report["valid"], report)

    def test_reversed_endpoint_rejected(self):
        data = fixture()
        out = simple_output(data)
        p = out["features"][0]["properties"]
        p["start_node_id"], p["end_node_id"] = p["end_node_id"], p["start_node_id"]
        self.assertFalse(validate(data, out)["valid"])

    def test_missing_demand_penalty_rejected(self):
        data = fixture()
        data["features"].append(
            feature("unreachable", "oks_connection_point", (300, 300), flow_tph=1)
        )
        report = validate(data, simple_output(data))
        self.assertTrue(any("unconnected" in e for e in report["errors"]), report)

    def test_invalid_input(self):
        data = fixture(
            [
                feature(
                    "bowtie",
                    "restriction",
                    [[(20, 20), (40, 40), (20, 40), (40, 20), (20, 20)]],
                    "Polygon",
                    restriction_type="oks",
                )
            ]
        )
        with self.assertRaisesRegex(schema.InputError, "invalid"):
            schema.read_input(data)
        data = fixture()
        data["features"].append(copy.deepcopy(data["features"][-1]))
        with self.assertRaisesRegex(schema.InputError, "duplicate"):
            schema.read_input(data)

    def test_uniform_length_diameter(self):
        t = network.Tree(
            root=0,
            xy={0: (0, 0), 1: (300, 0), 2: (600, 0)},
            parent={1: 0, 2: 1},
            children={0: [1], 1: [2], 2: []},
            term_node={"a": 2},
        )
        d = {"a": model.Demand("a", 3, Point(600, 0))}
        chains, _, errors = assign(t, d)
        self.assertFalse(errors)
        self.assertEqual([c["dn"] for c in chains], [150])

    def test_parallel_lengths_not_added(self):
        t = network.Tree(
            root=0,
            xy={0: (0, 0), 1: (50, 0), 2: (130, 0), 3: (50, 80)},
            parent={1: 0, 2: 1, 3: 1},
            children={0: [1], 1: [2, 3], 2: [], 3: []},
            term_node={"a": 2, "b": 3},
        )
        d = {n: model.Demand(n, 1, Point(t.xy[v])) for n, v in t.term_node.items()}
        chains, _, errors = assign(t, d)
        self.assertFalse(errors)
        self.assertEqual([c["dn"] for c in chains], [50, 50, 50])
        try:
            from heatrouting.core import _core
        except ImportError:
            self.skipTest("C++ not built")
        other, _, errors = assign(t, d, _core)
        self.assertFalse(errors)
        self.assertEqual(chains, other)

    def test_railway_is_prohibited(self):
        self.assertEqual(
            rules.restriction_rule("railway"), {"mode": "prohibited", "clearance": 1.0}
        )

    def test_unreachable_is_accounted(self):
        import numpy as np

        m = NS(
            demands={"lost": model.Demand("lost", 1, Point(0, 0))},
            sections={},
            chambers={},
        )
        w = NS(model=m, candidates=[world.Candidate(0, 1, 0, Point(0, 0), vertex=0)])
        s = search.Solver(
            w, {"lost": (np.array([np.inf]), np.array([-1]))}, log=lambda _: None
        )
        _, e = s.construct()
        self.assertEqual(e.unrouted, ["lost"])
        self.assertEqual(e.totals["penalty"], 100500000)


class GeometryTests(unittest.TestCase):
    def evaluate_line(self, data, existing=True):
        from heatrouting.geometry import Geometry

        m = model.load(data)
        pt = m.chambers["ch"].pt
        cand = world.Candidate(0, "old", 100, pt, "ch" if existing else None, vertex=0)
        tree = network.Tree(
            root=0,
            xy={0: tuple(pt.coords[0]), 1: tuple(m.demands[1].pt.coords[0])},
            parent={1: 0},
            children={0: [1], 1: []},
            term_node={1: 1},
        )
        w = NS(model=m, geometry=Geometry(m))
        ev = network.finalize(w, network.evaluate(w, [network.Group([1], cand, tree)]))
        return w, ev

    def test_road_and_overlapping_gas(self):
        road = feature(
            "road",
            "restriction",
            [[(40, 0), (60, 0), (60, 200), (40, 200), (40, 0)]],
            "Polygon",
            restriction_type="road",
        )
        gas = feature(
            "gas",
            "restriction",
            [(50, 0), (50, 200)],
            "LineString",
            restriction_type="gas_pipeline",
        )
        data = fixture([road, gas])
        w, ev = self.evaluate_line(data)
        self.assertFalse(ev.violations, ev.violations)
        # Road special zone is 20+3+3 m; gas overlap uses max(1.6,1.25).
        expected = rules.cost_new(100) * (74 + 26 * 1.6)
        self.assertAlmostEqual(ev.totals["construction"], expected, places=2)
        report = validate(data, export.to_collection(w, [ev]))
        self.assertTrue(report["valid"], report)
        self.assertEqual(len(ev.sections), 5)  # split where the crossing set changes

    def test_gas_crossing(self):
        data = fixture(
            [
                feature(
                    "gas",
                    "restriction",
                    [(50, 0), (50, 200)],
                    "LineString",
                    restriction_type="gas_pipeline",
                )
            ]
        )
        w, ev = self.evaluate_line(data)
        self.assertFalse(ev.violations, ev.violations)
        self.assertAlmostEqual(
            ev.totals["construction"], rules.cost_new(100) * (96 + 4 * 1.25), places=2
        )
        report = validate(data, export.to_collection(w, [ev]))
        self.assertTrue(report["valid"], report)

    def test_railway_blocks(self):
        data = fixture(
            [
                feature(
                    "rail",
                    "restriction",
                    [[(40, 0), (60, 0), (60, 200), (40, 200), (40, 0)]],
                    "Polygon",
                    restriction_type="railway",
                )
            ]
        )
        _, ev = self.evaluate_line(data)
        self.assertTrue(any("clearance" in v for v in ev.violations), ev.violations)

    def test_new_chamber_includes_connection(self):
        data = fixture()
        data["features"] = [
            f for f in data["features"] if f["properties"]["id"] != "ch"
        ]
        m = model.load(data)
        pt = m.sections["old"].line.interpolate(100)
        cand = world.Candidate(0, "old", 100, pt, vertex=0)
        tree = network.Tree(
            root=0,
            xy={0: tuple(pt.coords[0]), 1: tuple(m.demands[1].pt.coords[0])},
            parent={1: 0},
            children={0: [1], 1: []},
            term_node={1: 1},
        )
        from heatrouting.geometry import Geometry

        w = NS(model=m, geometry=Geometry(m))
        ev = network.finalize(w, network.evaluate(w, [network.Group([1], cand, tree)]))
        self.assertEqual(ev.totals["tie_in"], 0)
        self.assertEqual(ev.totals["chambers"], 3000000)
        report = validate(data, export.to_collection(w, [ev]))
        self.assertTrue(report["valid"], report)

    def test_existing_chamber_prices_each_ray(self):
        data = fixture()
        data["features"].append(
            feature("second", "oks_connection_point", (-100, 100), flow_tph=20)
        )
        m = model.load(data)
        pt = m.chambers["ch"].pt
        cand = world.Candidate(0, "old", 100, pt, "ch", vertex=0)
        tree = network.Tree(
            root=0,
            xy={
                0: tuple(pt.coords[0]),
                1: tuple(m.demands[1].pt.coords[0]),
                2: tuple(m.demands["second"].pt.coords[0]),
            },
            parent={1: 0, 2: 0},
            children={0: [1, 2], 1: [], 2: []},
            term_node={1: 1, "second": 2},
        )
        from heatrouting.geometry import Geometry

        w = NS(model=m, geometry=Geometry(m))
        ev = network.finalize(
            w, network.evaluate(w, [network.Group([1, "second"], cand, tree)])
        )
        self.assertEqual(ev.totals["tie_in"], 10000000)
        self.assertEqual(ev.totals["tie_in_count"], 2)
        report = validate(data, export.to_collection(w, [ev]))
        self.assertTrue(report["valid"], report)

    def test_simple_pipeline(self):
        m = model.load(fixture())
        w = world.World(m, world.Params())
        graph = routing.Graph(w)
        solver = search.Solver(w, graph.all_terminal_paths(), log=lambda _: None)
        _, ev = solver.run(restarts=1)
        ev = network.finalize(w, ev)
        self.assertFalse(ev.unrouted)
        self.assertFalse(ev.violations, ev.violations)
        report = validate(m.input_data, export.to_collection(w, [ev]))
        self.assertTrue(report["valid"], report)


class ChamberAndMasterTests(unittest.TestCase):
    def test_shared_new_chamber_charged_once(self):
        from heatrouting.geometry import Geometry
        from run_partition import seed_columns
        from heatrouting.partition import Master

        data = fixture()
        data["features"] = [
            f for f in data["features"] if f["properties"]["id"] != "ch"
        ]
        data["features"].append(
            feature("left", "oks_connection_point", (-100, 100), flow_tph=20)
        )
        m = model.load(data)
        pt = m.sections["old"].line.interpolate(100)
        w = NS(model=m, geometry=Geometry(m))
        groups = []
        for did in [1, "left"]:
            cand = world.Candidate(0, "old", 100, pt, vertex=0)
            tree = network.Tree(
                root=0,
                xy={0: tuple(pt.coords[0]), 1: tuple(m.demands[did].pt.coords[0])},
                parent={1: 0},
                children={0: [1], 1: []},
                term_node={did: 1},
            )
            groups.append(network.Group([did], cand, tree))
        ev = network.finalize(w, network.evaluate(w, groups))
        self.assertFalse(ev.violations, ev.violations)
        self.assertEqual(ev.totals["chambers"], 3000000)
        report = validate(data, export.to_collection(w, [ev]))
        self.assertTrue(report["valid"], report)
        columns = seed_columns(w, NS(group_cache=dict(enumerate(groups))))
        result = Master(w, columns, log=lambda _: None).solve(time_limit=5)
        self.assertIsNotNone(result.evaluation)
        self.assertAlmostEqual(
            rules.W_COST * result.objective / rules.COST_BASE, ev.score, places=6
        )
        self.assertLessEqual(result.lower_bound_family, result.objective + 0.01)

    def test_full_existing_chamber_allows_new_nearby(self):
        from heatrouting.geometry import Geometry

        data = fixture(
            [
                feature(
                    "cross",
                    "heat_network",
                    [(-10, 100), (10, 100)],
                    "LineString",
                    diameter=200,
                )
            ]
        )
        data["features"][-2] = feature(
            1, "oks_connection_point", (100, 105), flow_tph=20
        )
        m = model.load(data)
        self.assertEqual(m.chambers["ch"].degree, 4)
        pt = m.sections["old"].line.interpolate(105)
        cand = world.Candidate(0, "old", 105, pt, vertex=0)
        tree = network.Tree(
            root=0,
            xy={0: tuple(pt.coords[0]), 1: tuple(m.demands[1].pt.coords[0])},
            parent={1: 0},
            children={0: [1], 1: []},
            term_node={1: 1},
        )
        w = NS(model=m, geometry=Geometry(m))
        ev = network.finalize(w, network.evaluate(w, [network.Group([1], cand, tree)]))
        self.assertFalse(ev.violations, ev.violations)
        report = validate(data, export.to_collection(w, [ev]))
        self.assertTrue(report["valid"], report)

    def test_malformed_output_returns_diagnostics(self):
        data = fixture()
        out = simple_output(data)
        for field, value in [("diameter", []), ("start_node_id", []), ("cost", "bad")]:
            broken = copy.deepcopy(out)
            broken["features"][0]["properties"][field] = value
            self.assertFalse(validate(data, broken)["valid"])


class FurtherRegressionTests(unittest.TestCase):
    evaluate_line = GeometryTests.evaluate_line

    def test_existing_capacity_does_not_trigger_reconstruction(self):
        w, ev = self.evaluate_line(fixture(flow=500))
        self.assertFalse(ev.violations, ev.violations)
        self.assertEqual(ev.totals["recon"], 0)
        self.assertEqual(ev.totals["chamber_recon"], 0)
        self.assertEqual(ev.sections[0]["dn"], 400)
        self.assertTrue(
            validate(w.model.input_data, export.to_collection(w, [ev]))["valid"]
        )

    def test_linear_road_angle(self):
        data = fixture(
            [
                feature(
                    "roadline",
                    "restriction",
                    [(40, 96), (60, 104)],
                    "LineString",
                    restriction_type="road",
                )
            ]
        )
        _, ev = self.evaluate_line(data)
        self.assertTrue(any("angle" in e for e in ev.violations), ev.violations)

    def test_heat_network_crossing_without_connection(self):
        data = fixture(
            [
                feature(
                    "otherheat",
                    "heat_network",
                    [(50, 0), (50, 200)],
                    "LineString",
                    diameter=300,
                )
            ]
        )
        w, ev = self.evaluate_line(data)
        self.assertFalse(ev.violations, ev.violations)
        self.assertAlmostEqual(
            ev.totals["construction"], rules.cost_new(100) * (96 + 4 * 1.05), places=2
        )
        report = validate(data, export.to_collection(w, [ev]))
        self.assertTrue(report["valid"], report)

    def test_nearest_boundary_entry_pipeline(self):
        data = fixture(
            [
                feature(
                    "building",
                    "restriction",
                    [[(90, 90), (110, 90), (110, 110), (90, 110), (90, 90)]],
                    "Polygon",
                    restriction_type="oks",
                )
            ]
        )
        m = model.load(data)
        w = world.World(m, world.Params())
        graph = routing.Graph(w)
        solver = search.Solver(w, graph.all_terminal_paths(), log=lambda _: None)
        _, ev = solver.run(restarts=1)
        ev = network.finalize(w, ev)
        self.assertFalse(ev.unrouted, ev.unrouted)
        self.assertFalse(ev.violations, ev.violations)
        report = validate(data, export.to_collection(w, [ev]))
        self.assertTrue(report["valid"], report)

    def test_economic_penalty_cannot_change_restart_coverage(self):
        # A later restart with a lower monetary score but fewer connected demands
        # must never replace a feasible connected incumbent.
        from unittest.mock import patch

        complete = NS(unrouted=[], penalized=100.0, label=lambda: "complete")
        incomplete = NS(unrouted=["x"], penalized=1.0, label=lambda: "incomplete")
        w = NS(params=NS(seed=1), candidates=[], model=NS(demands={}))
        solver = search.Solver(w, {}, log=lambda _: None)
        with patch.object(
            solver, "construct", side_effect=[([], complete), ([], incomplete)]
        ), patch.object(
            solver, "agglomerate", side_effect=lambda gs, e: (gs, e)
        ), patch.object(
            solver, "improve", side_effect=lambda gs, e: (gs, e)
        ):
            _, best = solver.run(restarts=2)
        self.assertIs(best, complete)


if __name__ == "__main__":
    unittest.main()
