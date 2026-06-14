// TODO(7.2): replace this throwaway scaffold with the real HiPay Jetpack Compose
// card-entry component (live formatting, network icons, CVC policy, tokenization via the
// commonMain contract in com.hipay.card.validation). It exists ONLY so the story 7.1
// UI-test harness has a composable to drive and read. i18n (FR/EN/IT via strings.xml +
// the parity guard) is story 7.3 — the hardcoded English labels here are intentional.
//
// PCI: this module is on the com.hipay.card anti-logging path — never log here.
package com.hipay.card.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Provisional test tags for the harness. TODO(7.2): switch to the real component's tags. */
object CardEntryHarnessTags {
    const val HOLDER = "hipay.card.holder"
    const val NUMBER = "hipay.card.number"
    const val EXPIRY = "hipay.card.expiry"
    const val CVC = "hipay.card.cvc"
}

@Composable
fun PlaceholderCardEntry(modifier: Modifier = Modifier) {
    Column(modifier) {
        ScaffoldField(CardEntryHarnessTags.HOLDER, label = "Card holder")
        ScaffoldField(CardEntryHarnessTags.NUMBER, label = "Card number")
        ScaffoldField(CardEntryHarnessTags.EXPIRY, label = "Expiry date")
        // The CVC field is rendered disabled and carries a readable state semantic so the
        // harness can prove it reads a *state* (not just a label) — mirrors the iOS harness
        // reading the network chip's .isSelected trait in story 5.3.
        ScaffoldField(CardEntryHarnessTags.CVC, label = "Security code", enabled = false, state = "disabled")
    }
}

@Composable
private fun ScaffoldField(
    tag: String,
    label: String,
    enabled: Boolean = true,
    state: String? = null,
) {
    var text by remember { mutableStateOf("") }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        enabled = enabled,
        modifier = Modifier
            .testTag(tag)
            .semantics {
                contentDescription = label
                if (state != null) stateDescription = state
            },
    )
}
