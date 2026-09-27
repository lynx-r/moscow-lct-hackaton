// Hybrid kernel: tree building and plan evaluation on plain arrays.
//
// Python owns exact geometry and plan accounting; only discrete kernels are duplicated.
// Differential tests compare complete validated Python/C++ outputs.
// Rules and model data are passed in from Python; no number is typed here.
#include <pybind11/pybind11.h>
#include <pybind11/numpy.h>
#include <pybind11/stl.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <map>
#include <string>
#include <unordered_map>
#include <vector>
#include <memory>
#include <queue>
#include <stdexcept>

namespace py = pybind11;

namespace {

struct Rules {
    std::vector<int> dns;
    std::vector<double> cap, maxlen, cnew, crec;
    double tie_in, pen_fixed, pen_flow, w_cost, cost_base, w_len, len_base, viol_w;
    double k_bend = 1.0, bend_tol = 3.0;
    int max_upgrade = 100;
    std::vector<int> tier_dn;      // upper DN of each chamber tier
    std::vector<double> tier_cost;

    int idx_for_flow(double f) const {
        for (size_t i = 0; i < cap.size(); ++i) if (cap[i] >= f - 1e-9) return (int)i;
        return -1;
    }
    int idx_ge(int dn) const {
        for (size_t i = 0; i < dns.size(); ++i) if (dns[i] >= dn) return (int)i;
        return (int)dns.size() - 1;
    }
    double chamber_cost(int dn) const {
        for (size_t i = 0; i < tier_dn.size(); ++i) if (dn <= tier_dn[i]) return tier_cost[i];
        return tier_cost.back();
    }
    double score(double cost, double len) const { return w_cost * cost / cost_base + w_len * len / len_base; }
};

struct Node {
    double x, y;
    int parent = -1;
    std::vector<int> children;
    int term = -1;          // terminal index or -1
    long long vid = -1;     // graph vertex id, or negative split id
};

struct Tree {
    std::vector<Node> nodes;                       // 0 is the root
    std::unordered_map<long long, int> by_vid;     // graph vertex -> node
    std::vector<int> term_node;                    // terminal index -> node or -1
    std::vector<int> unrouted;
};

struct GroupEval {
    int uid;
    double construction = 0, new_length = 0, chambers_cost = 0, flow_root = 0;
    std::vector<int> root_children_dn;             // DN values
    int n_viol = 0, n_tech = 0, n_routed = 0;
    std::vector<double> segs;                      // x0,y0,x1,y1 per edge
    double bx0 = 0, by0 = 0, bx1 = 0, by1 = 0;
    std::vector<int> unrouted;
};

struct Totals {
    double construction = 0, chambers = 0, tie_in = 0, recon = 0, chamber_recon = 0, penalty = 0, cost = 0;
    double new_length = 0, recon_length = 0, length = 0, score = 0, penalized = 0;
    int n_groups = 0, n_viol = 0, n_tech = 0, n_unrouted = 0, n_recon = 0, n_chamber_recon = 0;
};

inline double orient(double ax, double ay, double bx, double by, double cx, double cy) {
    return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
}
constexpr double EPS_ORIENT = 1e-6;   // same tolerance as network.py
inline bool opposite(double d1, double d2) { return std::min(d1, d2) < -EPS_ORIENT && std::max(d1, d2) > EPS_ORIENT; }

class Core {
public:
    Core(const Rules& rules,
         py::array_t<double> verts, py::array_t<double> dist, py::array_t<int64_t> pred,
         std::vector<int> term_vertex, std::vector<double> term_flow,
         std::vector<double> sec_len, std::vector<int> sec_dn, std::vector<double> sec_flow_ex,
         std::vector<int> sec_parent, std::vector<int> sec_up, std::vector<int> sec_down,
         std::vector<std::vector<int>> node_secs,
         std::vector<int> ch_node, std::vector<int> ch_dn, std::vector<int> ch_degree,
         std::vector<int> cand_vertex, std::vector<int> cand_sec, std::vector<double> cand_chain,
         std::vector<int> cand_ch)
        : R(rules), term_vertex(term_vertex), term_flow(term_flow), sec_len(sec_len), sec_dn(sec_dn),
          sec_flow_ex(sec_flow_ex), sec_parent(sec_parent), sec_up(sec_up), sec_down(sec_down),
          node_secs(node_secs), ch_node(ch_node), ch_dn(ch_dn), ch_degree(ch_degree),
          cand_vertex(cand_vertex), cand_sec(cand_sec), cand_chain(cand_chain), cand_ch(cand_ch) {
        auto v = verts.unchecked<2>();
        N = (int)v.shape(0);
        vx.resize(N); vy.resize(N);
        for (int i = 0; i < N; ++i) { vx[i] = v(i, 0); vy[i] = v(i, 1); }
        auto d = dist.unchecked<2>();
        auto p = pred.unchecked<2>();
        T = (int)d.shape(0);
        dist_.assign(T, std::vector<double>(N));
        pred_.assign(T, std::vector<int64_t>(N));
        for (int t = 0; t < T; ++t) for (int i = 0; i < N; ++i) { dist_[t][i] = d(t, i); pred_[t][i] = p(t, i); }
        S = (int)sec_len.size();
        ancestors.assign(S, {});
        for (int s = 0; s < S; ++s) {
            int cur = sec_parent[s];
            std::vector<int> seen;
            while (cur >= 0 && std::find(seen.begin(), seen.end(), cur) == seen.end()) { seen.push_back(cur); cur = sec_parent[cur]; }
            ancestors[s] = seen;
        }
    }

