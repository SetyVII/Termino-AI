package com.termino.core.reader

import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.NestedScrollView

@Composable
fun NativeReaderView(
    controller: NativeReaderController,
    content: ReaderContent?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textView = remember(context) {
        TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setTextIsSelectable(true)
            isFocusable = true
            isFocusableInTouchMode = true
        }
    }
    val scrollView = remember(context, textView) {
        NestedScrollView(context).apply {
            isFillViewport = false
            addView(
                textView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            ReaderGestureLayout(viewContext).apply {
                addView(
                    scrollView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                onSwipeLeft = {
                    controller.listener?.onUserPageTurn()
                    controller.nextPage()
                }
                onSwipeRight = {
                    controller.listener?.onUserPageTurn()
                    controller.previousPage()
                }
                canSwipe = { !controller.hasActiveSelection() }
                onPinchStart = { x, y -> controller.beginPinch(x, y) }
                onPinch = { factor -> controller.updatePinch(factor) }
                onPinchEnd = { factor -> controller.endPinch(factor) }
                controller.attach(scrollView, textView)
            }
        },
        update = { }
    )

    LaunchedEffect(content?.requestId) {
        content?.let { controller.load(it) }
    }

    DisposableEffect(Unit) {
        onDispose { controller.detach() }
    }
}
