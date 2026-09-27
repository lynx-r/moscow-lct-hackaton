"""Matplotlib views of the world, the visibility graph, the search process and the results."""

from __future__ import annotations

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.collections import LineCollection
from matplotlib.lines import Line2D
from matplotlib.patches import Polygon as MPoly
from PIL import Image

from . import rules

EXIST_COL = {300: "#7FB2E4", 400: "#3F7CC0", 500: "#173F73"}
NEW_COL = {
    50: "#F8D4B8",
    65: "#F2B48A",
    80: "#EC9A66",
    100: "#E5804A",
    125: "#D8642F",
    150: "#C2401C",
    200: "#9E2F13",
    250: "#7A210C",
    300: "#561507",
    400: "#3B0C04",
}
RECON_COL = "#B0008E"
GROUP_COL = plt.cm.tab20.colors


def _draw_geom(ax, geom, **kw):
    if geom.geom_type in ("LineString", "MultiLineString"):
        for line in getattr(geom, "geoms", [geom]):
            ax.plot(*line.xy, color=kw.get("ec", "gray"), lw=kw.get("lw", 1))
        return
    polys = geom.geoms if geom.geom_type == "MultiPolygon" else [geom]
    for p in polys:
        ax.add_patch(MPoly(np.array(p.exterior.coords), closed=True, **kw))
        for r in p.interiors:
            ax.add_patch(
                MPoly(
                    np.array(r.coords),
                    closed=True,
                    fc="white",
                    ec=kw.get("ec", "none"),
                    lw=kw.get("lw", 0.5),
                )
            )


def base_axes(
    world, figsize=(11, 12.5), dpi=100, inflated=True, candidates=True, labels=True
):
    m = world.model
    fig, ax = plt.subplots(figsize=figsize, dpi=dpi)
    x0, y0, x1, y1 = world.roi.bounds
    for i, ob in enumerate(m.obstacles):
        if not ob.geom.intersects(world.roi):
            continue
        if ob.rtype == "water":
            _draw_geom(ax, ob.geom, fc="#BBD8F0", ec="#7FA8CF", lw=0.5)
        elif ob.rule["mode"] == "special":
            _draw_geom(ax, ob.geom, fc="#EFE3B5", ec="#A08A3A", lw=0.6, hatch="//")
        else:
            _draw_geom(ax, ob.geom, fc="#D5DBE1", ec="#8A949E", lw=0.5)
        if inflated:
            _draw_geom(ax, world.inflated[i], fc="none", ec="#B8C2CC", lw=0.4, ls="--")
    for s in m.sections.values():
        xs, ys = s.line.xy
        ax.plot(
            xs,
            ys,
            color=EXIST_COL.get(s.dn, "#3F7CC0"),
            lw=1.4 + (s.dn - 300) / 120,
            solid_capstyle="round",
            zorder=3,
        )
    for c in m.chambers.values():
        ax.plot(
            c.pt.x,
            c.pt.y,
            marker="s",
            ms=6,
            mfc="white",
            mec="#173F73",
            mew=1.3,
            zorder=4,
        )
        if labels:
            ax.annotate(
                str(c.id),
                (c.pt.x, c.pt.y),
                xytext=(5, 4),
                textcoords="offset points",
                fontsize=6,
                color="#173F73",
            )
    ax.plot(m.source_pt.x, m.source_pt.y, marker="*", ms=15, color="#173F73", zorder=5)
    if candidates:
        cx = [c.pt.x for c in world.candidates]
        cy = [c.pt.y for c in world.candidates]
        ax.plot(
            cx, cy, ls="none", marker="|", ms=5, color="#173F73", alpha=0.6, zorder=4
        )
    for d in m.demands.values():
        ax.plot(d.pt.x, d.pt.y, marker="o", ms=6, color="#C2401C", zorder=6)
        if labels:
            ax.annotate(
                f"{d.id} · {d.flow:g}",
                (d.pt.x, d.pt.y),
                xytext=(5, 5),
                textcoords="offset points",
                fontsize=6.5,
                color="#C2401C",
            )
    ax.set_xlim(x0, x1)
    ax.set_ylim(y0, y1)
    ax.set_aspect("equal")
    ax.grid(True, lw=0.3, alpha=0.4)
    ax.tick_params(labelsize=7)
    return fig, ax


