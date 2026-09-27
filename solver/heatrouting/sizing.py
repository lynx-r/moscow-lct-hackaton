"""Uniform DN on constant-flow chains; same-DN lengths continue per path."""

from . import rules


def assign(tree, demands, backend=None):
    order = tree.order()
    flow = {n: 0.0 for n in order}
    at = {n: d for d, n in tree.term_node.items()}
    for n in reversed(order):
        if n in at:
            flow[n] += demands[at[n]].flow
        if n != tree.root:
            flow[tree.parent[n]] += flow[n]
    compiled = None
    if backend is not None:
        index = {n: i for i, n in enumerate(order)}
        values = backend.size_chains(
            rules.DNS,
            [rules.capacity(d) for d in rules.DNS],
            [rules.max_len(d) for d in rules.DNS],
            [-1] + [index[tree.parent[n]] for n in order[1:]],
            [flow[n] for n in order],
            [0.0] + [tree.length(n) for n in order[1:]],
        )
        compiled = dict(zip(order, values))
    state = {}
    chains = []
    errors = []
    for bottom in reversed(order):
        if bottom == tree.root:
            continue
        kids = tree.children.get(bottom, [])
        if len(kids) == 1 and bottom not in at:
            continue
        ns = []
        n = bottom
        while n != tree.root:
            ns.append(n)
            p = tree.parent[n]
            if p == tree.root or len(tree.children.get(p, [])) != 1 or p in at:
                break
            n = p
        top = tree.parent[ns[-1]]
        length = sum(tree.length(v) for v in ns)
        chosen = None
        used = length
        if compiled is not None:
            chosen = compiled[bottom] if compiled[bottom] > 0 else None
        else:
            below = max((state[c][0] for c in kids), default=0)
            for dn in rules.DNS:
                run = max((state[c][1] for c in kids if state[c][0] == dn), default=0.0)
                if (
                    dn >= below
                    and rules.capacity(dn) >= flow[bottom] - 1e-9
                    and length + run <= rules.max_len(dn) + 1e-7
                ):
                    chosen, used = dn, length + run
                    break
        if chosen is None:
            errors.append(f"chain from {bottom}: no DN satisfies flow/length")
            chosen = rules.DNS[-1]
            used = length
        state[ns[-1]] = (chosen, used)
        chains.append(
            dict(
                child=bottom,
                parent=top,
                nodes=ns,
                xy=[tree.xy[v] for v in ns] + [tree.xy[top]],
                flow=flow[bottom],
                dn=chosen,
                length=length,
                demand=at.get(bottom),
            )
        )
    return chains, flow, errors
