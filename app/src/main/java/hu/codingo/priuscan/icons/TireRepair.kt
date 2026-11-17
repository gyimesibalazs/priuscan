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

internal val IcTireRepair: ImageVector
    get() {
        if (_tireRepair != null) {
            return _tireRepair!!
        }
        _tireRepair = materialIcon(name = "Outlined.TireRepair") {
            materialPath {
                moveTo(19.0f, 8.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                curveToRelative(0.0f, -0.28f, 0.11f, -0.53f, 0.29f, -0.71f)
                curveToRelative(0.4f, -0.4f, 2.46f, -1.04f, 2.46f, -1.04f)
                reflectiveCurveToRelative(-0.64f, 2.06f, -1.04f, 2.46f)
                curveTo(19.53f, 7.89f, 19.28f, 8.0f, 19.0f, 8.0f)
                close()
                moveTo(20.0f, 13.0f)
                verticalLineToRelative(5.0f)
                curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
                reflectiveCurveToRelative(-3.0f, -1.35f, -3.0f, -3.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveToRelative(-1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(3.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                verticalLineToRelative(8.17f)
                curveToRelative(0.31f, -0.11f, 0.65f, -0.17f, 1.0f, -0.17f)
                curveToRelative(1.65f, 0.0f, 3.0f, 1.35f, 3.0f, 3.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-5.0f)
                horizontalLineToRelative(-1.0f)
                lineToRelative(0.0f, -1.42f)
                curveToRelative(-1.77f, -0.77f, -3.0f, -2.53f, -3.0f, -4.58f)
                curveToRelative(0.0f, -2.76f, 2.24f, -5.0f, 5.0f, -5.0f)
                reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
                curveToRelative(0.0f, 2.05f, -1.23f, 3.81f, -3.0f, 4.58f)
                lineTo(21.0f, 13.0f)
                horizontalLineTo(20.0f)
                close()
                moveTo(22.0f, 7.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                reflectiveCurveToRelative(-3.0f, 1.34f, -3.0f, 3.0f)
                reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
                reflectiveCurveTo(22.0f, 8.66f, 22.0f, 7.0f)
                close()
                moveTo(10.0f, 7.0f)
                lineTo(8.0f, 9.0f)
                verticalLineTo(6.17f)
                lineTo(9.17f, 5.0f)
                horizontalLineTo(4.83f)
                lineTo(6.0f, 6.17f)
                verticalLineTo(9.0f)
                lineTo(4.0f, 7.0f)
                verticalLineToRelative(2.17f)
                lineToRelative(2.0f, 2.0f)
                verticalLineTo(14.0f)
                lineToRelative(-2.0f, -2.0f)
                verticalLineToRelative(2.17f)
                lineToRelative(2.0f, 2.0f)
                verticalLineTo(19.0f)
                lineToRelative(-2.0f, -2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-2.0f)
                lineToRelative(-2.0f, 2.0f)
                verticalLineToRelative(-2.83f)
                lineToRelative(2.0f, -2.0f)
                verticalLineTo(12.0f)
                lineToRelative(-2.0f, 2.0f)
                verticalLineToRelative(-2.83f)
                lineToRelative(2.0f, -2.0f)
                verticalLineTo(7.0f)
                close()
            }
        }
        return _tireRepair!!
    }

private var _tireRepair: ImageVector? = null
