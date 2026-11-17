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

internal val IcElectricBolt: ImageVector
    get() {
        if (_electricBolt != null) {
            return _electricBolt!!
        }
        _electricBolt = materialIcon(name = "Outlined.ElectricBolt") {
            materialPath {
                moveTo(14.69f, 2.21f)
                lineTo(4.33f, 11.49f)
                curveToRelative(-0.64f, 0.58f, -0.28f, 1.65f, 0.58f, 1.73f)
                lineTo(13.0f, 14.0f)
                lineToRelative(-4.85f, 6.76f)
                curveToRelative(-0.22f, 0.31f, -0.19f, 0.74f, 0.08f, 1.01f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.3f, 0.3f, 0.77f, 0.31f, 1.08f, 0.02f)
                lineToRelative(10.36f, -9.28f)
                curveToRelative(0.64f, -0.58f, 0.28f, -1.65f, -0.58f, -1.73f)
                lineTo(11.0f, 10.0f)
                lineToRelative(4.85f, -6.76f)
                curveToRelative(0.22f, -0.31f, 0.19f, -0.74f, -0.08f, -1.01f)
                lineToRelative(0.0f, 0.0f)
                curveTo(15.47f, 1.93f, 15.0f, 1.92f, 14.69f, 2.21f)
                close()
            }
        }
        return _electricBolt!!
    }

private var _electricBolt: ImageVector? = null
