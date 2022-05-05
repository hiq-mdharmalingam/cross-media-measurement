/**
 * Copyright 2022 The Cross-Media Measurement Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * ```
 *      http://www.apache.org/licenses/LICENSE-2.0
 * ```
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.wfanet.measurement.eventdataprovider.eventfiltration.validation

import com.google.api.expr.v1alpha1.Constant
import com.google.api.expr.v1alpha1.Expr
import com.google.api.expr.v1alpha1.Expr.Builder
import com.google.api.expr.v1alpha1.ParsedExpr
import com.google.protobuf.Message
import org.projectnessie.cel.Ast
import org.projectnessie.cel.CEL.parsedExprToAst
import org.projectnessie.cel.Env
import org.projectnessie.cel.EnvOption
import org.projectnessie.cel.Issues
import org.projectnessie.cel.Program
import org.projectnessie.cel.checker.Decls
import org.projectnessie.cel.common.types.pb.ProtoTypeRegistry

private const val NOT_OPERATOR = "!_"
private const val AND_OPERATOR = "_&&_"
private const val OR_OPERATOR = "_||_"

private val LEAF_ONLY_OPERATORS =
  listOf(
    "_>_",
    "_<_",
    "_!=_",
    "_==_",
    "_<=_",
    "@in",
  )
private val BOOLEAN_OPERATORS = listOf(NOT_OPERATOR, AND_OPERATOR, OR_OPERATOR)
private val ALLOWED_OPERATORS = LEAF_ONLY_OPERATORS + BOOLEAN_OPERATORS

private val TRUE_EXPRESSION =
  Expr.newBuilder().setConstExpr(Constant.newBuilder().setBoolValue(true)).build()

/**
 * Validates an Event Filtering CEL expression according to Halo rules.
 *
 * Throws a [EventFilterValidationException] on [compile] with the following codes:
 * * [EventFilterValidationException.Code.INVALID_CEL_EXPRESSION]
 * * [EventFilterValidationException.Code.INVALID_VALUE_TYPE]
 * * [EventFilterValidationException.Code.UNSUPPORTED_OPERATION]
 * * [EventFilterValidationException.Code.EXPRESSION_IS_NOT_CONDITIONAL]
 * * [EventFilterValidationException.Code.INVALID_OPERATION_OUTSIDE_LEAF]
 * * [EventFilterValidationException.Code.FIELD_COMPARISON_OUTSIDE_LEAF]
 */
object EventFilterValidator {

  private val hasIndent = { args: List<Expr> -> args.find { it.hasIdentExpr() } != null }
  private val hasCallExpr = { args: List<Expr> -> args.find { it.hasCallExpr() } != null }

  private fun validateInOperator(callExpr: Expr.Call) {
    val left = callExpr.argsList[0]
    val right = callExpr.argsList[1]
    if (!left.hasIdentExpr() && !left.hasSelectExpr()) {
      throw EventFilterValidationException(
        EventFilterValidationException.Code.INVALID_VALUE_TYPE,
        "Operator @in left argument should be a variable",
      )
    }
    if (!right.hasListExpr()) {
      throw EventFilterValidationException(
        EventFilterValidationException.Code.INVALID_VALUE_TYPE,
        "Operator @in right argument should be a list",
      )
    }
    if (right.hasListExpr()) {
      for (element in right.listExpr.elementsList) {
        if (!element.hasConstExpr()) {
          throw EventFilterValidationException(
            EventFilterValidationException.Code.INVALID_VALUE_TYPE,
            "Operator @in right argument should be a list of constants or a variable",
          )
        }
      }
    }
  }

  private fun failOnInvalidExpression(issues: Issues) {
    if (issues.hasIssues()) {
      throw EventFilterValidationException(
        EventFilterValidationException.Code.INVALID_CEL_EXPRESSION,
        issues.toString(),
      )
    }
  }

  private fun failOnListOutsideInOperator(expr: Expr) {
    if (expr.hasListExpr()) {
      throw EventFilterValidationException(
        EventFilterValidationException.Code.INVALID_VALUE_TYPE,
        "Lists are only allowed on the right side of a @in operator",
      )
    }
  }

