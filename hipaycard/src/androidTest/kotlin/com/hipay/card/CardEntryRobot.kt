package com.hipay.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput

/**
 * Reusable page-object / robot for the Android Compose card component.
 * Locates fields/chips by [androidx.compose.ui.platform.testTag] and exposes
 * drive/read helpers so stories 7.3–7.4 add assertions without re-deriving
 * matchers. Now points at the real component's tags (story 7.2).
 */
class CardEntryRobot(private val rule: ComposeContentTestRule) {

    fun setContent(content: @Composable () -> Unit) {
        rule.setContent(content)
    }

    fun assertPresent(vararg tags: String) {
        tags.forEach { rule.onNodeWithTag(it).assertExists() }
    }

    fun type(tag: String, text: String) {
        rule.onNodeWithTag(tag).performTextInput(text)
        rule.waitForIdle()
    }

    /** Robust against Material3 fields carrying a label: matches the field's editable text. */
    fun assertText(tag: String, expected: String) {
        rule.onNodeWithTag(tag).assert(hasText(expected))
    }

    fun assertSelected(tag: String, selected: Boolean) {
        rule.onNodeWithTag(tag).assert(if (selected) isSelected() else isNotSelected())
    }

    fun assertEnabled(tag: String, enabled: Boolean) {
        rule.onNodeWithTag(tag).assert(if (enabled) isEnabled() else isNotEnabled())
    }

    fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    /**
     * Assert the relative vertical order of the given tags (each strictly above the
     * next) using `boundsInRoot.top` — relative only (D12). Use it per column; fields
     * sharing a row (expiry/CVC) are not ordered against each other.
     */
    fun assertVerticalOrder(vararg tagsTopToBottom: String) {
        val tops = tagsTopToBottom.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        for (i in 1 until tops.size) {
            check(tops[i - 1] < tops[i]) {
                "Field '${tagsTopToBottom[i - 1]}' (top=${tops[i - 1]}) is not above " +
                    "'${tagsTopToBottom[i]}' (top=${tops[i]})"
            }
        }
    }
}
