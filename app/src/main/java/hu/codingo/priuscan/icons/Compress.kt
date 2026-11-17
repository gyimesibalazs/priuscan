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

internal val IcCompress: ImageVector
    get() {
        if (_compress != null) {
            return _compress!!
        }
        _compress = materialIcon(name = "Outlined.Compress") {
            materialPath {
                moveTo(4.0f, 9.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(16.0f)
                verticalLineTo(9.0f)
                horizontalLineTo(4.0f)
                close()
                moveTo(16.0f, 4.0f)
                lineToRelative(-1.41f, -1.41f)
                lineTo(13.0f, 4.17f)
                verticalLineTo(1.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.19f)
                lineTo(9.39f, 2.61f)
                lineTo(8.0f, 4.0f)
                lineToRelative(4.0f, 4.0f)
                lineTo(16.0f, 4.0f)
                close()
                moveTo(4.0f, 14.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(4.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(8.0f, 19.0f)
                lineToRelative(1.39f, 1.39f)
                lineTo(11.0f, 18.81f)
                verticalLineTo(22.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-3.17f)
                lineToRelative(1.59f, 1.59f)
                lineTo(16.0f, 19.0f)
                lineToRelative(-4.0f, -4.0f)
                lineTo(8.0f, 19.0f)
                close()
            }
        }
        return _compress!!
    }

private var _compress: ImageVector? = null
