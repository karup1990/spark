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

package org.apache.spark.sql.catalyst.expressions.codegen

import java.io.ObjectInputStream

import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.esotericsoftware.kryo.io.{Input, Output}

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.BindReferences.bindReferences
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils

/**
 * Generates bytecode for an [[Ordering]] of rows for a given set of expressions.
 */
object GenerateOrdering extends CodeGenerator[Seq[SortOrder], BaseOrdering] with Logging {

  protected def canonicalize(in: Seq[SortOrder]): Seq[SortOrder] =
    in.map(ExpressionCanonicalizer.execute(_).asInstanceOf[SortOrder])

  protected def bind(in: Seq[SortOrder], inputSchema: Seq[Attribute]): Seq[SortOrder] =
    bindReferences(in, inputSchema)

  /**
   * Creates a code gen ordering for sorting this schema, in ascending order.
   */
  def create(schema: StructType): BaseOrdering = {
    create(schema.zipWithIndex.map { case (field, ordinal) =>
      SortOrder(BoundReference(ordinal, field.dataType, nullable = true), Ascending)
    })
  }

  /**
   * Generates the code for comparing a struct type according to its natural ordering
   * (i.e. ascending order by field 1, then field 2, ..., then field n.
   */
  def genComparisons(ctx: CodegenContext, schema: StructType): String = {
    val ordering = schema.fields.map(_.dataType).zipWithIndex.map {
      case(dt, index) => SortOrder(BoundReference(index, dt, nullable = true), Ascending)
    }
    genComparisons(ctx, ordering)
  }

  /**
   * Creates the variables for ordering based on the given order.
   */
  private def createOrderKeys(
      ctx: CodegenContext,
      row: String,
      ordering: Seq[SortOrder]): Seq[ExprCode] = {
    ctx.INPUT_ROW = row
    // to use INPUT_ROW we must make sure currentVars is null
    ctx.currentVars = null
    ordering.map(_.child.genCode(ctx))
  }

  /**
   * Generates the code for ordering based on the given order.
   */
  def genComparisons(ctx: CodegenContext, ordering: Seq[SortOrder]): String = {
    val oldInputRow = ctx.INPUT_ROW
    val oldCurrentVars = ctx.currentVars
    val rowAKeys = createOrderKeys(ctx, "a", ordering)
    val rowBKeys = createOrderKeys(ctx, "b", ordering)
    val zordered = ordering.exists(_.direction.isInstanceOf[Zorder])

    val newCode = if (zordered) {
      val (maxRange, gMin) = ordering.zipWithIndex.map {
        case(order, i) =>
          val direction = order.direction.asInstanceOf[Zorder]
          (direction.max - direction.min, direction.min)
      }.maxBy(_._1)

      val groupSizes = ordering.map {
        order =>
          val direction = order.direction.asInstanceOf[Zorder]
          (maxRange/(direction.max - direction.min), direction.min)
      }

      def comparisonCode(lExpr: Seq[ExprCode], rExpr: Seq[ExprCode]): String = {
        val compareFunc = ctx.freshName("compareArray")
        val lessMsbFunc = ctx.freshName("lessMsb")
        val lessMsbCode =
          s"""
             |
             |public boolean $lessMsbFunc(long x, long y) {
             |  return java.lang.Long.compareUnsigned(x, y) < 0 && java.lang.Long.compareUnsigned(x, x ^ y) < 0;
             |}
             """.stripMargin
        val compareCode =
          s"""
             |public int $compareFunc(long[] values1, long[] values2) {
             |	int msd = 0;
             |  int dim = 1;
             |	while (dim < values1.length) {
             |	    long l = values1[msd] ^ values2[msd];
             |	    long l1 = values1[dim] ^ values2[dim];
             |	    if ($lessMsbFunc(l, l1)) {
             |	        msd = dim;
             |	    }
             |	    dim++;
             |	}
             |	if (values1[msd] == values2[msd]) {
             |	   return 0;
             |	} else if (values1[msd] < values2[msd] ) {
             |	    return -1;
             |	} else {
             |	    return 1;
             |	}
             |}
             """.stripMargin
        def genExprs(exprCodes: Seq[ExprCode]) = {
          val newExprs = exprCodes.zip(groupSizes).map {
            case (expr, (groupSize, min)) =>
              s"($gMin + (${expr.value} - $min) * $groupSize)" +
                s"^ Long.MIN_VALUE"
          }.mkString(",")
          s"new long[]{$newExprs}"
        }
        val leftExprs = genExprs(lExpr)
        val rightExprs = genExprs(rExpr)
        ctx.addNewFunction(lessMsbFunc, lessMsbCode)
        s"""
          |${rowAKeys.map(_.code).mkString("\n")}
          |${rowBKeys.map(_.code).mkString("\n")}
          |
          |int ret = ${ctx.addNewFunction(compareFunc, compareCode)}($leftExprs, $rightExprs);
          |if (ret != 0) {
          |  return ret;
          |}
          |""".stripMargin
      }
      comparisonCode(rowAKeys, rowBKeys)
    } else {
      val comparisons = rowAKeys.zip(rowBKeys).zipWithIndex.map { case ((l, r), i) =>
      val dt = ordering(i).child.dataType
      val asc = ordering(i).isAscending
      val nullOrdering = ordering(i).nullOrdering
      val lRetValue = nullOrdering match {
        case NullsFirst => "-1"
        case NullsLast => "1"
      }
      val rRetValue = nullOrdering match {
        case NullsFirst => "1"
        case NullsLast => "-1"
      }
      s"""
          |${l.code}
          |${r.code}
          |if (${l.isNull} && ${r.isNull}) {
          |  // Nothing
          |} else if (${l.isNull}) {
          |  return $lRetValue;
          |} else if (${r.isNull}) {
          |  return $rRetValue;
          |} else {
          |  int comp = ${ctx.genComp(dt, l.value, r.value)};
          |  if (comp != 0) {
          |    return ${if (asc) "comp" else "-comp"};
          |  }
          |}
      """.stripMargin
      }

      val code = ctx.splitExpressions(
        expressions = comparisons,
        funcName = "compare",
        arguments = Seq(("InternalRow", "a"), ("InternalRow", "b")),
        returnType = "int",
        makeSplitFunction = { body =>
          s"""
            |$body
            |return 0;
          """.stripMargin
        },
        foldFunctions = { funCalls =>
          funCalls.zipWithIndex.map { case (funCall, i) =>
            val comp = ctx.freshName("comp")
            s"""
              |int $comp = $funCall;
              |if ($comp != 0) {
              |  return $comp;
              |}
            """.stripMargin
          }.mkString
        })
      ctx.currentVars = oldCurrentVars
      ctx.INPUT_ROW = oldInputRow
      code
    }
    newCode
  }