    // ------------------------------------------------------------------ trees
    std::vector<int> path(int t, int src, int target) const {
        std::vector<int> out{target};
        int cur = target;
        while (cur != src) {
            int64_t p = pred_[t][cur];
            if (p < 0) return {};
            cur = (int)p;
            out.push_back(cur);
        }
        return out;
    }

    Tree build_tree(const std::vector<int>& terminals, int cand) const {
        Tree tr;
        tr.term_node.assign(T, -1);
        int root_v = cand_vertex[cand];
        tr.nodes.push_back(Node{vx[root_v], vy[root_v], -1, {}, -1, root_v});
        tr.by_vid[root_v] = 0;
        std::vector<std::pair<int, int>> targets{{root_v, 0}};      // (graph vertex, node)
        std::vector<std::pair<int, int>> edges;                      // (child node, parent node)
        std::vector<int> unattached(terminals);
        long long next_split = -1;
        while (!unattached.empty()) {
            double bestd = std::numeric_limits<double>::infinity();
            int best_t = -1, best_v = -1, best_pos = -1;
            for (size_t ui = 0; ui < unattached.size(); ++ui) {
                int t = unattached[ui];
                double dmin = std::numeric_limits<double>::infinity();
                int vmin = -1;
                for (auto& tv : targets) {
                    double dd = dist_[t][tv.first];
                    if (dd < dmin) { dmin = dd; vmin = tv.first; }
                }
                if (dmin < bestd) { bestd = dmin; best_t = t; best_v = vmin; best_pos = (int)ui; }
            }
            if (best_t < 0 || !std::isfinite(bestd)) {
                tr.unrouted.insert(tr.unrouted.end(), unattached.begin(), unattached.end());
                break;
            }
            unattached.erase(unattached.begin() + best_pos);
            int src = term_vertex[best_t];
            std::vector<int> pth = path(best_t, src, best_v);
            if (pth.empty()) { tr.unrouted.push_back(best_t); continue; }
            attach(tr, edges, targets, pth, best_t, src, next_split);
        }
        return tr;
    }

