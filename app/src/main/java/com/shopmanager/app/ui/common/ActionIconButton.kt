package com.shopmanager.app.ui.common

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * زر أيقونة دائري واحد (40dp) بنفس تعبئة كبسولة [AppActionPill]. أُعيدت كتابته بـ Box
 * بدل IconButton حتى لا يفرض Material حدّاً أدنى للمس (48dp) يجعل الدوائر المتجاورة تتلامس.
 */
@Composable
fun ActionIconButton(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectDelayedPressedAsState()
    // LAG FIX: قراءة الـ scale في مرحلة الرسم (graphicsLayer) بدل إعادة التركيب كل إطار.
    val scale = animateFloatAsState(
        targetValue = if (pressed) MotionSpecs.pressScale() else 1f,
        animationSpec = MotionSpecs.pressSpring(),
        label = "actionButtonScale"
    )
    val haptics = LocalHapticFeedback.current

    Box(
        modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}
