package com.hipay.card.cmp

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Android's "Remove animations" accessibility setting zeroes the global animation scales; a scale of
// 0 is the accepted signal that the user wants no motion. Off-device (JVM host tests) the setting is
// absent → default to "motion allowed".
@Composable
internal actual fun reduceMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return try {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Settings.SettingNotFoundException) {
        false
    }
}
