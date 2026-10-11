package com.flipperhero.agent

/**
 * User-facing text passthrough. In the iOS app `L(...)` reads the module's string catalog; the
 * Android port localizes in `:app`. Here it is identity so the ported call sites stay verbatim and
 * tests can assert the English strings.
 */
internal fun L(text: String): String = text
