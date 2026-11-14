package com.lightfm.radio

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.TextRange
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow

// This device's LightOS GetKeyboardOptions response predates this SDK (missing `swipeEnabled`), so
// rememberKeyboardOptions() crashes deserializing it. Use local defaults for every keyboard in the tool.
internal val LOCAL_KEYBOARD_OPTIONS = MutableStateFlow(defaultKeyboardOptions())

// Build the embedded LP3 keyboard's view-model wired to [state]; [onReturn] fires on the Return key.
// (The compose viewModel() helper isn't on the tool classpath, so instantiate + remember it directly.)
@Composable
internal fun rememberEmbeddedKeyboard(
    state: TextFieldState,
    singleLine: Boolean = true,
    onReturn: () -> Unit = {},
): EnQwertyLp3KeyboardViewModel<*> {
    val currentOnReturn by rememberUpdatedState(onReturn)
    val callback = remember(state, singleLine) { TextKeyboardCallback(state, singleLine) { currentOnReturn() } }
    return remember(callback) {
        EnQwertyLp3KeyboardViewModel<Unit>(
            callback,
            keyboardOptionsFlow = LOCAL_KEYBOARD_OPTIONS,
            optionsForLayout = { LayoutOptions(!it.isRootLayout) },
        )
    }
}

// keyboard → TextFieldState glue (the SDK's TextInputKeyboardCallback is internal, so replicate it).
private class TextKeyboardCallback(
    private val state: TextFieldState,
    private val singleLine: Boolean,
    private val onReturn: () -> Unit,
) : Lp3RepeatableKeyboardCallback {
    override fun onKeyPressed(code: Int) = Unit
    override fun onSpecialKeyPressed(key: SpecialKey) { if (key == SpecialKey.Space) insertAtCursor(" ") }
    override fun onKeyReleased(code: Int) = insertCodePoint(code)
    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> {
                val before = state.text.subSequence(0, state.selection.min)
                deleteBeforeCursor(if (before.isNotEmpty() && Character.isLowSurrogate(before.last())) 2 else 1)
            }
            SpecialKey.Return -> if (singleLine) onReturn() else insertAtCursor("\n")
            else -> Unit
        }
    }
    override fun onKeyLongPressed(code: Int) = Unit
    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            val before = state.text.subSequence(0, state.selection.min)
            val lastSpace = before.trimEnd().indexOfLast { it.isWhitespace() }
            deleteBeforeCursor(before.length - if (lastSpace >= 0) lastSpace + 1 else 0)
        }
    }
    override fun onKeyRepeated(code: Int) = insertCodePoint(code)
    override fun onSpecialKeyRepeated(specialKey: SpecialKey) { if (specialKey == SpecialKey.Space) insertAtCursor(" ") }
    override fun onSubmitWord(word: CharSequence) = insertAtCursor(word.toString())

    private fun insertCodePoint(code: Int) = insertAtCursor(buildString { appendCodePoint(code) })

    private fun insertAtCursor(text: String) {
        state.edit {
            val start = selection.min
            val end = selection.max
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}