    void attach(Tree& tr, std::vector<std::pair<int, int>>& edges, std::vector<std::pair<int, int>>& targets,
                const std::vector<int>& pth, int t, int src, long long& next_split) const {
        int n = (int)pth.size();
        int last_j = 0;
        for (int j = 1; j < n; ++j) if (tr.by_vid.count(pth[j])) last_j = j;
        bool split = false;
        int split_i = -1, split_k = -1;
        double sx = 0, sy = 0;
        if (!edges.empty()) {
            for (int i = last_j; i < n - 1; ++i) {
                double px = vx[pth[i]], py_ = vy[pth[i]], qx = vx[pth[i + 1]], qy = vy[pth[i + 1]];
                double best_t = -1.0; int best_k = -1; double bx = 0, by = 0;
                for (size_t k = 0; k < edges.size(); ++k) {
                    const Node& a = tr.nodes[edges[k].first];
                    const Node& b = tr.nodes[edges[k].second];
                    double d1 = orient(a.x, a.y, b.x, b.y, px, py_), d2 = orient(a.x, a.y, b.x, b.y, qx, qy);
                    double d3 = orient(px, py_, qx, qy, a.x, a.y), d4 = orient(px, py_, qx, qy, b.x, b.y);
                    if (opposite(d1, d2) && opposite(d3, d4)) {
                        double rx = qx - px, ry = qy - py_, ssx = b.x - a.x, ssy = b.y - a.y;
                        double denom = rx * ssy - ry * ssx;
                        if (denom == 0) denom = 1.0;
                        double tt = ((a.x - px) * ssy - (a.y - py_) * ssx) / denom;
                        if (tt > best_t) { best_t = tt; best_k = (int)k; bx = px + tt * rx; by = py_ + tt * ry; }
                    }
                }
                if (best_k >= 0) { split = true; split_i = i; split_k = best_k; sx = bx; sy = by; }
            }
        }
        int attach_node;
        int rest_from;
        if (!split) {
            attach_node = tr.by_vid[pth[last_j]];
            rest_from = last_j + 1;
        } else {
            int c = edges[split_k].first, p = edges[split_k].second;
            int sid = (int)tr.nodes.size();
            tr.nodes.push_back(Node{sx, sy, p, {c}, -1, next_split--});
            tr.nodes[c].parent = sid;
            auto& pc = tr.nodes[p].children;
            pc.erase(std::find(pc.begin(), pc.end(), c));
            pc.push_back(sid);
            edges[split_k] = {c, sid};
            edges.push_back({sid, p});
            attach_node = sid;
            rest_from = split_i + 1;
        }
        int prev = attach_node;
        for (int k = rest_from; k < n; ++k) {
            int q = pth[k];
            int nid = (int)tr.nodes.size();
            tr.nodes.push_back(Node{vx[q], vy[q], prev, {}, -1, q});
            tr.by_vid[q] = nid;
            tr.nodes[prev].children.push_back(nid);
            edges.push_back({nid, prev});
            if (q != src) targets.push_back({q, nid});
            prev = nid;
        }
        tr.nodes[prev].term = t;
        tr.term_node[t] = prev;
    }

    // ------------------------------------------------------------------ group evaluation (cached)
    py::tuple tree_data(const std::vector<int>& terminals, int cand) const {
        auto tr = build_tree(terminals, cand);
        std::vector<std::array<double,2>> xy;
        std::vector<int> parent, term;
        std::vector<long long> vertex;
        for (const auto& n : tr.nodes) {
            xy.push_back({n.x,n.y}); parent.push_back(n.parent);
            term.push_back(n.term); vertex.push_back(n.vid);
        }
        return py::make_tuple(xy,parent,term,vertex);
    }

    void set_graph(std::vector<int> eu, std::vector<int> ev, std::vector<double> elen) {
        adj.assign(N, {});
        for (size_t i = 0; i < eu.size(); ++i) {
            adj[eu[i]].push_back({ev[i], elen[i]});
            adj[ev[i]].push_back({eu[i], elen[i]});
        }
        is_terminal.assign(N, 0);
        for (int v : term_vertex) is_terminal[v] = 1;
        lambda_ = R.w_len / R.len_base / (R.w_cost / R.cost_base);
    }

