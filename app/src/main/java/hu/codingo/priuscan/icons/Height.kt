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

internal val IcHeight: ImageVector
    get() {
        if (_height != null) {
            return _height!!
        }
        _height = materialIcon(name = "Outlined.Height") {
            materialPath {
                moveTo(13.0f, 6.99f)
                lineToRelative(3.0f, 0.0f)
                lineToRelative(-4.0f, -3.99f)
                lineToRelative(-4.0f, 3.99f)
                lineToRelative(3.0f, 0.0f)
                lineToRelative(0.0f, 10.02f)
                lineToRelative(-3.0f, 0.0f)
                lineToRelative(4.0f, 3.99f)
                lineToRelative(4.0f, -3.99f)
                lineToRelative(-3.0f, 0.0f)
                close()
            }
        }
        return _height!!
    }

private var _height: ImageVector? = null