def draw_eval(ax, world, ev, title=None, breakdown=True, by_group=False):
    for s in ev.sections:
        xy = np.asarray(s["xy"])
        col = GROUP_COL[s["gi"] % 20] if by_group else NEW_COL.get(s["dn"], "#C2401C")
        ax.plot(
            xy[:, 0],
            xy[:, 1],
            color=col,
            lw=1.3 + s["dn"] / 90,
            solid_capstyle="round",
            zorder=7,
            ls=":" if s["inside"] else "-",
        )
    for r in ev.recon:
        xs, ys = r["geom"].xy
        ax.plot(
            xs, ys, color=RECON_COL, lw=9, alpha=0.35, solid_capstyle="butt", zorder=2
        )
    for t in ev.tie_ins:
        ax.plot(
            *t["xy"], marker="o", ms=11, mfc="none", mec="#C2401C", mew=1.6, zorder=8
        )
    for c in ev.chambers:
        ax.plot(
            *c["xy"], marker="s", ms=7, mfc="#C2401C", mec="white", mew=0.8, zorder=9
        )
    for n in ev.tech_nodes:
        ax.plot(
            *n["xy"], marker="o", ms=4.5, mfc="white", mec="#C2401C", mew=1.2, zorder=9
        )
    for c in ev.chamber_recon:
        ax.plot(
            *c["xy"], marker="s", ms=12, mfc="none", mec=RECON_COL, mew=1.6, zorder=8
        )
    m = world.model
    for did in ev.unrouted:
        d = m.demands[did]
        ax.plot(d.pt.x, d.pt.y, marker="x", ms=12, color="black", mew=2, zorder=10)
    if title:
        ax.set_title(title, fontsize=10, loc="left")
    if breakdown:
        t = ev.totals
        txt = (
            f"score S = {t['score']:.2f}\n"
            f"cost C = {t['cost']/1e6:.1f} M RUB\n"
            f"  new sections   {t['construction']/1e6:7.1f} M\n"
            f"  new chambers   {t['chambers']/1e6:7.1f} M\n"
            f"  tie-ins        {t['tie_in']/1e6:7.1f} M\n"
            f"  penalty        {t['penalty']/1e6:7.1f} M\n"
            f"new length L = {t['new_length']:.0f} m\n"
            f"groups {t['n_groups']}, unrouted {len(ev.unrouted)}, violations {len(ev.violations)}"
        )
        ax.text(
            0.015,
            0.015,
            txt,
            transform=ax.transAxes,
            fontsize=7.5,
            family="monospace",
            va="bottom",
            bbox=dict(boxstyle="round,pad=0.4", fc="white", ec="#B8C2CC", alpha=0.92),
            zorder=20,
        )
    handles = [
        Line2D(
            [],
            [],
            color=NEW_COL.get(dn, "#3B0C04"),
            lw=1.3 + dn / 90,
            label=f"new DN{dn}",
        )
        for dn in sorted({s["dn"] for s in ev.sections})
    ]
    handles += [
        Line2D(
            [],
            [],
            marker="o",
            ms=9,
            mfc="none",
            mec="#C2401C",
            ls="none",
            label="tie-in",
        ),
        Line2D(
            [],
            [],
            marker="s",
            ms=7,
            mfc="#C2401C",
            mec="white",
            ls="none",
            label="new chamber",
        ),
        Line2D(
            [],
            [],
            marker="o",
            ms=4.5,
            mfc="white",
            mec="#C2401C",
            ls="none",
            label="technical node",
        ),
    ]
    ax.legend(handles=handles, loc="upper right", fontsize=7, framealpha=0.92)


