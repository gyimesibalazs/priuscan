/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hu.codingo.priuscan.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

internal val IcWindPower: ImageVector
    get() {
        if (_windPower != null) {
            return _windPower!!
        }
        _windPower = materialIcon(name = "Outlined.WindPower") {
            materialPath {
                moveTo(4.0f, 3.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-6.0f)
                close()
            }
            materialPath {
                moveTo(1.0f, 7.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-5.0f)
                close()
            }
            materialPath {
                moveTo(3.0f, 19.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-5.0f)
                close()
            }
            materialPath {
                moveTo(22.21f, 18.61f)
                lineToRelative(-2.28f, -4.1f)
                curveToRelative(-0.27f, -0.48f, -0.73f, -0.83f, -1.26f, -0.97f)
                lineToRelative(-2.69f, -0.67f)
                curveToRelative(-0.02f, -0.47f, -0.14f, -0.92f, -0.37f, -1.33f)
                lineToRelative(3.96f, -6.59f)
                curveToRelative(0.65f, -1.08f, 0.3f, -2.48f, -0.78f, -3.13f)
                curveToRelative(-0.36f, -0.22f, -0.77f, -0.32f, -1.17f, -0.32f)
                curveToRelative(-0.56f, 0.0f, -1.12f, 0.21f, -1.56f, 0.62f)
                lineToRelative(-3.43f, 3.21f)
                curveTo(12.23f, 5.7f, 12.0f, 6.23f, 12.0f, 6.78f)
                verticalLineToRelative(3.4f)
                curveToRelative(-0.47f, 0.17f, -0.89f, 0.45f, -1.23f, 0.82f)
                horizontalLineTo(3.28f)
                curveTo(2.02f, 11.0f, 1.0f, 12.02f, 1.0f, 13.28f)
                curveToRelative(0.0f, 1.02f, 0.67f, 1.91f, 1.65f, 2.19f)
                lineToRelative(4.51f, 1.29f)
                curveToRelative(0.18f, 0.05f, 0.37f, 0.08f, 0.55f, 0.08f)
                curveToRelative(0.36f, 0.0f, 0.72f, -0.1f, 1.03f, -0.29f)
                lineToRelative(2.24f, -1.34f)
                curveToRelative(0.29f, 0.26f, 0.63f, 0.47f, 1.02f, 0.61f)
                verticalLineTo(21.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                verticalLineToRelative(-4.28f)
                lineToRelative(4.61f, 4.61f)
                curveToRelative(0.45f, 0.45f, 1.03f, 0.67f, 1.61f, 0.67f)
                curveToRelative(0.58f, 0.0f, 1.17f, -0.22f, 1.61f, -0.67f)
                horizontalLineToRelative(0.0f)
                curveTo(22.55f, 20.61f, 22.71f, 19.5f, 22.21f, 18.61f)
                close()
                moveTo(7.72f, 14.84f)
                lineTo(3.2f, 13.55f)
                curveTo(3.08f, 13.52f, 3.0f, 13.4f, 3.0f, 13.28f)
                curveTo(3.0f, 13.13f, 3.13f, 13.0f, 3.28f, 13.0f)
                horizontalLineToRelative(6.73f)
                curveToRelative(0.0f, 0.15f, 0.01f, 0.3f, 0.03f, 0.44f)
                lineTo(7.72f, 14.84f)
                close()
                moveTo(13.0f, 14.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                curveTo(14.0f, 13.55f, 13.55f, 14.0f, 13.0f, 14.0f)
                close()
                moveTo(14.0f, 10.14f)
                verticalLineTo(6.78f)
                lineToRelative(3.43f, -3.21f)
                curveToRelative(0.05f, -0.05f, 0.19f, -0.12f, 0.34f, -0.04f)
                curveToRelative(0.13f, 0.08f, 0.18f, 0.25f, 0.1f, 0.38f)
                lineToRelative(-3.74f, 6.24f)
                lineTo(14.0f, 10.14f)
                close()
                moveTo(20.42f, 19.92f)
                curveToRelative(-0.05f, 0.05f, -0.24f, 0.16f, -0.4f, 0.0f)
                lineToRelative(-4.85f, -4.85f)
                curveToRelative(0.08f, -0.09f, 0.16f, -0.18f, 0.24f, -0.28f)
                lineToRelative(2.78f, 0.69f)
                lineToRelative(2.28f, 4.1f)
                curveTo(20.53f, 19.69f, 20.51f, 19.83f, 20.42f, 19.92f)
                close()
            }
        }
        return _windPower!!
    }

private var _windPower: ImageVector? = null
