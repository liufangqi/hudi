/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.adapter

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.parser.HoodieSpark3_2ExtendedSqlParser

/**
 * The adapter for spark3.2.
 */
class Spark3_2Adapter extends Spark3Adapter {
  /**
   * if the logical plan is a TimeTravelRelation LogicalPlan.
   */
  override def isRelationTimeTravel(plan: LogicalPlan): Boolean = {
    plan.isInstanceOf[TimeTravelRelation]
  }

  /**
   * Get the member of the TimeTravelRelation LogicalPlan.
   */
  override def getRelationTimeTravel(plan: LogicalPlan): Option[(LogicalPlan, Option[Expression], Option[String])] = {
    plan match {
      case timeTravel: TimeTravelRelation =>
        Some((timeTravel.table, timeTravel.timestamp, timeTravel.version))
      case _ =>
        None
    }
  }

  override def createExtendedSparkParser: Option[(SparkSession, ParserInterface) => ParserInterface] = {
    Some(
      (spark: SparkSession, delegate: ParserInterface) => new HoodieSpark3_2ExtendedSqlParser(spark, delegate)
    )
  }
}
