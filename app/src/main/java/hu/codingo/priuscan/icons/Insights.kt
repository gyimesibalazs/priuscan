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

internal val IcInsights: ImageVector
    get() {
        if (_insights != null) {
            return _insights!!
        }
        _insights = materialIcon(name = "Outlined.Insights") {
            materialPath {
                moveTo(21.0f, 8.0f)
                curveToRelative(-1.45f, 0.0f, -2.26f, 1.44f, -1.93f, 2.51f)
                lineToRelative(-3.55f, 3.56f)
                curveToRelative(-0.3f, -0.09f, -0.74f, -0.09f, -1.04f, 0.0f)
                lineToRelative(-2.55f, -2.55f)
                curveTo(12.27f, 10.45f, 11.46f, 9.0f, 10.0f, 9.0f)
                curveToRelative(-1.45f, 0.0f, -2.27f, 1.44f, -1.93f, 2.52f)
                lineToRelative(-4.56f, 4.55f)
                curveTo(2.44f, 15.74f, 1.0f, 16.55f, 1.0f, 18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                curveToRelative(1.45f, 0.0f, 2.26f, -1.44f, 1.93f, -2.51f)
                lineToRelative(4.55f, -4.56f)
                curveToRelative(0.3f, 0.09f, 0.74f, 0.09f, 1.04f, 0.0f)
                lineToRelative(2.55f, 2.55f)
                curveTo(12.73f, 16.55f, 13.54f, 18.0f, 15.0f, 18.0f)
                curveToRelative(1.45f, 0.0f, 2.27f, -1.44f, 1.93f, -2.52f)
                lineToRelative(3.56f, -3.55f)
                curveTo(21.56f, 12.26f, 23.0f, 11.45f, 23.0f, 10.0f)
                curveTo(23.0f, 8.9f, 22.1f, 8.0f, 21.0f, 8.0f)
                close()
            }
            materialPath {
                moveTo(15.0f, 9.0f)
                lineToRelative(0.94f, -2.07f)
                lineToRelative(2.06f, -0.93f)
                lineToRelative(-2.06f, -0.93f)
                lineToRelative(-0.94f, -2.07f)
                lineToRelative(-0.92f, 2.07f)
                lineToRelative(-2.08f, 0.93f)
                lineToRelative(2.08f, 0.93f)
                close()
            }
            materialPath {
                moveTo(3.5f, 11.0f)
                lineToRelative(0.5f, -2.0f)
                lineToRelative(2.0f, -0.5f)
                lineToRelative(-2.0f, -0.5f)
                lineToRelative(-0.5f, -2.0f)
                lineToRelative(-0.5f, 2.0f)
                lineToRelative(-2.0f, 0.5f)
                lineToRelative(2.0f, 0.5f)
                close()
            }
        }
        return _insights!!
    }

private var _insights: ImageVector? = null
