"""Pack graph arrays for C++ sizing and subset DP; retain Python plan evaluation."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from . import rules
from .network import tie_in_key
from .world import World


def available() -> bool:
    try:
        from .core import _core  # noqa: F401

        return getattr(_core, "SPEC_VERSION", None) == rules.SPEC_VERSION
    except ImportError:
        return False


def make_rules():
    from .core import _core

    r = _core.Rules()
    r.dns = list(rules.DNS)
    r.cap = [rules.capacity(d) for d in rules.DNS]
    r.maxlen = [rules.max_len(d) for d in rules.DNS]
    r.cnew = [rules.cost_new(d) for d in rules.DNS]
    r.crec = [rules.cost_recon(d) for d in rules.DNS]
    r.tie_in = rules.TIE_IN_COST
    r.pen_fixed, r.pen_flow = rules.penalty(0.0), rules.penalty(1.0) - rules.penalty(
        0.0
    )
    r.w_cost, r.cost_base, r.w_len, r.len_base = (
        rules.W_COST,
        rules.COST_BASE,
        rules.W_LEN,
        rules.LEN_BASE,
    )
    from .network import VIOLATION_WEIGHT

    r.viol_w = VIOLATION_WEIGHT
    r.k_bend, r.bend_tol, r.max_upgrade = (
        rules.K_BEND,
        rules.BEND_TOL_DEG,
        rules.MAX_LEN_UPGRADE_STEPS,
    )
    r.tier_dn = [200, 500, 1000, 100000]
    r.tier_cost = [
        rules.chamber_cost(200),
        rules.chamber_cost(500),
        rules.chamber_cost(1000),
        rules.chamber_cost(1400),
    ]
    return r


class CoreBridge:
    def __init__(self, world: World, dij: dict, graph=None):
        from .core import _core

        world.sizing_backend = _core
        m = world.model
        self.world = world
        self.term_ids = list(m.demands)
        self.term_index = {d: i for i, d in enumerate(self.term_ids)}
        sec_ids = list(m.sections)
        sec_index = {s: i for i, s in enumerate(sec_ids)}
        ch_ids = list(m.chambers)
        ch_index = {c: i for i, c in enumerate(ch_ids)}
        dist = np.stack([dij[d][0] for d in self.term_ids]).astype(np.float64)
        self.dij = dij
        self.directional = any(
            not isinstance(dij[d][1], np.ndarray) for d in self.term_ids
        )
        pred = (
            np.full(dist.shape, -9999, dtype=np.int64)
            if self.directional
            else np.stack([dij[d][1] for d in self.term_ids]).astype(np.int64)
        )
        sec = [m.sections[s] for s in sec_ids]
        n_nodes = len(m.nodes)
        node_secs = [
            [sec_index[s] for s in m.node_sections.get(n, [])] for n in range(n_nodes)
        ]
        self.core = _core.Core(
            make_rules(),
            np.ascontiguousarray(world.verts, dtype=np.float64),
            dist,
            pred,
            [world.terminal_vertex[d] for d in self.term_ids],
            [m.demands[d].flow for d in self.term_ids],
            [s.length for s in sec],
            [s.dn for s in sec],
            [s.flow_existing for s in sec],
            [
                (
                    sec_index.get(m.parent_section.get(s.up_node), -1)
                    if m.parent_section.get(s.up_node) is not None
                    else -1
                )
                for s in sec
            ],
            [s.up_node for s in sec],
            [s.down_node for s in sec],
            node_secs,
            [m.chambers[c].node for c in ch_ids],
            [m.chambers[c].dn for c in ch_ids],
            [m.chambers[c].degree for c in ch_ids],
            [c.vertex for c in world.candidates],
            [sec_index[c.section_id] for c in world.candidates],
            [c.chainage for c in world.candidates],
            [ch_index[c.chamber_id] if c.is_chamber else -1 for c in world.candidates],
        )

        if graph is not None:
            self.core.set_graph(
                [int(x) for x in graph.edges_u],
                [int(x) for x in graph.edges_v],
                [float(x) for x in graph.edges_len],
            )
            self.has_graph = True
        else:
            self.has_graph = False

    def make_group(self, terminals, cand):
        from .network import Tree, Group, make_group

        if self.directional:
            return make_group(self.world, self.dij, list(terminals), cand)
        xy, parents, terms, vertices = self.core.tree_data(
            [self.term_index[d] for d in terminals], cand.id
        )
        tree = Tree(root=cand.vertex)
        for i, v in enumerate(vertices):
            tree.xy[v] = tuple(xy[i])
            tree.children.setdefault(v, [])
            if parents[i] >= 0:
                parent = vertices[parents[i]]
                tree.parent[v] = parent
                tree.children.setdefault(parent, []).append(v)
            if terms[i] >= 0:
                tree.term_node[self.term_ids[terms[i]]] = v
        tree.unrouted = [d for d in terminals if d not in tree.term_node]
        return Group(list(terminals), cand, tree)

    def evaluate(self, groups, dropped=()):
        from .network import evaluate

        return evaluate(self.world, groups, dropped)
