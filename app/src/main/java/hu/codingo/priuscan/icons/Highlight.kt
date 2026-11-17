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

internal val IcHighlight: ImageVector
    get() {
        if (_highlight != null) {
            return _highlight!!
        }
        _highlight = materialIcon(name = "Outlined.Highlight") {
            materialPath {
                moveTo(6.0f, 14.0f)
                lineToRelative(3.0f, 3.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-5.0f)
                lineToRelative(3.0f, -3.0f)
                lineTo(18.0f, 9.0f)
                lineTo(6.0f, 9.0f)
                verticalLineToRelative(5.0f)
                close()
                moveTo(8.0f, 11.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.17f)
                lineToRelative(-3.0f, 3.0f)
                lineTo(13.0f, 20.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-3.83f)
                lineToRelative(-3.0f, -3.0f)
                lineTo(8.0f, 11.0f)
                close()
                moveTo(11.0f, 2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(3.502f, 5.874f)
                lineTo(4.916f, 4.46f)
                lineToRelative(2.122f, 2.12f)
                lineToRelative(-1.414f, 1.415f)
                close()
                moveTo(16.96f, 6.582f)
                lineToRelative(2.123f, -2.12f)
                lineToRelative(1.413f, 1.416f)
                lineToRelative(-2.123f, 2.12f)
                close()
            }
        }
        return _highlight!!
    }

private var _highlight: ImageVector? = null