  private fun failOnSingleToplevelValue() {
    throw EventFilterValidationException(
      EventFilterValidationException.Code.EXPRESSION_IS_NOT_CONDITIONAL,
      "Expression cannot be a single value, should be a conditional",
    )
  }

  private fun failOnVariableOutsideLeaf(args: List<Expr>) {
    if (hasIndent(args) && hasCallExpr(args)) {
      throw EventFilterValidationException(
        EventFilterValidationException.Code.FIELD_COMPARISON_OUTSIDE_LEAF,
        "Field comparison should be done only on the leaf expressions",
      )
    }
  }

  private fun failOnInvalidOperationOutsideLeaf(callExpr: Expr.Call) {
    val operator = callExpr.function
    if (LEAF_ONLY_OPERATORS.contains(operator) && hasCallExpr(callExpr.argsList)) {
      throw EventFilterValidationException(
        EventFilterValidationException.Code.INVALID_OPERATION_OUTSIDE_LEAF,
        "Operator $operator should only be used on leaf expressions",
      )
    }
  }

  private fun failOnNotAllowedOperator(operator: String?) {
    if (!ALLOWED_OPERATORS.contains(operator)) {
      throw EventFilterValidationException(
        EventFilterValidationException.Code.UNSUPPORTED_OPERATION,
        "Operator $operator is not allowed",
      )
    }
  }

  private fun validateExpr(expr: Expr) {
    // Leaf Node
    if (!expr.hasCallExpr()) {
      failOnListOutsideInOperator(expr)
      return
    }
    val callExpr: Expr.Call = expr.callExpr
    val operator = callExpr.function
    failOnNotAllowedOperator(operator)
    if (operator == "@in") {
      validateInOperator(callExpr)
      return
    }
    failOnVariableOutsideLeaf(callExpr.argsList)
    failOnInvalidOperationOutsideLeaf(callExpr)
    for (arg in callExpr.argsList) {
      validateExpr(arg)
    }
  }

  fun validateExpression(expression: Expr) {
    if (!expression.hasCallExpr()) {
      failOnSingleToplevelValue()
    }
    validateExpr(expression)
  }

  private fun Expr.toOperativeNegationNormalForm(
    operativeFields: Set<String>,
    negate: Boolean = false
  ): Expr {
    // Leaf Node, should never be reached if the EventFilter is valid. The leaf nodes are always
    // checked from the parent.
    if (!hasCallExpr()) {
      failOnSingleToplevelValue()
    }
    // Negation Node. Flip the negate flag and recurse down. If the child is a non operative
    // comparison node, return true.
    if (isNegation()) {
      val childExpr: Expr = getCallExpr().getArgsList().single()
      if (childExpr.nonOperativeComparisonNode(operativeFields)) {
        return TRUE_EXPRESSION
      }
      return childExpr.toOperativeNegationNormalForm(operativeFields, !negate)
    }
    // OR Node
    // if negating recurse down with AND and distrubute negation to children (De Morgan's laws)
    // else recurse down with OR without altering.
    if (isDisjuction()) {
      return buildToOperativeNegationNormalForm(
        if (negate) AND_OPERATOR else OR_OPERATOR,
        operativeFields,
        negate
      )
    }
    // AND Node
    // if negating recurse down with OR and distrubute negation to children (De Morgan's laws)
    // else recurse down with AND without altering.
    if (isConjuction()) {
      return buildToOperativeNegationNormalForm(
        if (negate) OR_OPERATOR else AND_OPERATOR,
        operativeFields,
        negate
      )
    }
    // Comparison Node (e.g. x == 47).  If it is for a non operative comparison, return true.
    if (nonOperativeComparisonNode(operativeFields)) {
      return TRUE_EXPRESSION
    }
    // Operative comparison node, valid statement that should not be altered.
    return this
  }