  protected def create(ordering: Seq[SortOrder]): BaseOrdering = {
    val ctx = newCodeGenContext()
    val comparisons = genComparisons(ctx, ordering)
    val codeBody = s"""
      public SpecificOrdering generate(Object[] references) {
        return new SpecificOrdering(references);
      }

      class SpecificOrdering extends ${classOf[BaseOrdering].getName} {

        private Object[] references;
        ${ctx.declareMutableStates()}

        public SpecificOrdering(Object[] references) {
          this.references = references;
          ${ctx.initMutableStates()}
        }

        public int compare(InternalRow a, InternalRow b) {
          $comparisons
          return 0;
        }

        ${ctx.declareAddedFunctions()}
      }"""

    val code = CodeFormatter.stripOverlappingComments(
      new CodeAndComment(codeBody, ctx.getPlaceHolderToComments()))
    logDebug(s"Generated Ordering by ${ordering.mkString(",")}:\n${CodeFormatter.format(code)}")

    val (clazz, _) = CodeGenerator.compile(code)
    clazz.generate(ctx.references.toArray).asInstanceOf[BaseOrdering]
  }
}

/**
 * A lazily generated row ordering comparator.
 */
class LazilyGeneratedOrdering(val ordering: Seq[SortOrder])
  extends Ordering[InternalRow] with KryoSerializable {

  def this(ordering: Seq[SortOrder], inputSchema: Seq[Attribute]) =
    this(bindReferences(ordering, inputSchema))

  @transient
  private[this] var generatedOrdering = GenerateOrdering.generate(ordering)

  def compare(a: InternalRow, b: InternalRow): Int = {
    generatedOrdering.compare(a, b)
  }

  private def readObject(in: ObjectInputStream): Unit = Utils.tryOrIOException {
    in.defaultReadObject()
    generatedOrdering = GenerateOrdering.generate(ordering)
  }

  override def write(kryo: Kryo, out: Output): Unit = Utils.tryOrIOException {
    kryo.writeObject(out, ordering.toArray)
  }

  override def read(kryo: Kryo, in: Input): Unit = Utils.tryOrIOException {
    generatedOrdering = GenerateOrdering.generate(kryo.readObject(in, classOf[Array[SortOrder]]))
  }
}

object LazilyGeneratedOrdering {

  /**
   * Creates a [[LazilyGeneratedOrdering]] for the given schema, in natural ascending order.
   */
  def forSchema(schema: StructType): LazilyGeneratedOrdering = {
    new LazilyGeneratedOrdering(schema.zipWithIndex.map {
      case (field, ordinal) =>
        SortOrder(BoundReference(ordinal, field.dataType, nullable = true), Ascending)
    })
  }
}
