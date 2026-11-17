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

internal val IcCyclone: ImageVector
    get() {
        if (_cyclone != null) {
            return _cyclone!!
        }
        _cyclone = materialIcon(name = "Outlined.Cyclone") {
            materialPath {
                moveTo(12.0f, 8.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
                curveToRelative(0.0f, 2.21f, 1.79f, 4.0f, 4.0f, 4.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                curveTo(16.0f, 9.79f, 14.21f, 8.0f, 12.0f, 8.0f)
                close()
                moveTo(12.0f, 14.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                curveTo(14.0f, 13.1f, 13.1f, 14.0f, 12.0f, 14.0f)
                close()
            }
            materialPath {
                moveTo(22.0f, 7.47f)
                verticalLineTo(5.35f)
                curveTo(20.05f, 4.77f, 16.56f, 4.0f, 12.0f, 4.0f)
                curveTo(9.85f, 4.0f, 7.89f, 4.86f, 6.46f, 6.24f)
                curveTo(6.59f, 5.39f, 6.86f, 3.84f, 7.47f, 2.0f)
                horizontalLineTo(5.35f)
                curveTo(4.77f, 3.95f, 4.0f, 7.44f, 4.0f, 12.0f)
                curveToRelative(0.0f, 2.15f, 0.86f, 4.11f, 2.24f, 5.54f)
                curveToRelative(-0.85f, -0.14f, -2.4f, -0.4f, -4.24f, -1.01f)
                verticalLineToRelative(2.12f)
                curveTo(3.95f, 19.23f, 7.44f, 20.0f, 12.0f, 20.0f)
                curveToRelative(2.15f, 0.0f, 4.11f, -0.86f, 5.54f, -2.24f)
                curveToRelative(-0.14f, 0.85f, -0.4f, 2.4f, -1.01f, 4.24f)
                horizontalLineToRelative(2.12f)
                curveTo(19.23f, 20.05f, 20.0f, 16.56f, 20.0f, 12.0f)
                curveToRelative(0.0f, -2.15f, -0.86f, -4.11f, -2.24f, -5.54f)
                curveTo(18.61f, 6.59f, 20.16f, 6.86f, 22.0f, 7.47f)
                close()
                moveTo(12.0f, 18.0f)
                curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
                reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
                reflectiveCurveToRelative(6.0f, 2.69f, 6.0f, 6.0f)
                reflectiveCurveTo(15.31f, 18.0f, 12.0f, 18.0f)
                close()
            }
        }
        return _cyclone!!
    }

private var _cyclone: ImageVector? = null
