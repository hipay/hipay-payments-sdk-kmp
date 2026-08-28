package com.hipay.card.cmp

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
internal actual fun reduceMotionEnabled(): Boolean = UIAccessibilityIsReduceMotionEnabled()
