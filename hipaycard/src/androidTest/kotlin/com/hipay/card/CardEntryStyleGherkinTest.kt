package com.hipay.card

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * Contractual styling Gherkin scenario, verbatim from PI-6085:
 *
 *   Étant donné que le développeur instancie HiPayCardEntry avec HiPayCardEntryStyle.hipayDefault
 *   Quand la vue est affichée à l'écran sur iOS et Android
 *   Alors les champs affichent les valeurs visuelles définies dans le style par défaut
 *
 * On-screen half of the scenario for native Android: the component instantiated with
 * `hipayDefault` renders its four fields, each honoring the default `fieldHeight`
 * MINIMUM (42dp — a scale-invariant lower bound only: font scale and the 48dp
 * trailing-affordance floor legitimately grow a field). The default VALUES are pinned
 * by the shared-contract test ([com.hipay.card.style] commonTest) and the CMP mapping
 * test; the pixel-level result is the manual G13 matrix. The custom-style scenario is
 * marked "non testable" in the ticket (demo toggle + G13); its metric application is
 * covered by [CardEntryStyleTest]. NETWORK-FREE: no digits typed.
 */
@RunWith(AndroidJUnit4::class)
class CardEntryStyleGherkinTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Scénario 1 : affichage avec le style par défaut.
    @Test
    fun defaultStyleDisplaysTheFourFieldsAtTheDefaultMetrics() {
        // Étant donné / Quand — instantiated with hipayDefault and displayed on screen.
        composeRule.setContent {
            HiPayCardEntry(
                HiPayCardEntryController(HiPayConfig("test-user", "test-pass", Environment.STAGE)).withOfflineCeiling(),
                style = HiPayCardEntryStyle.hipayDefault,
            )
        }
        composeRule.waitForIdle()

        // Alors — every field is displayed and honors the default minimum height.
        for (tag in listOf(
            HiPayCardEntryTags.HOLDER,
            HiPayCardEntryTags.NUMBER,
            HiPayCardEntryTags.EXPIRY,
            HiPayCardEntryTags.CVC,
        )) {
            val field = composeRule.onNodeWithTag(tag)
            field.assertIsDisplayed()
            val height = field.getBoundsInRoot().height.value
            assertTrue(
                "$tag is $height dp — expected >= ~42 (hipayDefault fieldHeight minimum)",
                height >= 41f,
            )
        }
    }
}
