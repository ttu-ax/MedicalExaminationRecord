package com.example.medicalrecord

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun ZoomableReportImage(path: String, modifier: Modifier = Modifier, expanded: Boolean, onToggleExpanded: () -> Unit) {
    val bitmap = remember(path) {
        if (!File(path).exists()) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var rotation by remember(path) { mutableFloatStateOf(0f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("原图 · 双指缩放")
            Row {
                TextButton(onClick = { rotation = (rotation + 90f) % 360f }) { Text("旋转") }
                TextButton(onClick = { scale = 1f; rotation = 0f; offset = Offset.Zero }) { Text("复位") }
                TextButton(onClick = onToggleExpanded) { Text(if (expanded) "收起" else "放大") }
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF202826))
                .pointerInput(path) {
                    detectTransformGestures { _, pan, zoom, angle ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        rotation += angle
                        offset += pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap == null) Text("原图不可用", color = Color.White)
            else Image(
                bitmap, "报告原图，可缩放和旋转",
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = offset.x
                    translationY = offset.y
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                },
                contentScale = ContentScale.Fit
            )
        }
    }
}
