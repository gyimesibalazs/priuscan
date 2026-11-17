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

internal val IcTurnRight: ImageVector
    get() {
        if (_turnRight != null) {
            return _turnRight!!
        }
        _turnRight = materialIcon(name = "Outlined.TurnRight") {
            materialPath {
                moveTo(17.17f, 11.0f)
                lineToRelative(-1.59f, 1.59f)
                lineTo(17.0f, 14.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(17.17f, 9.0f)
                lineTo(9.0f, 9.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(9.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-9.0f)
                lineTo(17.17f, 11.0f)
                close()
            }
        }
        return _turnRight!!
    }

private var _turnRight: ImageVector? = null
