package com.termino.core.reader

/**
 * Bridge between the Activity (which receives hardware keys) and the visible
 * reader. The reader registers [pageTurner] while it is on screen and the
 * "turn pages with volume keys" option is on; it returns true if it consumed
 * the key.
 */
object ReaderKeys {
    @Volatile
    var pageTurner: ((forward: Boolean) -> Boolean)? = null
}
