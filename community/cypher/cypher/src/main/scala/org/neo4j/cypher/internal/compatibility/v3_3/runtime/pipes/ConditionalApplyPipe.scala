/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compatibility.v3_3.runtime.pipes

import org.neo4j.cypher.internal.compatibility.v3_3.runtime.ExecutionContext
import org.neo4j.cypher.internal.v3_3.logical.plans.LogicalPlanId
import org.neo4j.values.storable.Values

case class ConditionalApplyPipe(source: Pipe, inner: Pipe, items: Seq[String], negated: Boolean)
                               (val id: LogicalPlanId = LogicalPlanId.DEFAULT)
  extends PipeWithSource(source) {

  protected def internalCreateResults(input: Iterator[ExecutionContext], state: QueryState): Iterator[ExecutionContext] =
    input.flatMap {
      (outerContext) =>
        if (condition(outerContext)) {
          val original = outerContext.createClone()
          val innerState = state.withInitialContext(outerContext)
          val innerResults = inner.createResults(innerState)
          innerResults.map { context => original mergeWith context }
        } else Iterator.single(outerContext)
    }

  private def condition(context: ExecutionContext) = {
    val cond = items.exists { context.get(_).get != Values.NO_VALUE}
      if (negated) !cond else cond
  }

  private def name = if (negated) "AntiConditionalApply" else "ConditionalApply"
}
