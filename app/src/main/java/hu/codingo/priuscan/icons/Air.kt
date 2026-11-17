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

internal val IcAir: ImageVector
    get() {
        if (_air != null) {
            return _air!!
        }
        _air = materialIcon(name = "Outlined.Air") {
            materialPath {
                moveTo(14.5f, 17.0f)
                curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
                reflectiveCurveToRelative(-3.0f, -1.35f, -3.0f, -3.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(9.5f)
                curveTo(13.15f, 14.0f, 14.5f, 15.35f, 14.5f, 17.0f)
                close()
                moveTo(19.0f, 6.5f)
                curveTo(19.0f, 4.57f, 17.43f, 3.0f, 15.5f, 3.0f)
                reflectiveCurveTo(12.0f, 4.57f, 12.0f, 6.5f)
                horizontalLineToRelative(2.0f)
                curveTo(14.0f, 5.67f, 14.67f, 5.0f, 15.5f, 5.0f)
                reflectiveCurveTo(17.0f, 5.67f, 17.0f, 6.5f)
                reflectiveCurveTo(16.33f, 8.0f, 15.5f, 8.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(13.5f)
                curveTo(17.43f, 10.0f, 19.0f, 8.43f, 19.0f, 6.5f)
                close()
                moveTo(18.5f, 11.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(16.5f)
                curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveTo(19.33f, 16.0f, 18.5f, 16.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(1.93f, 0.0f, 3.5f, -1.57f, 3.5f, -3.5f)
                reflectiveCurveTo(20.43f, 11.0f, 18.5f, 11.0f)
                close()
            }
        }
        return _air!!
    }

private var _air: ImageVector? = null
