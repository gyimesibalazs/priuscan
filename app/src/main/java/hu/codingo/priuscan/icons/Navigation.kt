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

internal val IcNavigation: ImageVector
    get() {
        if (_navigation != null) {
            return _navigation!!
        }
        _navigation = materialIcon(name = "Outlined.Navigation") {
            materialPath {
                moveTo(12.0f, 7.27f)
                lineToRelative(4.28f, 10.43f)
                lineToRelative(-3.47f, -1.53f)
                lineToRelative(-0.81f, -0.36f)
                lineToRelative(-0.81f, 0.36f)
                lineToRelative(-3.47f, 1.53f)
                lineTo(12.0f, 7.27f)
                moveTo(12.0f, 2.0f)
                lineTo(4.5f, 20.29f)
                lineToRelative(0.71f, 0.71f)
                lineTo(12.0f, 18.0f)
                lineToRelative(6.79f, 3.0f)
                lineToRelative(0.71f, -0.71f)
                lineTo(12.0f, 2.0f)
                close()
            }
        }
        return _navigation!!
    }

private var _navigation: ImageVector? = null