    // ------------------------------------------------------------------ extended Dreyfus-Wagner
    // T(v,S): min cost (pipe at the DN of the subset flow, plus lambda, plus branch chambers) of a tree
    // spanning terminal subset S and hanging from vertex v. The maximum-length rule is not applied, so
    // T is a lower bound on the evaluator's cost; the extracted tree evaluated exactly gives the upper bound.
    struct DP {
        int k = 0, n = 0;
        std::vector<int> ts;                       // terminal indices
        std::vector<double> T, T1, Tk, M2, M3;     // [mask*n + v]; Tk = min(M2, M3)
        std::vector<int32_t> bp_pred, bp_sub, bp_sub2, bp_sub3;   // extend predecessor / merge submasks
        std::vector<uint8_t> bp_type;              // 0 none, 1 base, 2 extend, 3 merge
        std::vector<double> flow_of_mask, cost_of_mask, chamber_of_mask;
        std::vector<int> dn_of_mask;
        double at(const std::vector<double>& tab, int mask, int v) const { return tab[(size_t)mask * n + v]; }
    };

    std::shared_ptr<DP> steiner_dp(const std::vector<int>& terminals) {
        auto dp = std::make_shared<DP>();
        int k = (int)terminals.size();
        if (k < 1 || k > 14) throw std::runtime_error("steiner_dp: one to 14 terminals per subset");
        dp->k = k; dp->n = N; dp->ts = terminals;
        int M = 1 << k;
        const double INF = std::numeric_limits<double>::infinity();
        dp->T.assign((size_t)M * N, INF); dp->T1.assign((size_t)M * N, INF); dp->Tk.assign((size_t)M * N, INF);
        dp->M2.assign((size_t)M * N, INF); dp->M3.assign((size_t)M * N, INF);
        dp->bp_pred.assign((size_t)M * N, -1); dp->bp_sub.assign((size_t)M * N, 0); dp->bp_type.assign((size_t)M * N, 0);
        dp->bp_sub2.assign((size_t)M * N, 0); dp->bp_sub3.assign((size_t)M * N, 0);
        dp->flow_of_mask.assign(M, 0.0); dp->cost_of_mask.assign(M, 0.0); dp->chamber_of_mask.assign(M, 0.0); dp->dn_of_mask.assign(M, 0);
        for (int mask = 1; mask < M; ++mask) {
            double f = 0; for (int i = 0; i < k; ++i) if (mask & (1 << i)) f += term_flow[terminals[i]];
            int di = R.idx_for_flow(f); if (di < 0) di = (int)R.dns.size() - 1;
            dp->flow_of_mask[mask] = f; dp->dn_of_mask[mask] = R.dns[di];
            dp->cost_of_mask[mask] = R.cnew[di] + lambda_;
            dp->chamber_of_mask[mask] = R.chamber_cost(R.dns[di]);
        }
        std::vector<double> dist(N), cand(N);
        std::vector<int> pred(N);
        std::vector<char> done(N);
        using QE = std::pair<double, int>;
        for (int mask = 1; mask < M; ++mask) {
            size_t base = (size_t)mask * N;
            double c = dp->cost_of_mask[mask], ch = dp->chamber_of_mask[mask];
            bool single = (mask & (mask - 1)) == 0;
            int low = mask & -mask;
            for (int v = 0; v < N; ++v) {
                cand[v] = INF; pred[v] = -1;
                if (is_terminal[v]) continue;
                // merges at v with at most three branches (appendix 3): M2 = T1(sub) + T1(rest), M3 = T1(sub) + M2(rest)
                if (!single) {
                    double best2 = INF, best3 = INF; int bsub2 = 0, bsub3 = 0;
                    for (int sub = (mask - 1) & mask; sub > 0; sub = (sub - 1) & mask) {
                        if (!(sub & low)) continue;
                        int rest = mask ^ sub;
                        double a = dp->T1[(size_t)sub * N + v];
                        if (a == INF) continue;
                        double b1 = dp->T1[(size_t)rest * N + v];
                        if (b1 < INF && a + b1 < best2) { best2 = a + b1; bsub2 = sub; }
                        double b2 = dp->M2[(size_t)rest * N + v];
                        if (b2 < INF && a + b2 < best3) { best3 = a + b2; bsub3 = sub; }
                    }
                    dp->M2[base + v] = best2; dp->bp_sub2[base + v] = bsub2;
                    dp->M3[base + v] = best3; dp->bp_sub3[base + v] = bsub3;
                    double best = std::min(best2, best3);
                    dp->Tk[base + v] = best;
                    dp->bp_sub[base + v] = (best2 <= best3) ? 2 : 3;
                    if (best < INF) { cand[v] = best + ch; }
                }
            }
            if (single) {
                int i = __builtin_ctz(mask);
                cand[term_vertex[terminals[i]]] = 0.0;
            }
            // multi-source Dijkstra from cand over non-terminal vertices
            std::priority_queue<QE, std::vector<QE>, std::greater<QE>> pq;
            for (int v = 0; v < N; ++v) { dist[v] = cand[v]; done[v] = 0; if (cand[v] < INF) pq.push({cand[v], v}); }
            while (!pq.empty()) {
                auto [d, v] = pq.top(); pq.pop();
                if (done[v] || d > dist[v]) continue;
                done[v] = 1;
                for (auto& e : adj[v]) {
                    int u = e.first;
                    if (is_terminal[u]) continue;            // terminals are leaves only
                    double nd = d + e.second * c;
                    if (nd < dist[u]) { dist[u] = nd; pred[u] = v; pq.push({nd, u}); }
                }
            }
            for (int v = 0; v < N; ++v) {
                dp->T[base + v] = dist[v];
                if (dist[v] == INF) continue;
                if (pred[v] >= 0) { dp->bp_type[base + v] = 2; dp->bp_pred[base + v] = pred[v]; }
                else if (single && is_terminal[v]) dp->bp_type[base + v] = 1;
                else dp->bp_type[base + v] = 3;
            }
            for (int v = 0; v < N; ++v) {
                if (is_terminal[v]) continue;
                double best = INF;
                for (auto& e : adj[v]) { double t = dp->T[base + e.first]; if (t < INF && t + e.second * c < best) best = t + e.second * c; }
                dp->T1[base + v] = best;
            }
        }
        return dp;
    }

