package com.termino.core.reader

/**
 * Puente entre la Activity (que recibe las teclas físicas) y el lector
 * visible. El lector registra [pageTurner] mientras está en pantalla y la
 * opción "pasar página con volumen" está activa; devuelve true si consumió.
 */
object ReaderKeys {
    @Volatile
    var pageTurner: ((forward: Boolean) -> Boolean)? = null
}
