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

internal val IcWaterDrop: ImageVector
    get() {
        if (_waterDrop != null) {
            return _waterDrop!!
        }
        _waterDrop = materialIcon(name = "Outlined.WaterDrop") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveToRelative(-5.33f, 4.55f, -8.0f, 8.48f, -8.0f, 11.8f)
                curveToRelative(0.0f, 4.98f, 3.8f, 8.2f, 8.0f, 8.2f)
                reflectiveCurveToRelative(8.0f, -3.22f, 8.0f, -8.2f)
                curveTo(20.0f, 10.48f, 17.33f, 6.55f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-3.35f, 0.0f, -6.0f, -2.57f, -6.0f, -6.2f)
                curveToRelative(0.0f, -2.34f, 1.95f, -5.44f, 6.0f, -9.14f)
                curveToRelative(4.05f, 3.7f, 6.0f, 6.79f, 6.0f, 9.14f)
                curveTo(18.0f, 17.43f, 15.35f, 20.0f, 12.0f, 20.0f)
                close()
                moveTo(7.83f, 14.0f)
                curveToRelative(0.37f, 0.0f, 0.67f, 0.26f, 0.74f, 0.62f)
                curveToRelative(0.41f, 2.22f, 2.28f, 2.98f, 3.64f, 2.87f)
                curveToRelative(0.43f, -0.02f, 0.79f, 0.32f, 0.79f, 0.75f)
                curveToRelative(0.0f, 0.4f, -0.32f, 0.73f, -0.72f, 0.75f)
                curveToRelative(-2.13f, 0.13f, -4.62f, -1.09f, -5.19f, -4.12f)
                curveTo(7.01f, 14.42f, 7.37f, 14.0f, 7.83f, 14.0f)
                close()
            }
        }
        return _waterDrop!!
    }

private var _waterDrop: ImageVector? = null
