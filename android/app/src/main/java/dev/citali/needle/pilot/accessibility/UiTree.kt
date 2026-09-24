package dev.citali.needle.pilot.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * A redacted, serializable view of the current accessibility tree.
 *
 * Node text and content descriptions are passed through [Redactor] before being
 * attached, so a [UiSnapshot] is safe to render in a log or send to an AI
 * endpoint without leaking passwords, OTPs, payment data, or personal IDs.
 */
data class UiNode(
    val id: Int,
    val className: String,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val scrollable: Boolean,
    val focused: Boolean,
    val bounds: String?,
    val children: List<UiNode> = emptyList(),
) {
    fun flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }
}

data class UiSnapshot(
    val packageName: String?,
    val root: UiNode?,
    val nodeCount: Int,
    val signature: String,
    val capturedAtMillis: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean get() = root == null

    fun editableNodes(): List<UiNode> =
        root?.flatten()?.filter { it.editable && !it.password } ?: emptyList()

    fun clickableNodes(): List<UiNode> =
        root?.flatten()?.filter { it.clickable || it.longClickable } ?: emptyList()

    fun firstByTextOrDescContaining(query: String, clickableOnly: Boolean = false): UiNode? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return null
        return root?.flatten()?.firstOrNull { node ->
            if (clickableOnly && !node.clickable && !node.longClickable) return@firstOrNull false
            node.text?.lowercase(Locale.ROOT)?.contains(q) == true ||
                node.contentDescription?.lowercase(Locale.ROOT)?.contains(q) == true
        }
    }

    /** Compact, redacted tree for the AI prompt. */
    fun toPromptString(): String {
        val sb = StringBuilder()
        sb.append("package: ").append(packageName ?: "unknown").append('\n')
        sb.append("nodes: ").append(nodeCount).append('\n')
        appendNode(sb, root, 0)
        return sb.toString()
    }

    private fun appendNode(sb: StringBuilder, node: UiNode?, depth: Int) {
        if (node == null || depth > SnapshotBuilder.MAX_DEPTH) return
        repeat(depth) { sb.append("  ") }
        sb.append('#').append(node.id).append(' ')
        sb.append(node.className)
        node.resourceId?.takeIf { it.isNotBlank() }?.let { sb.append('[').append(it).append(']') }
        node.text?.takeIf { it.isNotBlank() }?.let { sb.append(' ').append('"').append(truncate(it)).append('"') }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { sb.append(" desc=\"").append(truncate(it)).append('"') }
        val flags = buildString {
            if (node.clickable) append('C')
            if (node.longClickable) append('L')
            if (node.editable) append('E')
            if (node.password) append('P')
            if (node.scrollable) append('S')
            if (node.focused) append('F')
        }
        if (flags.isNotEmpty()) sb.append(" <").append(flags).append('>')
        sb.append('\n')
        node.children.forEach { appendNode(sb, it, depth + 1) }
    }

    private fun truncate(value: String, max: Int = 60): String =
        if (value.length <= max) value else value.take(max - 1) + '…'
}

/** Builds redacted [UiSnapshot]s and provides the shared DFS walk used to resolve nodes. */
object SnapshotBuilder {
    const val MAX_NODES = 160
    const val MAX_DEPTH = 12

    /** Pre-order DFS of the live tree. Index in this list == node id (#index). */
    fun walkDfs(root: AccessibilityNodeInfo?): List<Pair<AccessibilityNodeInfo, Int>> {
        val result = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        fun visit(info: AccessibilityNodeInfo?, depth: Int) {
            if (info == null || result.size >= MAX_NODES || depth > MAX_DEPTH) return
            result.add(info to depth)
            for (i in 0 until info.childCount) {
                visit(info.getChild(i), depth + 1)
            }
        }
        visit(root, 0)
        return result
    }

    fun build(rootInfo: AccessibilityNodeInfo?, redact: Boolean = true): UiSnapshot {
        val walk = walkDfs(rootInfo)
        val nodesById = walk.map { it.first }
        val pkg = rootInfo?.packageName?.toString()

        // Structural signature for stuck detection: package + per-node shape flags.
        val signature = buildString {
            append(pkg ?: "none")
            append('|')
            nodesById.forEach { n ->
                append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                append(if (n.isClickable) 'C' else '-')
                append(if (n.isEditable) 'E' else '-')
                append(if (n.isFocused) 'F' else '-')
                append(if (!n.text.isNullOrEmpty()) 't' else '-')
                append(',')
            }
        }

        var counter = 0
        fun buildTree(depth: Int): UiNode? {
            if (counter >= nodesById.size) return null
            val info = nodesById[counter]
            val id = counter
            counter++
            val children = mutableListOf<UiNode>()
            while (counter < nodesById.size && walk[counter].second > depth) {
                val child = buildTree(walk[counter].second)
                if (child != null) children.add(child) else break
            }
            val hint = info.text?.toString()
            val isPassword = info.isPassword
            val isEditable = info.isEditable
            return UiNode(
                id = id,
                className = info.className?.toString()?.substringAfterLast('.') ?: "View",
                resourceId = info.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
                text = if (redact) Redactor.redact(info.text?.toString(), isEditable, isPassword, hint) else info.text?.toString(),
                contentDescription = if (redact) Redactor.redact(info.contentDescription?.toString(), isEditable, isPassword, hint) else info.contentDescription?.toString(),
                clickable = info.isClickable,
                longClickable = info.isLongClickable,
                editable = isEditable,
                password = isPassword,
                scrollable = info.isScrollable,
                focused = info.isFocused,
                bounds = runCatching {
                    val r = android.graphics.Rect()
                    info.getBoundsInScreen(r)
                    "${r.left},${r.top},${r.right},${r.bottom}"
                }.getOrNull(),
                children = children,
            )
        }

        val root = buildTree(0)
        return UiSnapshot(pkg, root, nodesById.size, signature)
    }
}

/**
 * Conservative redaction. When in doubt about a value being sensitive, mask it.
 * UI chrome (labels, buttons, headings) is preserved so the agent can still act.
 */
object Redactor {
    private val sensitiveHints = listOf(
        "password", "passcode", "pin", "otp", "one-time", "verification code",
        "cvv", "cvc", "card number", "credit", "debit", "mpin", "upi pin",
        "aadhaar", "ssn", "pan card", "recovery code", "security code", "token",
    )

    private val sensitivePatterns = listOf(
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""),
        Regex("""\b(?:\d[ -]?){13,19}\b"""),
        Regex("""\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b"""),
        Regex("""\b\d{6,8}\b"""),
        Regex("""\b\d{3}[-.]?\d{3}[-.]?\d{4}\b"""),
    )

    fun redact(value: String?, editable: Boolean, password: Boolean, hint: String?): String? {
        if (value.isNullOrBlank()) return value
        val trimmed = value.trim()
        if (password) return "\u2022\u2022\u2022"
        if (editable && hint != null && sensitiveHints.any { hint.contains(it, ignoreCase = true) }) {
            return "\u2022\u2022\u2022"
        }
        if (editable && sensitivePatterns.any { it.containsMatchIn(trimmed) }) {
            return "\u2022\u2022\u2022"
        }
        // Non-editable text that is clearly a credential-looking value is masked too.
        if (!editable && sensitivePatterns.any { it.containsMatchIn(trimmed) } &&
            sensitiveHints.any { trimmed.contains(it, ignoreCase = true) }
        ) {
            return "\u2022\u2022\u2022"
        }
        return value.replace('\n', ' ')
    }
}
