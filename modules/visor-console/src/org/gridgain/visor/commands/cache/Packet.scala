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
 * ___    _________________________ ________
 * __ |  / /____  _/__  ___/__  __ \___  __ \
 * __ | / /  __  /  _____ \ _  / / /__  /_/ /
 * __ |/ /  __/ /   ____/ / / /_/ / _  _, _/
 * _____/   /___/   /____/  \____/  /_/ |_|
 *
 */

package org.gridgain.visor.commands

/**
 * ==Overview==
 * Visor 'cache' command implementation.
 *
 * ==Importing==
 * When using this command from Scala code (not from REPL) you need to make sure to
 * properly import all necessary typed and implicit conversions:
 * <ex>
 * import org.gridgain.visor._
 * import commands.cache.VisorCacheCommand._
 * </ex>
 * Note that `VisorCacheCommand` object contains necessary implicit conversions so that
 * this command would be available via `visor` keyword.
 *
 * ==Help==
 * {{{
 * +--------------------------------------------------------------------------------+
 * | cache | Prints statistics about caches from specified node on the entire grid. |
 * |       | Output sorting can be specified in arguments.                          |
 * |       |                                                                        |
 * |       | Output abbreviations:                                                  |
 * |       |     #   Number of nodes.                                               |
 * |       |     H/h Number of cache hits.                                          |
 * |       |     M/m Number of cache misses.                                        |
 * |       |     R/r Number of cache reads.                                         |
 * |       |     W/w Number of cache writes.                                        |
 * +--------------------------------------------------------------------------------+
 * }}}
 *
 * ====Specification====
 * {{{
 *     visor cache
 *     visor cache "-i {-n=<name>}"
 *     visor cache "{-n=<name>} {-id=<node-id>|id8=<node-id8>} {-s=lr|lw|hi|mi|re|wr} {-a} {-r}"
 * }}}
 *
 * ====Arguments====
 * {{{
 *     -id=<node-id>
 *         Full ID of the node to get cache statistics from.
 *         Either '-id8' or '-id' can be specified.
 *         If neither is specified statistics will be gathered from all nodes.
 *     -id8=<node-id>
 *         ID8 of the node to get cache statistics from.
 *         Either '-id8' or '-id' can be specified.
 *         If neither is specified statistics will be gathered from all nodes.
 *     -n=<name>
 *         Name of the cache.
 *         By default - statistics for all caches will be printed.
 *     -s=no|lr|lw|hi|mi|re|wr
 *         Defines sorting type. Sorted by:
 *            lr Last read.
 *            lw Last write.
 *            hi Hits.
 *            mi Misses.
 *            rd Reads.
 *            wr Writes.
 *         If not specified - default sorting is 'lr'.
 *     -i
 *         Interactive mode.
 *         User can interactively select node for cache statistics.
 *     -r
 *         Defines if sorting should be reversed.
 *         Can be specified only with '-s' argument.
 *     -a
 *         Prints details statistics about each cache.
 *         By default only aggregated summary is printed.
 * }}}
 *
 * ====Examples====
 * {{{
 *     visor cache "-id8=12345678 -s=hi -r"
 *         Prints summary statistics about caches from node with specified id8
 *         sorted by number of hits in reverse order.
 *     visor cache "-i"
 *         Prints cache statistics for interactively selected node.
 *     visor cache "-s=hi -r -a"
 *         Prints detailed statistics about all caches sorted by number of hits in reverse order.
 *     visor cache
 *         Prints summary statistics about all caches.
 * }}}
 */
package object cache
