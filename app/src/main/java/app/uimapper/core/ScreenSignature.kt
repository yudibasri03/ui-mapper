package app.uimapper.core

import app.uimapper.model.UiNode
import java.security.MessageDigest

/**
 * Structural fingerprint of a screen. Two captures of the same screen with different data (list rows,
 * counters, user names) should produce nearly identical feature sets; different screens should not.
 *
 * Feature kinds:
 *  V:<Class>|<resId>             every visible view (set semantics collapses repeated list rows)
 *  C:<Class>|<resId>|<label>     actionable element; label omitted inside scrollable containers
 *  T:<text>                      titles (pane titles, headings, *title* ids, toolbar texts)
 *  S:<Class>|<resId>             scrollable container
 *  E:<resId>|<hint>              editable field
 */
object ScreenSignature {

    private const val MAX_DEPTH = 45

    fun features(root: UiNode): List<String> {
        val out = HashSet<String>()
        walk(root, parentCls = "", inScrollable = false, out = out)
        return out.sorted()
    }

    private fun walk(n: UiNode, parentCls: String, inScrollable: Boolean, out: MutableSet<String>) {
        if (n.depth > MAX_DEPTH) return
        if (!n.visible || n.bounds.isEmpty()) {
            // Invisible containers can still hold visible children in rare cases (e.g. zero-size wrappers).
            if (n.children.isEmpty()) return
        } else {
            val cls = n.simpleCls
            val rid = n.resIdEntry ?: ""
            out += "V:$cls|$rid"
            if (n.isActionable) {
                val lbl = if (inScrollable) "" else UiTree.labelOf(n)?.takeIf(::isStaticLabel) ?: ""
                out += "C:$cls|$rid|$lbl"
            }
            if (n.scrollable) out += "S:$cls|$rid"
            if (n.editable) out += "E:$rid|${n.hint?.take(40) ?: ""}"
            n.paneTitle?.takeIf { it.isNotBlank() }?.let { out += "T:${it.trim().take(50)}" }
            if (!inScrollable && isTitleNode(n, parentCls)) {
                n.text?.trim()?.takeIf { it.isNotEmpty() && it.length <= 50 }?.let { out += "T:$it" }
            }
        }
        val childInScrollable = inScrollable || n.scrollable
        for (c in n.children) walk(c, n.simpleCls, childInScrollable, out)
    }

    private fun isTitleNode(n: UiNode, parentCls: String): Boolean {
        if (n.heading) return true
        val rid = n.resIdEntry?.lowercase() ?: ""
        if ("title" in rid || "toolbar" in rid || "header" in rid) return true
        val p = parentCls.lowercase()
        return "toolbar" in p || "actionbar" in p
    }

    /** Short static-looking labels only; dynamic strings (prices, counters, dates) are dropped. */
    private fun isStaticLabel(s: String): Boolean {
        if (s.isEmpty() || s.length > 40) return false
        val digits = s.count { it.isDigit() }
        return digits.toFloat() / s.length < 0.3f
    }

    fun signature(pkg: String, activity: String?, features: List<String>): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("$pkg|${activity ?: ""}|".toByteArray())
        for (f in features) {
            md.update(f.toByteArray())
            md.update('\n'.code.toByte())
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Jaccard similarity of two feature sets, 0..1. */
    fun similarity(a: Collection<String>, b: Collection<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val sa = if (a is Set<String>) a else a.toHashSet()
        val sb = if (b is Set<String>) b else b.toHashSet()
        var inter = 0
        for (x in sa) if (x in sb) inter++
        val union = sa.size + sb.size - inter
        return if (union == 0) 1f else inter.toFloat() / union.toFloat()
    }

    /** Best-effort screen title: pane title, heading, *title* id / toolbar text, else a short top text. */
    fun deriveTitle(root: UiNode, screenH: Int): String? {
        val all = UiTree.flatten(root).filter { it.visible && !it.bounds.isEmpty() }
        all.firstOrNull { !it.paneTitle.isNullOrBlank() }?.let { return it.paneTitle!!.trim().take(50) }
        all.firstOrNull { it.heading && !it.text.isNullOrBlank() }?.let { return it.text!!.trim().take(50) }
        all.firstOrNull {
            val rid = it.resIdEntry?.lowercase() ?: ""
            ("title" in rid || "toolbar" in rid) && !it.text.isNullOrBlank() && it.text.length <= 50
        }?.let { return it.text!!.trim() }
        val topLimit = if (screenH > 0) screenH / 5 else Int.MAX_VALUE
        return all.firstOrNull {
            !it.clickable && !it.editable && it.bounds.t < topLimit &&
                !it.text.isNullOrBlank() && it.text.trim().length in 2..40
        }?.text?.trim()
    }

    fun defaultLabel(title: String?, activity: String?, number: Int): String {
        val base = title?.takeIf { it.isNotBlank() }
            ?: activity?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
            ?: "Layar"
        return "$base #$number"
    }
}