  private fun createEnv(eventMessage: Message): Env {
    val typeRegistry: ProtoTypeRegistry = ProtoTypeRegistry.newRegistry(eventMessage)
    val celVariables =
      eventMessage.descriptorForType.fields.map { field ->
        val typeName = field.messageType.fullName
        val defaultValue = eventMessage.getField(field) as? Message
        checkNotNull(defaultValue) { "eventMessage field should have Message type" }
        typeRegistry.registerMessage(defaultValue)
        Decls.newVar(
          field.name,
          Decls.newObjectType(typeName),
        )
      }
    return Env.newEnv(
      EnvOption.customTypeAdapter(typeRegistry),
      EnvOption.customTypeProvider(typeRegistry),
      EnvOption.declarations(celVariables),
    )
  }

  private fun getAst(celExpression: String, env: Env): Ast {
    val astAndIssues =
      try {
        env.compile(celExpression)
      } catch (e: Exception) {
        throw EventFilterValidationException(
          EventFilterValidationException.Code.INVALID_CEL_EXPRESSION,
          e.message ?: "Compiling expression threw an unexpected exception",
        )
      }
    failOnInvalidExpression(astAndIssues.issues)
    return astAndIssues.ast
  }

  fun compile(celExpression: String, env: Env): Ast {
    val ast = getAst(celExpression, env)
    val expr = ast.expr
    validateExpression(expr)
    return ast
  }

  fun compileToOperativeNegationNormalForm(
    celExpression: String,
    env: Env,
    operativeFields: Set<String>
  ): Ast {
    val expr = getAst(celExpression, env).expr
    validateExpression(expr)
    val nnfExpr = expr.toOperativeNegationNormalForm(operativeFields)
    return parsedExprToAst(ParsedExpr.newBuilder().setExpr(nnfExpr).build())
  }

  fun compileProgramWithEventMessage(
    celExpression: String,
    eventMessage: Message,
    operativeFields: Set<String> = emptySet()
  ): Program {
    val env = createEnv(eventMessage)
    val ast =
      if (operativeFields.isEmpty()) compile(celExpression, env)
      else compileToOperativeNegationNormalForm(celExpression, env, operativeFields)
    return env.program(ast)
  }

  private fun Expr.buildToOperativeNegationNormalForm(
    func: String,
    operativeFields: Set<String>,
    negate: Boolean = false
  ): Expr {
    val builder: Builder = toBuilderWithFunction(func)
    getCallExpr().getArgsList().forEach {
      if (it.nonOperativeComparisonNode(operativeFields)) {
        builder.getCallExprBuilder().addArgs(TRUE_EXPRESSION)
      } else {
        builder
          .getCallExprBuilder()
          .addArgs(it.toOperativeNegationNormalForm(operativeFields, negate))
      }
    }
    return builder.build()
  }
}

private fun getFieldName(selectExpr: Expr.Select): String {
  if (selectExpr.operand.hasIdentExpr()) {
    return selectExpr.operand.identExpr.name + "." + selectExpr.getField()
  }
  return getFieldName(selectExpr.operand.getSelectExpr()) + "." + selectExpr.getField()
}

private fun Expr.nonOperativeComparisonNode(operativeFields: Set<String>): Boolean {
  if (hasCallExpr()) {

    if (LEAF_ONLY_OPERATORS.contains(callExpr.function)) {
      val selectExpr =
        listOf(callExpr.argsList[0], callExpr.argsList[1])
          .filter { it.hasSelectExpr() }
          .single()
          .getSelectExpr()

      if (selectExpr == null) {
        return false
      }
      val fieldName: String = getFieldName(selectExpr)
      if (!operativeFields.contains(fieldName)) {
        return true
      }
    }
  }
  return false
}

private fun Expr.toBuilderWithFunction(func: String): Builder {
  val builder: Builder = toBuilder()
  builder.getCallExprBuilder().clearArgs()
  builder.getCallExprBuilder().setFunction(func)
  return builder
}

private fun Expr.functionMatches(funcPattern: String) =
  hasCallExpr() && getCallExpr().getFunction().matches(funcPattern.toRegex())

private fun Expr.isNegation(): Boolean = functionMatches("_*[!]_*")

private fun Expr.isConjuction(): Boolean = functionMatches("_*[&][&]_*")

private fun Expr.isDisjuction(): Boolean = functionMatches("_*[|][|]_*")
