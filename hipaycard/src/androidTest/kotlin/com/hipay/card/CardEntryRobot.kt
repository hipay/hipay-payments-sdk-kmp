package com.hipay.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput

/**
 * Reusable page-object / robot for the Android Compose card component (story 7.1).
 *
 * It encapsulates locating fields by [androidx.compose.ui.platform.testTag] and the
 * drive/read helpers so stories 7.2–7.4 add behavior assertions without re-deriving node
 * matchers. The tags are provisional (the throwaway placeholder).
 *
 * TODO(7.2): switch the provisional placeholder tags to the real composable's stable tags.
 */
class CardEntryRobot(private val rule: ComposeContentTestRule) {

    /** Host the composable under test (callable once per test, like ComposeTestRule.setContent). */
    fun setContent(content: @Composable () -> Unit) {
        rule.setContent(content)
    }

    fun assertPresent(vararg tags: String) {
        tags.forEach { rule.onNodeWithTag(it).assertExists() }
    }

    /** Drive input into a field. */
    fun type(tag: String, text: String) {
        rule.onNodeWithTag(tag).performTextInput(text)
        rule.waitForIdle()
    }

    /** Read back the editable text of a field. */
    fun assertText(tag: String, expected: String) {
        rule.onNodeWithTag(tag).assertTextEquals(expected)
    }

    /** Read a field's accessibility label (contentDescription). */
    fun assertContentDescription(tag: String, expected: String) {
        rule.onNodeWithTag(tag).assertContentDescriptionEquals(expected)
    }

    /** Read a field's state semantic (proves the harness reads state, not just labels). */
    fun assertState(tag: String, expected: String) {
        rule.onNodeWithTag(tag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
    }

    /**
     * Assert the relative vertical order of the fields in the rendered tree (holder above
     * number above expiry above cvc) — the Android mirror of 5.3's fieldOrder() assertion.
     * Relative, never absolute (D12).
     */
    fun assertFieldOrder(vararg tagsTopToBottom: String) {
        val tops = tagsTopToBottom.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        for (i in 1 until tops.size) {
            check(tops[i - 1] < tops[i]) {
                "Field '${tagsTopToBottom[i - 1]}' (top=${tops[i - 1]}) is not above " +
                    "'${tagsTopToBottom[i]}' (top=${tops[i]})"
            }
        }
    }
}