    // cost of hanging the full subset from vertex v with no chamber charged at v (the tie-in rule prices it),
    // with at most `max_branches` root children (2 for a mid-section tie-in, 4 minus the existing degree at a chamber)
    static double dp_root_cost(const DP& dp, int v, int max_branches) {
        int full = (1 << dp.k) - 1;
        double best = dp.at(dp.T1, full, v);
        if (max_branches >= 2) best = std::min(best, dp.at(dp.M2, full, v));
        if (max_branches >= 3) best = std::min(best, dp.at(dp.M3, full, v));
        return best;
    }

    // extract the tree hanging from root vertex v as (child, parent) graph-vertex pairs
    std::vector<std::pair<int, int>> dp_tree(const DP& dp, int v, int max_branches) const {
        std::vector<std::pair<int, int>> edges;
        int full = (1 << dp.k) - 1;
        double t1 = dp.at(dp.T1, full, v), m2 = max_branches >= 2 ? dp.at(dp.M2, full, v) : std::numeric_limits<double>::infinity();
        double m3 = max_branches >= 3 ? dp.at(dp.M3, full, v) : std::numeric_limits<double>::infinity();
        if (t1 <= m2 && t1 <= m3) extract_T1(dp, full, v, edges);
        else if (m2 <= m3) extract_M2(dp, full, v, edges);
        else extract_M3(dp, full, v, edges);
        return edges;
    }

private:
    void extract_T(const DP& dp, int mask, int v, std::vector<std::pair<int, int>>& edges) const {
        size_t i = (size_t)mask * dp.n + v;
        uint8_t ty = dp.bp_type[i];
        if (ty == 1) return;
        if (ty == 2) { int u = dp.bp_pred[i]; edges.push_back({u, v}); extract_T(dp, mask, u, edges); return; }
        if (ty == 3) extract_Tk(dp, mask, v, edges);
    }
    void extract_T1(const DP& dp, int mask, int v, std::vector<std::pair<int, int>>& edges) const {
        double c = dp.cost_of_mask[mask], best = std::numeric_limits<double>::infinity(); int bu = -1;
        for (auto& e : adj[v]) { double t = dp.at(dp.T, mask, e.first); if (t + e.second * c < best) { best = t + e.second * c; bu = e.first; } }
        if (bu < 0) return;
        edges.push_back({bu, v});
        extract_T(dp, mask, bu, edges);
    }
    void extract_Tk(const DP& dp, int mask, int v, std::vector<std::pair<int, int>>& edges) const {
        if (dp.bp_sub[(size_t)mask * dp.n + v] == 2) extract_M2(dp, mask, v, edges); else extract_M3(dp, mask, v, edges);
    }
    void extract_M2(const DP& dp, int mask, int v, std::vector<std::pair<int, int>>& edges) const {
        int sub = dp.bp_sub2[(size_t)mask * dp.n + v];
        if (sub == 0) return;
        extract_T1(dp, sub, v, edges);
        extract_T1(dp, mask ^ sub, v, edges);
    }
    void extract_M3(const DP& dp, int mask, int v, std::vector<std::pair<int, int>>& edges) const {
        int sub = dp.bp_sub3[(size_t)mask * dp.n + v];
        if (sub == 0) return;
        extract_T1(dp, sub, v, edges);
        extract_M2(dp, mask ^ sub, v, edges);
    }

