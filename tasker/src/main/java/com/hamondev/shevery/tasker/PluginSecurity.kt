package com.hamondev.shevery.tasker

import android.content.Context
import java.security.MessageDigest

/** Shared automation capability embedded by the project build. */
object PluginSecurity {
    @JvmStatic
    fun getOrCreateToken(@Suppress("UNUSED_PARAMETER") context: Context): String =
        PluginContract.BUILTIN_AUTH_TOKEN

    @JvmStatic
    fun accepts(expected: String, provided: String?): Boolean = provided != null &&
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))
}
