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

package org.gridgain.scalar.examples

import org.gridgain.scalar.scalar
import scalar._
import org.gridgain.grid.Grid
import java.util.Date

/**
 * Demonstrates a cron-based `Callable` execution scheduling.
 * The example schedules task that returns result. To trace the execution result it uses method
 * `GridScheduleFuture.get()` blocking current thread and waiting for result of the next execution.
 * <p>
 * Remote nodes should always be started with special configuration file which
 * enables P2P class loading: `'ggstart.{sh|bat} examples/config/example-compute.xml'`.
 */
object ScalarScheduleCallableExample extends App {
    scalar("examples/config/example-compute.xml") {
        var cnt = 0

        // Schedule callable that returns incremented value each time.
        val fut = grid$.scheduleLocalCall(
            () => {
                cnt += 1

                cnt
            },
            "{1, 3} * * * * *" // Cron expression.
        )

        println(">>> Started scheduling callable execution at " + new Date + ". " +
            "Wait for 3 minutes and check the output.")

        println(">>> First execution result: " + fut.get + ", time: " + new Date)
        println(">>> Second execution result: " + fut.get + ", time: " + new Date)
        println(">>> Third execution result: " + fut.get + ", time: " + new Date)

        println(">>> Execution scheduling stopped after 3 executions.")

        println(">>> Check local node for output.")
    }
}