    std::vector<std::vector<std::pair<int, double>>> adj;
    std::vector<char> is_terminal;
    double lambda_ = 0.0;
public:
    std::vector<double> dp_root_costs(const DP& dp, const std::vector<int>& vertices, const std::vector<int>& max_branches) const {
        std::vector<double> out;
        for (size_t i = 0; i < vertices.size(); ++i) out.push_back(dp_root_cost(dp, vertices[i], max_branches[i]));
        return out;
    }
    int dp_dn_full(const DP& dp) const { return dp.dn_of_mask[(1 << dp.k) - 1]; }

private:
    Rules R;
    int N = 0, T = 0, S = 0;
    std::vector<double> vx, vy;
    std::vector<std::vector<double>> dist_;
    std::vector<std::vector<int64_t>> pred_;
    std::vector<int> term_vertex; std::vector<double> term_flow;
    std::vector<double> sec_len; std::vector<int> sec_dn; std::vector<double> sec_flow_ex;
    std::vector<int> sec_parent, sec_up, sec_down;
    std::vector<std::vector<int>> node_secs, ancestors;
    std::vector<int> ch_node, ch_dn, ch_degree;
    std::vector<int> cand_vertex, cand_sec; std::vector<double> cand_chain; std::vector<int> cand_ch;
    std::unordered_map<std::string, GroupEval> gcache;
    std::unordered_map<uint64_t, int> xcache;
    int next_uid = 0;
};

// Bottom-up sizing on maximal constant-flow chains. Parent indices are BFS ordered.
std::vector<int> size_chains(const std::vector<int>& dns, const std::vector<double>& cap,
                            const std::vector<double>& maxlen, const std::vector<int>& parent,
                            const std::vector<double>& flow, const std::vector<double>& lengths) {
    size_t n=parent.size();
    std::vector<std::vector<int>> kids(n);
    for (size_t i=1;i<n;++i) kids[parent[i]].push_back((int)i);
    std::vector<int> dn(n,-1); std::vector<double> run(n,0.);
    for (int bottom=(int)n-1;bottom>0;--bottom) {
        if (kids[bottom].size()==1) continue;
        std::vector<int> chain; int v=bottom; double length=0.;
        while (v>0) {
            chain.push_back(v); length+=lengths[v]; int up=parent[v];
            if (up==0 || kids[up].size()!=1) break;
            v=up;
        }
        int below=0; for (int c:kids[bottom]) below=std::max(below,dn[c]);
        int chosen=-1; double used=length;
        for (size_t d=0;d<dns.size();++d) {
            double continuing=0.;
            for (int c:kids[bottom]) if(dn[c]==dns[d]) continuing=std::max(continuing,run[c]);
            if (dns[d]>=below && cap[d]>=flow[bottom]-1e-9 && length+continuing<=maxlen[d]+1e-7) {
                chosen=dns[d];used=length+continuing;break;
            }
        }
        for(int c:chain) dn[c]=chosen;
        run[chain.back()]=used;
    }
    return dn;
}

}  // namespace

