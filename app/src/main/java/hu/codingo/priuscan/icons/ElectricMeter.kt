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

internal val IcElectricMeter: ImageVector
    get() {
        if (_electricMeter != null) {
            return _electricMeter!!
        }
        _electricMeter = materialIcon(name = "Outlined.ElectricMeter") {
            materialPath {
                moveTo(21.0f, 11.0f)
                curveToRelative(0.0f, -4.97f, -4.03f, -9.0f, -9.0f, -9.0f)
                reflectiveCurveToRelative(-9.0f, 4.03f, -9.0f, 9.0f)
                curveToRelative(0.0f, 3.92f, 2.51f, 7.24f, 6.0f, 8.48f)
                verticalLineTo(22.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.06f)
                curveToRelative(0.33f, 0.04f, 0.66f, 0.06f, 1.0f, 0.06f)
                reflectiveCurveToRelative(0.67f, -0.02f, 1.0f, -0.06f)
                verticalLineTo(22.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.52f)
                curveTo(18.49f, 18.24f, 21.0f, 14.92f, 21.0f, 11.0f)
                close()
                moveTo(12.0f, 18.0f)
                curveToRelative(-3.86f, 0.0f, -7.0f, -3.14f, -7.0f, -7.0f)
                reflectiveCurveToRelative(3.14f, -7.0f, 7.0f, -7.0f)
                reflectiveCurveToRelative(7.0f, 3.14f, 7.0f, 7.0f)
                reflectiveCurveTo(15.86f, 18.0f, 12.0f, 18.0f)
                close()
            }
            materialPath {
                moveTo(8.0f, 7.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-8.0f)
                close()
            }
            materialPath {
                moveTo(12.75f, 10.0f)
                lineToRelative(-3.0f, 3.0f)
                lineToRelative(1.25f, 1.25f)
                lineToRelative(-1.25f, 1.25f)
                lineToRelative(1.5f, 1.5f)
                lineToRelative(3.0f, -3.0f)
                lineToRelative(-1.25f, -1.25f)
                lineToRelative(1.25f, -1.25f)
                close()
            }
        }
        return _electricMeter!!
    }

private var _electricMeter: ImageVector? = null
