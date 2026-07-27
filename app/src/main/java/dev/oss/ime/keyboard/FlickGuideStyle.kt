package dev.oss.ime.keyboard

/**
 * What to show while a finger is resting on a flick key.
 *
 * Both answer the same question — what will this produce? — but they trade differently. The cross
 * teaches the layout at the cost of covering four neighbouring keys while you read it; the preview
 * answers only for the direction you are already heading, and stays out of the way.
 */
enum class FlickGuideStyle {
    /** One bubble above the key, showing what releasing now would type. */
    PREVIEW,

    /** The full cross of every direction the key offers, with the current one highlighted. */
    DIRECTIONS;

    companion object {
        fun of(name: String?): FlickGuideStyle = entries.firstOrNull { it.name == name } ?: PREVIEW
    }
}