PYBIND11_MODULE(_core, m) {
    m.doc() = "Current-spec tree construction, chain sizing and relaxed subset DP";
    m.attr("SPEC_VERSION") = "2026-09-27";
    m.def("size_chains", &size_chains);
    py::class_<Rules>(m, "Rules")
        .def(py::init<>())
        .def_readwrite("dns", &Rules::dns).def_readwrite("cap", &Rules::cap).def_readwrite("maxlen", &Rules::maxlen)
        .def_readwrite("cnew", &Rules::cnew).def_readwrite("crec", &Rules::crec)
        .def_readwrite("tie_in", &Rules::tie_in).def_readwrite("pen_fixed", &Rules::pen_fixed).def_readwrite("pen_flow", &Rules::pen_flow)
        .def_readwrite("w_cost", &Rules::w_cost).def_readwrite("cost_base", &Rules::cost_base)
        .def_readwrite("w_len", &Rules::w_len).def_readwrite("len_base", &Rules::len_base).def_readwrite("viol_w", &Rules::viol_w)
        .def_readwrite("tier_dn", &Rules::tier_dn).def_readwrite("tier_cost", &Rules::tier_cost)
        .def_readwrite("k_bend", &Rules::k_bend).def_readwrite("bend_tol", &Rules::bend_tol).def_readwrite("max_upgrade", &Rules::max_upgrade);
    py::class_<Totals>(m, "Totals")
        .def_readonly("construction", &Totals::construction).def_readonly("chambers", &Totals::chambers)
        .def_readonly("tie_in", &Totals::tie_in).def_readonly("recon", &Totals::recon)
        .def_readonly("chamber_recon", &Totals::chamber_recon).def_readonly("penalty", &Totals::penalty)
        .def_readonly("cost", &Totals::cost).def_readonly("new_length", &Totals::new_length)
        .def_readonly("recon_length", &Totals::recon_length).def_readonly("length", &Totals::length)
        .def_readonly("score", &Totals::score).def_readonly("penalized", &Totals::penalized)
        .def_readonly("n_groups", &Totals::n_groups).def_readonly("n_viol", &Totals::n_viol)
        .def_readonly("n_tech", &Totals::n_tech).def_readonly("n_unrouted", &Totals::n_unrouted)
        .def_readonly("n_recon", &Totals::n_recon).def_readonly("n_chamber_recon", &Totals::n_chamber_recon);
    py::class_<Core>(m, "Core")
        .def(py::init<const Rules&, py::array_t<double>, py::array_t<double>, py::array_t<int64_t>,
                      std::vector<int>, std::vector<double>, std::vector<double>, std::vector<int>, std::vector<double>,
                      std::vector<int>, std::vector<int>, std::vector<int>, std::vector<std::vector<int>>,
                      std::vector<int>, std::vector<int>, std::vector<int>,
                      std::vector<int>, std::vector<int>, std::vector<double>, std::vector<int>>())
        .def("tree_data", &Core::tree_data)
        .def("set_graph", &Core::set_graph)
        .def("steiner_dp", &Core::steiner_dp, py::arg("terminals"))
        .def("dp_root_costs", &Core::dp_root_costs)
        .def("dp_tree", &Core::dp_tree)
        .def("dp_dn_full", &Core::dp_dn_full);
    py::class_<Core::DP, std::shared_ptr<Core::DP>>(m, "DP")
        .def_readonly("k", &Core::DP::k);
}
