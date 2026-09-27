"""Search over connection plans: construct, agglomerate, improve. Keeps a pool of distinct plans."""

from __future__ import annotations

import itertools
import time

import numpy as np

from .network import Evaluation, Group, evaluate, make_group, tie_in_key
from .world import World


class Solver:
    def __init__(self, world: World, dij: dict, log=print, top_k: int = 6, core=None):
        self.w = world
        self.dij = dij
        self.log = log
        self.top_k = top_k
        self.core = (
            core  # optional core_bridge.CoreBridge; Python evaluate stays the reference
        )
        self.cv = np.array([c.vertex for c in world.candidates])
        self.pool: dict = {}
        self.history: list = []
        self.n_eval = 0
        self.group_cache: dict = {}
        self.dropped: list = (
            []
        )  # compatibility argument; missing IDs are derived from coverage

    def group(self, terminals, cand) -> Group:
        """Cache reference group trees by terminal set and attachment candidate."""
        key = (frozenset(terminals), cand.id)
        g = self.group_cache.get(key)
        if g is None:
            terms = sorted(terminals, key=str)
            g = (
                self.core.make_group(terms, cand)
                if self.core is not None
                else make_group(self.w, self.dij, terms, cand)
            )
            if len(self.group_cache) >= 20000:
                self.group_cache.pop(next(iter(self.group_cache)))
            self.group_cache[key] = g
        return g

    def _evaluate(self, groups, dropped=None):
        d = self.dropped if dropped is None else dropped
        return (
            self.core.evaluate(groups, d)
            if self.core is not None
            else evaluate(self.w, groups, d)
        )

    def materialize(self, ev):
        return ev

    # ---- helpers
    forbidden: set = (
        frozenset()
    )  # existing objects (tie_in_key without bucket) excluded from tie-ins
    mask = None  # optional boolean mask over candidates (randomised restarts)

    def allowed(self, cand) -> bool:
        if self.mask is not None and not self.mask[cand.id]:
            return False
        return tie_in_key(cand)[:2] not in self.forbidden

    def rank_candidates(self, terminals, k: int) -> list:
        d = np.zeros(len(self.cv))
        for t in terminals:
            d += self.dij[t][0][self.cv]
        order = np.argsort(d)
        out = []
        for i in order:
            if not np.isfinite(d[i]):
                break
            if self.allowed(self.w.candidates[i]):
                out.append(self.w.candidates[i])
            if len(out) >= k:
                break
        return out

    POOL_MAX = 4000
    MERGE_RADIUS = 800.0  # m of legal path between the nearest demands of two groups for a merge to be tried

    def eval_plan(self, groups: list, dropped=None):
        ev = self._evaluate(groups, dropped)
        self.n_eval += 1
        prev = self.pool.get(ev.signature)
        if prev is None or ev.penalized < prev.penalized:
            self.pool[ev.signature] = ev
            if len(self.pool) > self.POOL_MAX:
                worst = sorted(self.pool, key=lambda k: self.pool[k].penalized)[
                    self.POOL_MAX // 2 :
                ]
                for k in worst:
                    del self.pool[k]
        return ev

    def _dist(self, a, b) -> float:
        return float(self.dij[a][0][self.w.terminal_vertex[b]])

    def _close(self, A, B) -> bool:
        return (
            min(self._dist(a, b) for a in A.terminals for b in B.terminals)
            <= self.MERGE_RADIUS
        )

    def record(self, label: str, ev: Evaluation) -> None:
        self.history.append((label, ev))
        self.log(f"  {label}: {ev.label()}")

    # ---- phases
    def construct(self):
        groups = []
        for did in self.w.model.demands:
            best = None
            for c in self.rank_candidates([did], max(self.top_k, 20)):
                grp = self.group([did], c)
                ev = self._evaluate([grp])
                self.n_eval += 1
                if ev.violations or not grp.routed:
                    continue
                if best is None or ev.penalized < best[0].penalized:
                    best = (ev, grp)
            if best:
                groups.append(best[1])
        ev = self.eval_plan(groups)
        self.record("construct: every demand alone at its best tie-in", ev)
        return groups, ev

    EXHAUSTIVE_MAX_GROUPS = 30

    def agglomerate(self, groups, ev):
        if len(groups) <= self.EXHAUSTIVE_MAX_GROUPS:
            return self._agglomerate_exhaustive(groups, ev)
        return self._agglomerate_lazy(groups, ev)

    def _agglomerate_exhaustive(self, groups, ev):
        """Every pair, every step: best for small instances."""
        while True:
            best = None
            for i in range(len(groups)):
                for j in range(i + 1, len(groups)):
                    A, B = groups[i], groups[j]
                    terms = A.terminals + B.terminals
                    cands = {A.cand.id: A.cand, B.cand.id: B.cand}
                    for c in self.rank_candidates(terms, max(2, self.top_k - 2)):
                        cands[c.id] = c
                    rest = [g for k, g in enumerate(groups) if k not in (i, j)]
                    for c in cands.values():
                        plan = rest + [self.group(terms, c)]
                        e = self.eval_plan(plan)
                        if e.penalized < ev.penalized - 1e-9 and (
                            best is None or e.penalized < best[0].penalized
                        ):
                            best = (e, plan, (A, B, c))
            if best is None:
                break
            ev, groups, (A, B, c) = best
            self.record(
                f"merge {sorted(A.terminals, key=str)} + {sorted(B.terminals, key=str)} at candidate {c.id}",
                ev,
            )
        return groups, ev

    def _agglomerate_lazy(self, groups, ev):
        """Lazy greedy merging: candidate merges are scored once and re-scored only when they touch the last
        merged group or when they come up as the best; only geographically close groups are paired.
        """
        table = {}  # (id(A), id(B)) -> (penalized, plan, merged, A, B, c)

        def score_pair(A, B):
            terms = A.terminals + B.terminals
            cands = {A.cand.id: A.cand, B.cand.id: B.cand}
            for c in self.rank_candidates(terms, max(2, self.top_k - 2)):
                cands[c.id] = c
            rest = [g for g in groups if g is not A and g is not B]
            best = None
            for c in cands.values():
                merged = self.group(terms, c)
                e = self.eval_plan(rest + [merged])
                if best is None or e.penalized < best[0]:
                    best = (
                        e.penalized - ev.penalized,
                        rest + [merged],
                        merged,
                        A,
                        B,
                        c,
                        e,
                    )  # delta to the current base
            return best

        for i in range(len(groups)):
            for j in range(i + 1, len(groups)):
                if self._close(groups[i], groups[j]):
                    table[(id(groups[i]), id(groups[j]))] = score_pair(
                        groups[i], groups[j]
                    )
        while table:
            key = min(table, key=lambda k: table[k][0])
            entry = table[key]
            if entry[0] >= -1e-9:
                break
            fresh = score_pair(
                entry[3], entry[4]
            )  # re-score against the current base plan
            table[key] = fresh
            others = [v[0] for k, v in table.items() if k != key]
            if fresh[0] >= -1e-9:
                del table[key]
                continue
            if others and fresh[0] > min(others) + 1e-9:
                continue  # something else looks better now; loop again
            _, plan, merged, A, B, c, e = fresh
            groups, ev = plan, e
            self.record(
                f"merge {sorted(A.terminals, key=str)} + {sorted(B.terminals, key=str)} at candidate {c.id}",
                ev,
            )
            table = {
                k: v for k, v in table.items() if id(A) not in k and id(B) not in k
            }
            for g in groups:
                if g is not merged and self._close(g, merged):
                    table[(id(g), id(merged))] = score_pair(g, merged)
        return groups, ev

    def improve(self, groups, ev, max_rounds: int = 6):
        for _ in range(max_rounds):
            found = (
                self._retie(groups, ev)
                or self._move(groups, ev)
                or self._split(groups, ev)
            )
            if not found:
                break
            groups, ev, label = found
            self.record(label, ev)
        return groups, ev

    def _retie(self, groups, ev):
        for i, g in enumerate(groups):
            for c in self.rank_candidates(g.terminals, self.top_k + 4):
                if c.id == g.cand.id:
                    continue
                plan = list(groups)
                plan[i] = self.group(g.terminals, c)
                e = self.eval_plan(plan)
                if e.penalized < ev.penalized - 1e-9:
                    return (
                        plan,
                        e,
                        f"re-tie {sorted(g.terminals, key=str)} to candidate {c.id}",
                    )
        return None

    def _move(self, groups, ev):
        for i, g in enumerate(groups):
            if len(g.terminals) < 2:
                continue
            for did in g.terminals:
                for j, h in enumerate(groups):
                    if j == i:
                        continue
                    plan = list(groups)
                    plan[i] = self.group([d for d in g.terminals if d != did], g.cand)
                    plan[j] = self.group(h.terminals + [did], h.cand)
                    e = self.eval_plan(plan)
                    if e.penalized < ev.penalized - 1e-9:
                        return (
                            plan,
                            e,
                            f"move demand {did} into {sorted(h.terminals, key=str)}",
                        )
        return None

    def _split(self, groups, ev):
        for i, g in enumerate(groups):
            if len(g.terminals) < 2:
                continue
            for did in g.terminals:
                for c in self.rank_candidates([did], 3):
                    plan = list(groups)
                    plan[i] = self.group([d for d in g.terminals if d != did], g.cand)
                    plan.append(self.group([did], c))
                    e = self.eval_plan(plan)
                    if e.penalized < ev.penalized - 1e-9:
                        return (
                            plan,
                            e,
                            f"split demand {did} out of {sorted(g.terminals, key=str)}",
                        )
        return None

    def run(self, forbidden: set = frozenset(), restarts: int = 3):
        """Greedy agglomeration is basin-sensitive, so restart 0 is deterministic and later restarts hide a
        random third of the candidates during construction and merging; the best plan wins.
        """
        self.forbidden = set(forbidden)
        rng = np.random.default_rng(self.w.params.seed)
        t0 = time.time()
        best = None
        if forbidden:
            self.log(f"tie-ins forbidden on {sorted(map(str, forbidden))}")
        for r in range(restarts):
            self.dropped = []
            self.mask = None if r == 0 else rng.random(len(self.cv)) > 0.35
            self.log(f"restart {r}: construct")
            groups, ev = self.construct()
            self.log(f"restart {r}: agglomerate")
            groups, ev = self.agglomerate(groups, ev)
            self.mask = None
            self.log(f"restart {r}: improve")
            groups, ev = self.improve(groups, ev)
            self.record(f"restart {r} result", ev)
            if best is None or (len(ev.unrouted), ev.penalized) < (
                len(best[1].unrouted),
                best[1].penalized,
            ):
                best = (groups, ev)
                best_dropped = list(self.dropped)
        self.dropped = best_dropped
        self.record("final", best[1])
        self.log(
            f"  {self.n_eval} evaluations, {len(self.pool)} distinct plans, {len(self.group_cache)} trees built, {time.time() - t0:.1f} s"
        )
        return best

    def variants(self, best, n=3):
        self.log("variants: choosing feasible distinct groupings and attachments")
        candidates = [
            e
            for e in self.pool.values()
            if not e.violations and set(e.unrouted) == set(best.unrouted)
        ]
        if not best.violations:
            candidates.append(best)
        candidates.sort(key=lambda e: e.score)
        out = []
        signatures = set()
        for e in candidates:
            # A 50 m tie-in bucket alone does not justify another variant.
            sig = (frozenset(k[:2] for k in e.signature[0]), e.signature[1])
            if sig not in signatures:
                out.append(e)
                signatures.add(sig)
                if len(out) >= n:
                    break
        return out
