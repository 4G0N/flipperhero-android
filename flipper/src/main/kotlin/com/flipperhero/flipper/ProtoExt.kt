package com.flipperhero.flipper

import com.flipperdevices.protobuf.Main
import com.google.protobuf.ByteString

/** Sets exactly one `content` oneof field on a [Main.Builder]. */
typealias RpcContent = Main.Builder.() -> Unit

fun ByteArray.toByteString(): ByteString = ByteString.copyFrom(this)
