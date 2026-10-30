package tv.stream.app.ui

/**
 * Panes built on first use, such as a long series' seasons: only the season on screen becomes items. Keys are fixed and
 * in order; each pane is built once, from the immutable inputs its builder captured, never from a worker's mutable
 * state. [signature] stands for every pane's content in render diffs and [rowKeys] for focus lookups, so neither
 * needs to build anything.
 */
class LazyPanes(
    private val builders: LinkedHashMap<String, () -> List<Item>>,
    val signature: List<Any?>,
    val rowKeys: Set<String>,
) : AbstractMap<String, List<Item>>() {
    private val built = HashMap<String, List<Item>>()

    /** How many panes have been built, for tests and measurements. */
    val builtCount: Int get() = synchronized(built) { built.size }

    override fun get(key: String): List<Item>? {
        val build = builders[key] ?: return null
        synchronized(built) { return built.getOrPut(key) { build() } }
    }

    override fun containsKey(key: String): Boolean = builders.containsKey(key)
    override val keys: Set<String> get() = builders.keys
    override val size: Int get() = builders.size

    override val entries: Set<Map.Entry<String, List<Item>>>
        get() = builders.keys.mapTo(LinkedHashSet()) { key ->
            object : Map.Entry<String, List<Item>> {
                override val key = key
                override val value: List<Item> get() = get(key)!!
            }
        }

    // Two lazy maps are the same content when their signatures are.
    override fun equals(other: Any?): Boolean = other is LazyPanes && other.keys == keys && other.signature == signature
    override fun hashCode(): Int = signature.hashCode()
}
