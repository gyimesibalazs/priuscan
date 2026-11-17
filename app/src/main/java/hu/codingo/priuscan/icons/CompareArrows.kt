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

internal val IcCompareArrows: ImageVector
    get() {
        if (_compareArrows != null) {
            return _compareArrows!!
        }
        _compareArrows = materialIcon(name = "AutoMirrored.Outlined.CompareArrows", autoMirror =
                true) {
            materialPath {
                moveTo(9.01f, 14.0f)
                lineTo(2.0f, 14.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(7.01f)
                verticalLineToRelative(3.0f)
                lineTo(13.0f, 15.0f)
                lineToRelative(-3.99f, -4.0f)
                verticalLineToRelative(3.0f)
                close()
                moveTo(14.99f, 13.0f)
                verticalLineToRelative(-3.0f)
                lineTo(22.0f, 10.0f)
                lineTo(22.0f, 8.0f)
                horizontalLineToRelative(-7.01f)
                lineTo(14.99f, 5.0f)
                lineTo(11.0f, 9.0f)
                lineToRelative(3.99f, 4.0f)
                close()
            }
        }
        return _compareArrows!!
    }

private var _compareArrows: ImageVector? = null
