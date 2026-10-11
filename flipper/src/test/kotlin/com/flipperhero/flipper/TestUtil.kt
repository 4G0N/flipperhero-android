package com.flipperhero.flipper

/** Asserts [block] throws a [T]. Works with suspend blocks (used inside runBlocking). */
suspend inline fun <reified T : Throwable> assertThrowsSuspend(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("expected ${T::class.simpleName} but got $e")
    }
    throw AssertionError("expected ${T::class.simpleName} but nothing was thrown")
}
