package com.nash.engine.impl.di

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * True when the host app is a debuggable build.
 *
 * A deliberate one-line duplicate of the engine/ml helper of the same name.
 * The alternative — widening that one's visibility, or promoting it into
 * core/common — would export a Context extension from a module whose whole
 * point is that it is a leaf, to save four lines. Keep the copy local to the
 * module that needs it.
 */
internal fun Context.isDebugBuild(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0