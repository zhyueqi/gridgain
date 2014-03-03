/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*
 * ________               ______                    ______   _______
 * __  ___/_____________ ____  /______ _________    __/__ \  __  __ \
 * _____ \ _  ___/_  __ `/__  / _  __ `/__  ___/    ____/ /  _  / / /
 * ____/ / / /__  / /_/ / _  /  / /_/ / _  /        _  __/___/ /_/ /
 * /____/  \___/  \__,_/  /_/   \__,_/  /_/         /____/_(_)____/
 *
 */

package org.gridgain.scalar.lang

import org.gridgain.grid.lang._
import org.gridgain.grid._
import org.gridgain.grid.util.lang.GridInClosureX

/**
 * Peer deploy aware adapter for Java's `GridInClosureX`.
 *
 * @author @java.author
 * @version @java.version
 */
class ScalarInClosureX[T](private val f: T => Unit) extends GridInClosureX[T] {
    assert(f != null)

    peerDeployLike(f)

    /**
     * Delegates to passed in function.
     */
    @throws(classOf[GridException])
    def applyx(t: T) {
        f(t)
    }
}