def fig_world(world, path):
    fig, ax = base_axes(world)
    ax.set_title(
        "World: obstacles (grey), clearance zones (dashed), existing network by DN, tie-in candidates (ticks), demands (red)",
        fontsize=9,
        loc="left",
    )
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_visibility(world, graph, path, example_demand=None, dij=None):
    fig, ax = base_axes(world, inflated=False, candidates=False, labels=False)
    segs = np.stack([world.verts[graph.edges_u], world.verts[graph.edges_v]], axis=1)
    ax.add_collection(
        LineCollection(segs, colors="#2D5B8A", lw=0.25, alpha=0.18, zorder=2)
    )
    ax.plot(
        world.verts[: world.n_corner, 0],
        world.verts[: world.n_corner, 1],
        ls="none",
        marker=".",
        ms=2,
        color="#2D5B8A",
        alpha=0.6,
        zorder=3,
    )
    if example_demand is not None and dij is not None:
        dist, pred = dij[example_demand]
        src = world.terminal_vertex[example_demand]
        cv = np.array([c.vertex for c in world.candidates])
        k = int(np.argmin(dist[cv]))
        from .routing import Graph

        path_v = Graph.path(pred, src, int(cv[k]))
        pts = world.verts[path_v]
        ax.plot(pts[:, 0], pts[:, 1], color="#C2401C", lw=2.5, zorder=8)
        ax.set_title(
            f"Visibility graph: {graph.n} vertices, {len(graph.edges_u)} edges; example shortest legal path for demand {example_demand} ({dist[cv][k]:.0f} m)",
            fontsize=9,
            loc="left",
        )
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_result(world, ev, path, title):
    fig, ax = base_axes(world, inflated=False, candidates=False)
    draw_eval(ax, world, ev, title=title)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_frame(world, ev, path, title):
    fig, ax = base_axes(
        world,
        figsize=(8.5, 9.6),
        dpi=80,
        inflated=False,
        candidates=False,
        labels=False,
    )
    draw_eval(ax, world, ev, title=title, by_group=True)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def make_gif(frame_paths, out_path, duration_ms=900):
    imgs = [
        Image.open(p).convert("P", palette=Image.ADAPTIVE, colors=128)
        for p in frame_paths
    ]
    imgs[0].save(
        out_path,
        save_all=True,
        append_images=imgs[1:],
        duration=duration_ms,
        loop=0,
        optimize=True,
    )


def fig_variants(world, evs, path):
    n = len(evs)
    fig, axes = plt.subplots(1, n, figsize=(7.2 * n, 9.5), dpi=90)
    if n == 1:
        axes = [axes]
    for k, (ax, ev) in enumerate(zip(axes, evs)):
        plt.sca(ax)
        _base_on(ax, world)
        draw_eval(ax, world, ev, title=f"Option {k + 1}: {ev.label()}", breakdown=True)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def _base_on(ax, world):
    m = world.model
    x0, y0, x1, y1 = world.roi.bounds
    for ob in m.obstacles:
        if not ob.geom.intersects(world.roi):
            continue
        if ob.rtype == "water":
            _draw_geom(ax, ob.geom, fc="#BBD8F0", ec="#7FA8CF", lw=0.5)
        elif ob.rule["mode"] == "special":
            _draw_geom(ax, ob.geom, fc="#EFE3B5", ec="#A08A3A", lw=0.6, hatch="//")
        else:
            _draw_geom(ax, ob.geom, fc="#D5DBE1", ec="#8A949E", lw=0.5)
    for s in m.sections.values():
        xs, ys = s.line.xy
        ax.plot(
            xs,
            ys,
            color=EXIST_COL.get(s.dn, "#3F7CC0"),
            lw=1.4 + (s.dn - 300) / 120,
            zorder=3,
        )
    for c in m.chambers.values():
        ax.plot(
            c.pt.x,
            c.pt.y,
            marker="s",
            ms=5,
            mfc="white",
            mec="#173F73",
            mew=1.2,
            zorder=4,
        )
    ax.plot(m.source_pt.x, m.source_pt.y, marker="*", ms=13, color="#173F73", zorder=5)
    for d in m.demands.values():
        ax.plot(d.pt.x, d.pt.y, marker="o", ms=5, color="#C2401C", zorder=6)
    ax.set_xlim(x0, x1)
    ax.set_ylim(y0, y1)
    ax.set_aspect("equal")
    ax.grid(True, lw=0.3, alpha=0.4)
    ax.tick_params(labelsize=7)
