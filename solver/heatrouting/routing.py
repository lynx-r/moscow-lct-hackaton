"""Sparse visibility proposals with exact straight crossing and private entry checks."""

from __future__ import annotations
import time
import numpy as np
import scipy.sparse as sp
import shapely
from scipy.sparse.csgraph import dijkstra
from scipy.spatial import cKDTree
from .world import V_TERMINAL, V_CANDIDATE, World
from . import rules


class Graph:
    def __init__(self, world: World):
        self.world = world
        self.build()

    def build(self):
        w = self.world
        t0 = time.time()
        n = len(w.verts)
        pairs = cKDTree(w.verts).query_pairs(w.params.vis_radius, output_type="ndarray")
        # A terminal inside its host connects only through its prescribed entry gate.
        keep = np.ones(len(pairs), bool)
        for v, gate in w.entry_gate.items():
            touching = (pairs[:, 0] == v) | (pairs[:, 1] == v)
            keep &= ~touching | ((pairs[:, 0] == gate) | (pairs[:, 1] == gate))
        term = w.vkind == V_TERMINAL
        keep &= ~(term[pairs[:, 0]] & term[pairs[:, 1]])
        pairs = pairs[keep]
        coords = np.stack([w.verts[pairs[:, 0]], w.verts[pairs[:, 1]]], axis=1)
        segs = shapely.linestrings(coords)
        si, oi = w.tree_shrunk.query(segs, predicate="intersects")
        gate_demand = dict(w.entry_waypoint_demand)
        blocked = np.zeros(len(pairs), bool)
        for i, j in zip(si, oi):
            if not w.hard[j]:
                continue
            u, v = pairs[i]
            did = w.vertex_demand.get(int(u), w.vertex_demand.get(int(v)))
            if did is None and int(u) not in gate_demand and int(v) not in gate_demand:
                blocked[i] = True
        exact = np.zeros(len(pairs), bool)
        exact[si[~w.hard[oi]]] = True
        exact |= term[pairs[:, 0]] | term[pairs[:, 1]]
        exact |= np.isin(pairs[:, 0], list(gate_demand)) | np.isin(
            pairs[:, 1], list(gate_demand)
        )
        accepted = []
        weights = []
        lengths = []
        for ix in np.flatnonzero(~blocked):
            u, v = map(int, pairs[ix])
            did = w.vertex_demand.get(u, w.vertex_demand.get(v))
            c = w.candidate_by_vertex.get(v, w.candidate_by_vertex.get(u))
            xy = list(segs[ix].coords)
            if did is not None and u != w.terminal_vertex[did]:
                xy.reverse()
            elif c is not None and u == c.vertex:
                xy.reverse()
            # Private entry edges may be geometrically valid at their demand DN
            # even where a larger trunk could not be placed.
            sizing_demand = (
                did if did is not None else gate_demand.get(u, gate_demand.get(v))
            )
            dn = (
                rules.dn_for_flow(w.model.demands[sizing_demand].flow)
                if sizing_demand is not None
                else w.dn_class
            )
            if exact[ix]:
                pieces, errors = w.geometry.describe(xy, dn or rules.DNS[-1], did, c)
                if errors:
                    continue
            else:
                pieces = [
                    dict(
                        cost=float(segs[ix].length)
                        * rules.cost_new(dn or rules.DNS[-1])
                    )
                ]
            length = float(segs[ix].length)
            accepted.append((u, v))
            lengths.append(length)
            effective = sum(p["cost"] for p in pieces) + rules.LAMBDA * length
            weights.append(
                effective / (rules.cost_new(dn or rules.DNS[-1]) + rules.LAMBDA)
            )
        ok = np.asarray(accepted, dtype=int).reshape(-1, 2)
        self.edges_u, self.edges_v = ok[:, 0], ok[:, 1]
        self.edges_len = np.asarray(lengths)
        self.edges_w = np.asarray(weights)
        self.G = sp.csr_matrix(
            (
                np.r_[weights, weights],
                (np.r_[ok[:, 0], ok[:, 1]], np.r_[ok[:, 1], ok[:, 0]]),
            ),
            shape=(n, n),
        )
        self.n = n
        self.terminals = np.flatnonzero(term)
        w.diagnostics.append(
            f"visibility: {n} vertices, {len(ok)} legal proposal edges, DN class {w.dn_class}, {time.time()-t0:.1f} s"
        )

    def _turn_graph(self):
        if hasattr(self, "turn_graph"):
            return
        # A state is an oriented edge. Keeping incoming direction prevents >90°
        # turns and avoids incorrectly declaring a nearest-boundary entry unreachable.
        u = np.r_[self.edges_u, self.edges_v]
        v = np.r_[self.edges_v, self.edges_u]
        weight = np.r_[self.edges_w, self.edges_w]
        count = len(u)
        outgoing = [[] for _ in range(self.n)]
        for i, a in enumerate(u):
            outgoing[a].append(i)
        rows = []
        cols = []
        values = []
        for i, (a, b) in enumerate(zip(u, v)):
            if self.world.vkind[b] in (V_TERMINAL, V_CANDIDATE):
                continue
            js = np.asarray(outgoing[b], dtype=int)
            if not len(js):
                continue
            heading = self.world.verts[b] - self.world.verts[a]
            next_vectors = self.world.verts[v[js]] - self.world.verts[b]
            valid = (next_vectors @ heading) >= -1e-7
            for j in js[valid]:
                rows.append(i)
                cols.append(int(j))
                values.append(weight[j])
        self.start_states = {}
        for k, terminal in enumerate(self.terminals):
            self.start_states[int(terminal)] = count + k
            for j in outgoing[terminal]:
                rows.append(count + k)
                cols.append(j)
                values.append(weight[j])
        size = count + len(self.terminals)
        self.turn_graph = sp.csr_matrix((values, (rows, cols)), shape=(size, size))
        self.state_ends = np.r_[v, self.terminals]

    def dijkstra_from_terminal(self, v):
        self._turn_graph()
        dist, pred = dijkstra(
            self.turn_graph,
            directed=True,
            indices=self.start_states[v],
            return_predecessors=True,
        )
        vertex_dist = np.full(self.n, np.inf)
        best = np.full(self.n, -1, dtype=int)
        # Stable sorting makes equal-length alternatives deterministic.
        for state in np.argsort(dist, kind="stable"):
            if not np.isfinite(dist[state]):
                break
            target = self.state_ends[state]
            if best[target] < 0:
                best[target] = state
                vertex_dist[target] = dist[state]
        return vertex_dist, Predecessors(best, pred, self.state_ends)

    def all_terminal_paths(self):
        out = {
            did: self.dijkstra_from_terminal(v)
            for did, v in self.world.terminal_vertex.items()
        }
        candidates = [c.vertex for c in self.world.candidates]
        for _ in range(2):
            missing = [
                did
                for did, (dist, pred) in out.items()
                if not np.isfinite(dist[candidates]).any()
                and did not in getattr(self.world, "entry_failures", {})
            ]
            if not missing:
                break
            if self.n > 5000:
                self.world.diagnostics.append(
                    f"search expansion deferred for {len(missing)} demands: graph exceeds 5000 vertices"
                )
                break
            self.world.params.vis_radius *= 2
            self.world.diagnostics.append(
                f"expanding visibility to {self.world.params.vis_radius:g} m for demands {sorted(missing,key=str)}"
            )
            if hasattr(self, "turn_graph"):
                del self.turn_graph
            self.build()
            out = {
                did: self.dijkstra_from_terminal(v)
                for did, v in self.world.terminal_vertex.items()
            }
        return out

    @staticmethod
    def path(pred, src_v, target_v):
        if isinstance(pred, Predecessors):
            return pred.path(target_v)
        path = [target_v]
        cur = target_v
        while cur != src_v:
            cur = int(pred[cur])
            if cur < 0 or cur in path:
                return []
            path.append(cur)
        return path


class Predecessors:
    def __init__(self, best, pred, ends):
        self.best, self.pred, self.ends = best, pred, ends

    def path(self, target):
        state = int(self.best[target])
        out = []
        while state >= 0:
            out.append(int(self.ends[state]))
            state = int(self.pred[state])
        return out if len(out) == len(set(out)) else []
