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

internal val IcWbSunny: ImageVector
    get() {
        if (_wbSunny != null) {
            return _wbSunny!!
        }
        _wbSunny = materialIcon(name = "Outlined.WbSunny") {
            materialPath {
                moveTo(6.76f, 4.84f)
                lineToRelative(-1.8f, -1.79f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(1.79f, 1.79f)
                close()
                moveTo(1.0f, 10.5f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(2.0f)
                lineTo(1.0f, 12.5f)
                close()
                moveTo(11.0f, 0.55f)
                horizontalLineToRelative(2.0f)
                lineTo(13.0f, 3.5f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(19.04f, 3.045f)
                lineToRelative(1.408f, 1.407f)
                lineToRelative(-1.79f, 1.79f)
                lineToRelative(-1.407f, -1.408f)
                close()
                moveTo(17.24f, 18.16f)
                lineToRelative(1.79f, 1.8f)
                lineToRelative(1.41f, -1.41f)
                lineToRelative(-1.8f, -1.79f)
                close()
                moveTo(20.0f, 10.5f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-3.0f)
                close()
                moveTo(12.0f, 5.5f)
                curveToRelative(-3.31f, 0.0f, -6.0f, 2.69f, -6.0f, 6.0f)
                reflectiveCurveToRelative(2.69f, 6.0f, 6.0f, 6.0f)
                reflectiveCurveToRelative(6.0f, -2.69f, 6.0f, -6.0f)
                reflectiveCurveToRelative(-2.69f, -6.0f, -6.0f, -6.0f)
                close()
                moveTo(12.0f, 15.5f)
                curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                reflectiveCurveToRelative(1.79f, -4.0f, 4.0f, -4.0f)
                reflectiveCurveToRelative(4.0f, 1.79f, 4.0f, 4.0f)
                reflectiveCurveToRelative(-1.79f, 4.0f, -4.0f, 4.0f)
                close()
                moveTo(11.0f, 19.5f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.95f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(3.55f, 18.54f)
                lineToRelative(1.41f, 1.41f)
                lineToRelative(1.79f, -1.8f)
                lineToRelative(-1.41f, -1.41f)
                close()
            }
        }
        return _wbSunny!!
    }

private var _wbSunny: ImageVector? = null
