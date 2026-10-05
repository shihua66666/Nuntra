package com.shihua66666.nuntra.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状：全部落在 4–8dp 区间。
 *
 * 需求明确要求圆角 6–8dp、不要尖锐斜切，因此不提供任何 >8dp 的大圆角，
 * 也不使用 CutCornerShape。
 */
val NuntraShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)
