package com.shizush.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shizush.ui.theme.*

data class TerminalLine(
    val text: String,
    val isInput: Boolean = false,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun TerminalView(
    lines: List<TerminalLine>,
    inputValue: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onExecute: (String) -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    isExecuting: Boolean = false,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(lines.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalBackground)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp)
            ) {
                if (lines.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Welcome to Shell Terminal\nType a command to get started",
                            color = TerminalBrightBlack,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                } else {
                    Text(
                        text = buildTerminalAnnotatedString(lines),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            AnimatedVisibility(
                visible = isExecuting,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = TerminalGreen,
                    trackColor = Color.Transparent,
                )
            }
        }

        NavigationKeysRow(
            onUp = onHistoryUp,
            onDown = onHistoryDown,
            onLeft = {
                val pos = inputValue.selection.start
                if (pos > 0) {
                    onInputChange(inputValue.copy(
                        selection = TextRange(pos - 1)
                    ))
                }
            },
            onRight = {
                val pos = inputValue.selection.start
                if (pos < inputValue.text.length) {
                    onInputChange(inputValue.copy(
                        selection = TextRange(pos + 1)
                    ))
                }
            },
            onTab = {
                val text = inputValue.text
                val pos = inputValue.selection.start
                val newText = text.substring(0, pos) + "\t" + text.substring(pos)
                onInputChange(TextFieldValue(
                    text = newText,
                    selection = TextRange(pos + 1)
                ))
            },
            onCtrlC = {
                onInputChange(TextFieldValue(""))
            }
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            thickness = 0.5.dp
        )

        Surface(
            color = TerminalBackground,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$ ",
                    color = TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )

                BasicTextField(
                    value = inputValue,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = TerminalForeground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = SolidColor(TerminalCursor),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputValue.text.isNotBlank()) {
                                onExecute(inputValue.text)
                            }
                        }
                    ),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box {
                            if (inputValue.text.isEmpty()) {
                                Text(
                                    text = "Type a command...",
                                    color = TerminalBrightBlack,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun NavigationKeysRow(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onTab: () -> Unit,
    onCtrlC: () -> Unit,
) {
    Surface(
        color = TerminalBackground,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NavKeyButton("\u2191", "History up", onUp)
            NavKeyButton("\u2193", "History down", onDown)
            NavKeyButton("\u2190", "Move left", onLeft)
            NavKeyButton("\u2192", "Move right", onRight)
            NavKeyButton("TAB", "Insert tab", onTab)
            NavKeyButton("CTRL+C", "Cancel", onCtrlC)
        }
    }
}

@Composable
private fun NavKeyButton(
    text: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = TerminalSurface.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = text,
            color = TerminalForeground.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun buildTerminalAnnotatedString(lines: List<TerminalLine>): AnnotatedString {
    return buildAnnotatedString {
        for ((index, line) in lines.withIndex()) {
            when {
                line.isError -> {
                    withStyle(SpanStyle(color = TerminalRed)) {
                        append(line.text)
                    }
                }
                line.isInput -> {
                    withStyle(SpanStyle(color = TerminalGreen, fontWeight = FontWeight.Bold)) {
                        append("$ ")
                    }
                    withStyle(SpanStyle(color = TerminalForeground)) {
                        append(line.text)
                    }
                }
                else -> {
                    withStyle(SpanStyle(color = TerminalForeground)) {
                        append(line.text)
                    }
                }
            }
            if (index < lines.lastIndex) {
                append("\n")
            }
        }
    }
}
