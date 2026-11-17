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

internal val IcElectricCar: ImageVector
    get() {
        if (_electricCar != null) {
            return _electricCar!!
        }
        _electricCar = materialIcon(name = "Outlined.ElectricCar") {
            materialPath {
                moveTo(18.92f, 2.01f)
                curveTo(18.72f, 1.42f, 18.16f, 1.0f, 17.5f, 1.0f)
                horizontalLineToRelative(-11.0f)
                curveTo(5.84f, 1.0f, 5.29f, 1.42f, 5.08f, 2.01f)
                lineTo(3.0f, 8.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(8.0f)
                lineTo(18.92f, 2.01f)
                close()
                moveTo(6.85f, 3.0f)
                horizontalLineToRelative(10.29f)
                lineToRelative(1.08f, 3.11f)
                horizontalLineTo(5.77f)
                lineTo(6.85f, 3.0f)
                close()
                moveTo(19.0f, 13.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(14.0f)
                verticalLineTo(13.0f)
                close()
            }
            materialPath {
                moveTo(7.5f, 10.5f)
                moveToRelative(-1.5f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, 3.0f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, -3.0f, 0.0f)
            }
            materialPath {
                moveTo(16.5f, 10.5f)
                moveToRelative(-1.5f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, 3.0f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, -3.0f, 0.0f)
            }
            materialPath {
                moveTo(7.0f, 20.0f)
                lineToRelative(4.0f, 0.0f)
                lineToRelative(0.0f, -2.0f)
                lineToRelative(6.0f, 3.0f)
                lineToRelative(-4.0f, 0.0f)
                lineToRelative(0.0f, 2.0f)
                close()
            }
        }
        return _electricCar!!
    }

private var _electricCar: ImageVector? = null
