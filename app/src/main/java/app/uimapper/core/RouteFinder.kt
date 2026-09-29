package app.uimapper.core

import app.uimapper.model.ActionType
import app.uimapper.model.NavEdge
import app.uimapper.model.START_NODE
import app.uimapper.model.Session
import app.uimapper.model.isExternalNode

/** Graph queries over a session's navigation edges. */
object RouteFinder {

    /** Screen reached by the app launch (target of the first START edge), else the first screen. */
    fun startScreenId(session: Session): String? =
        session.edges.firstOrNull { it.from == START_NODE }?.to ?: session.screens.firstOrNull()?.id

    /**
     * Edges a route may use. BACK edges are skipped unless [includeBack], so a route is something the user
     * can replay forwards. UNKNOWN edges are automatic transitions (splash -> home, a redirect after a
     * loading screen) and stay usable: without them every screen behind a splash would be unreachable.
     */
    private fun routeEdges(session: Session, includeBack: Boolean): List<NavEdge> =
        session.edges.filter {
            it.from != it.to && !isExternalNode(it.from) && (includeBack || it.action != ActionType.BACK)
        }

    /** Automatic / unrecognised transitions cost more, so a route of real taps wins when one exists. */
    private fun cost(e: NavEdge): Int = if (e.action == ActionType.UNKNOWN) 2 else 1

    /**
     * Cheapest route [from] -> [to] over [routeEdges] (Dijkstra: a tap costs 1, an automatic transition 2).
     * Returns empty list if from == to, null if unreachable.
     */
    fun shortestPath(session: Session, from: String, to: String, includeBack: Boolean = false): List<NavEdge>? {
        if (from == to) return emptyList()
        return shortestTree(session, from, includeBack)[to]
    }

    /** Cheapest route from [from] to every reachable node (from itself maps to an empty route). */
    private fun shortestTree(session: Session, from: String, includeBack: Boolean): Map<String, List<NavEdge>> {
        val byFrom = routeEdges(session, includeBack).groupBy { it.from }
        val dist = HashMap<String, Int>()
        val steps = HashMap<String, Int>()
        val prev = HashMap<String, NavEdge>()
        val done = HashSet<String>()
        dist[from] = 0
        steps[from] = 0
        // Graphs are small (tens to a few hundred nodes): a linear scan for the minimum is enough.
        while (true) {
            var cur: String? = null
            for ((node, d) in dist) {
                if (node in done) continue
                val c = cur
                if (c == null || d < dist.getValue(c) || (d == dist.getValue(c) && steps.getValue(node) < steps.getValue(c))) {
                    cur = node
                }
            }
            val u = cur ?: break
            done += u
            val du = dist.getValue(u)
            val su = steps.getValue(u)
            for (e in byFrom[u].orEmpty()) {
                if (e.to in done) continue
                val nd = du + cost(e)
                val old = dist[e.to]
                if (old == null || nd < old || (nd == old && su + 1 < steps.getValue(e.to))) {
                    dist[e.to] = nd
                    steps[e.to] = su + 1
                    prev[e.to] = e
                }
            }
        }
        val out = HashMap<String, List<NavEdge>>(done.size * 2)
        for (node in done) {
            val path = ArrayList<NavEdge>()
            var n = node
            while (n != from) {
                val edge = prev[n] ?: break
                path += edge
                n = edge.from
            }
            out[node] = path.asReversed()
        }
        return out
    }

    /** Cheapest route from the start screen to every reachable screen. */
    fun routesFromStart(session: Session): Map<String, List<NavEdge>> {
        val start = startScreenId(session) ?: return emptyMap()
        val tree = shortestTree(session, start, includeBack = false)
        val out = LinkedHashMap<String, List<NavEdge>>()
        for (s in session.screens) {
            tree[s.id]?.let { out[s.id] = it }
        }
        return out
    }

    /**
     * BFS depth of each node from the start screen, over the same edges as [routesFromStart], so a node
     * has a depth exactly when a route to it exists (unreachable nodes are absent).
     */
    fun depths(session: Session): Map<String, Int> {
        val start = startScreenId(session) ?: return emptyMap()
        val byFrom = routeEdges(session, includeBack = false).groupBy { it.from }
        val depth = LinkedHashMap<String, Int>()
        depth[start] = 0
        val queue = ArrayDeque<String>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = depth.getValue(cur)
            for (e in byFrom[cur].orEmpty()) {
                if (e.to !in depth) {
                    depth[e.to] = d + 1
                    queue.addLast(e.to)
                }
            }
        }
        return depth
    }

    /** Indonesian verb for an action, used in route descriptions. */
    fun actionVerb(action: ActionType): String = when (action) {
        ActionType.CLICK -> "Ketuk"
        ActionType.LONG_CLICK -> "Tekan lama"
        ActionType.SCROLL -> "Gulir"
        ActionType.TEXT_INPUT -> "Isi teks"
        ActionType.BACK -> "Kembali"
        ActionType.LAUNCH -> "Buka aplikasi"
        ActionType.EXTERNAL -> "Pindah ke aplikasi lain"
        ActionType.UNKNOWN -> "Transisi"
    }

    fun nodeLabel(session: Session, id: String): String = when {
        id == START_NODE -> "Mulai"
        isExternalNode(id) -> "Aplikasi lain (${id.removePrefix("ext:")})"
        else -> session.screen(id)?.let { "${it.id} · ${it.label}" } ?: id
    }

    /** "Ketuk \"Masuk\"" / "Kembali" / "Buka aplikasi". */
    fun stepText(edge: NavEdge): String {
        val verb = actionVerb(edge.action)
        val el = edge.element?.display()
        return if (el.isNullOrBlank()) verb else "$verb \"$el\""
    }

    /** "S1 · Beranda —[Ketuk "Masuk"]→ S2 · Login". */
    fun describe(session: Session, edge: NavEdge): String =
        "${nodeLabel(session, edge.from)} —[${stepText(edge)}]→ ${nodeLabel(session, edge.to)}"
}
