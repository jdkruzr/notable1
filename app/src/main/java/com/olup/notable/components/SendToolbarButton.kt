package com.olup.notable

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SendToolbarButton(
    scope: CoroutineScope,
    pageView: PageView,
    onStart: suspend () -> Unit = {},
    onComplete: suspend () -> Unit = {}
) {
    IconButton(
        onClick = {
            scope.launch {
                TextRecognizer.recognizeText(
                    scope = scope,
                    pageView = pageView,
                    onStart = onStart,
                    onComplete = onComplete,
                    onTextRecognized = { /* Empty as we handle text in TextRecognizer */ }
                )
            }
        }
    ) {
        Icon(
            painter = painterResource(id = R.drawable.send),
            contentDescription = "Send to AI",
            modifier = Modifier.size(24.dp)
        )
    }
}
