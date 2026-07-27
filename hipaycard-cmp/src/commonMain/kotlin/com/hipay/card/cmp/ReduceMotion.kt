package com.hipay.card.cmp

import androidx.compose.runtime.Composable

/**
 * Whether the platform's "reduce motion / remove animations" accessibility setting is on. When true
 * the component drops its transitions (expand/collapse, swipe reveal) to instant, per WCAG 2.3.3.
 * Read at composition — a live toggle of the OS setting applies the next time the screen is shown.
 */
@Composable
internal expect fun reduceMotionEnabled(): Boolean
