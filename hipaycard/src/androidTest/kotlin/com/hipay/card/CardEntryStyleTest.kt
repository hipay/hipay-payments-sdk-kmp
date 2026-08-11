package com.hipay.card

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hipay.card.style.HiPayCardEntryStyle
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The injected [HiPayCardEntryStyle] drives the field metrics on the native Android component.
 * `fieldHeight` is a MINIMUM (`heightIn`), so a large custom value makes the entered-data field
 * visibly taller than the compact `hipayDefault` (42). Default and custom render in the SAME
 * composition and are compared to each other — a relative assertion that stays meaningful under
 * non-default device font scales, where an absolute dp threshold on the default would drift.
 * NETWORK-FREE: no digits typed.
 */
@RunWith(AndroidJUnit4::class)
class CardEntryStyleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun controller() =
        HiPayCardEntryController(HiPayConfig("test-user", "test-pass", Environment.STAGE)).withOfflineCeiling()

    @Test
    fun customFieldHeight_growsTheEntryField() {
        composeRule.setContent {
            Column {
                HiPayCardEntry(controller()) // hipayDefault
                HiPayCardEntry(controller(), style = HiPayCardEntryStyle(fieldHeight = 96f))
            }
        }
        composeRule.waitForIdle()
        // Both entries share the tag; composition order = default first, custom second.
        val fields = composeRule.onAllNodesWithTag(HiPayCardEntryTags.NUMBER)
        val defaultHeight = fields[0].getBoundsInRoot().height.value
        val customHeight = fields[1].getBoundsInRoot().height.value
        // heightIn(min = 96) → the custom field honors its minimum…
        assertTrue(
            "custom field is $customHeight dp — expected >= ~96 (heightIn min)",
            customHeight >= 95f,
        )
        // …and outgrows the compact default rendered alongside it — the style drives the metric.
        assertTrue(
            "custom ($customHeight dp) does not exceed default ($defaultHeight dp)",
            customHeight > defaultHeight,
        )
    }
}
