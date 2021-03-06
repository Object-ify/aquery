package edu.nyu.aquery.analysis

import edu.nyu.aquery.ast._
import AnalysisTypes._


/**
 * Performs a "soft" type check on an aquery program. We call this soft as a lot of information
 * won't be available until runtime, given kdb+'s dynamic typing. So the main goal of this check
 * is to catch errors already known at translation time.
 *
 * Note that for the most part, we deal with unknown types, so this makes it hard to justify
 * a very in-depth analysis of UDFs (since most will return unknown type), and similar constructs.
 * However, this does catch some common issues and can be easily extended to cover more cases.
 *
 * @param info contains information related to functions, particularly the number of arguments
 *             required in a call (for UDFs), along with return types and argument types (for built
 *             ins)
 */
class TypeChecker(info: FunctionInfo) {
  // convenience wrapper around type error
  private def err(t: TypeTag, f: TypeTag, e: Expr) = TypeError(t, f, e.pos)

  /**
   * Check that the expression has type `expect`. Returns this error and any errors that
   * were found during the type checking of the expression itself
   * @param expect expected type
   * @param expr
   * @return
   */
  def checkTypeTag(expect: Set[TypeTag], expr: Expr): Seq[AnalysisError] = {
    val (t, errors) = checkExpr(expr)
    val tagError =
      if (expect.contains(t) || t == TUnknown || expect.contains(TUnknown))
        List()
      else
        // just provide first option for error message
        List(err(expect.head, t, expr))
    errors ++ tagError
  }

  /**
   * Type check binary expressions
   * @param expr
   * @return
   */
  def checkBinExpr(expr: BinExpr): (TypeTag, Seq[AnalysisError]) =  expr.op match {
    // && and || are assumed to be solely for booleans, users can use min/max for numeric
    case Land | Lor =>
      (TBoolean, checkTypeTag(bool, expr.l) ++ checkTypeTag(bool, expr.r))
    case Lt | Le | Gt | Ge  =>
      (TBoolean, checkTypeTag(num, expr.l) ++ checkTypeTag(num, expr.r))
    // requires that the types of the args agree
    case Eq | Neq =>
      val (leftType, leftErrors) = checkExpr(expr.l)
      (TBoolean, leftErrors ++ checkTypeTag(Set(leftType), expr.r))
    // can use both numeric and boolean to allow helpful boolean arithmetic e.g. c1 * c2 > 2
    case Plus | Minus | Times | Div | Exp =>
      (TNumeric, checkTypeTag(numAndBool,  expr.l) ++ checkTypeTag(numAndBool, expr.r))
  }

  /**
   * Type check unary expressions
   * @param expr
   * @return
   */
  def checkUnaryExpr(expr: UnExpr): (TypeTag, Seq[AnalysisError]) = expr.op match {
    case Not =>
      (TBoolean, checkTypeTag(bool, expr.v))
    case Neg =>
      (TNumeric, checkTypeTag(num, expr.v))
  }

  /**
   * Type check function calls. For built-ins this includes checking the type of the actual
   * parameters versus those of the formals (defined in edu.nyu.aquery.analysis.FunctionInfo) and
   * returning the predefined type. For UDFs this only includes checking the number of arguments
   * and will always return TUnknown.
   *
   * For array indexing (which is in essence a function call), we always return TUnknown, and
   * simply check the array expression for any errors. A remaining todo is to check the size of
   * the array expression before indexing
   * @param expr
   * @return
   */
  def checkCallExpr(expr: CallExpr): (TypeTag, Seq[AnalysisError]) = expr match {
    case FunCall(f, args) =>
      // if we have a match, then check arguments etc and get appropriate return type
      info(f).map { x =>
        val typesAndErrors = args.map(e => checkExpr(e))
        val argTypes = typesAndErrors.map(_._1)
        val argErrors = typesAndErrors.flatMap(_._2)
        val ret = x.signature.lift(argTypes)
        val badCall = ret.map(_ => Nil).getOrElse(BadCall(f, expr.pos) :: Nil)
        (ret.getOrElse(TUnknown), badCall ++ argErrors)
      }.getOrElse {
        (TUnknown, args.flatMap(a => checkExpr(a)._2))
      }
    // TODO: size checks as part of type analysis
    case ArrayIndex(e, _) => (TUnknown, checkExpr(e)._2)
  }

  /**
   * A case expression is well typed if:
   *  - If there is a initial expression, then when conditions match that type (or are unknown),
   *    otherwise all when conditions are boolean (or unknown)
   *  - The types along all return branches must agree
   * @param c
   * @return
   */
  def checkCaseExpr(c: Case): (TypeTag, Seq[AnalysisError]) = c match {
    case Case(cond, when, e) =>
      // if no case expression, then conditions in if-else must be boolean
      val (condType, condErrors) = cond.map(checkExpr).getOrElse((TBoolean, Nil))
      // all branches should be boolean conditions or the same type as the case
      val ifErrors = when.flatMap(w => checkTypeTag(Set(condType), w.c))
      // first condition defines type of branches
      val (elseType, whenErrors) = when match {
        // go ahead with unknown type tag, and report missing as error
        // note that this conditions should actually never take place, as restricted by parse
        case Nil => (TUnknown, List(err(TBoolean, TUnit, c)))
        case x :: xs =>
          val (thenType, errs) = checkExpr(x.t)
          val moreErrs = xs.flatMap(checkTypeTag(Set(thenType), _))
          (thenType, errs ++ moreErrs)
      }
      val elseErrors = e.map(checkTypeTag(Set(elseType), _)).getOrElse(Nil)

      (elseType, condErrors ++ ifErrors ++ whenErrors ++ elseErrors)
  }

  /**
   * Provides types to literals. We treat date/timestamp as numeric
   * @param e
   * @return
   */
  def checkLit(e: Lit): (TypeTag, Seq[AnalysisError]) = {
    val tag = e match {
      case IntLit(_) => TNumeric
      case FloatLit(_) => TNumeric
      case StringLit(_) => TString
      case DateLit(_) =>  TNumeric
      case BooleanLit(_) => TBoolean
      case TimestampLit(_) => TNumeric
    }
    (tag, Nil)
  }

  /**
   * Type check an expression
   * @param expr
   * @return
   */
  def checkExpr(expr: Expr): (TypeTag, Seq[AnalysisError]) = expr match {
    case bin: BinExpr => checkBinExpr(bin)
    case un: UnExpr => checkUnaryExpr(un)
    case call: CallExpr => checkCallExpr(call)
    case caseE: Case => checkCaseExpr(caseE)
    case lit: Lit => checkLit(lit)
    case Id(_) => (TUnknown, Nil)
    case RowId => (TNumeric, Nil)
    case ColumnAccess(_, _) | WildCard => (TUnknown, Nil)
    case Each(e) => checkExpr(e)
  }

  /**
   * Some expression are only allowed in queries
   * @param expr expression to check
   * @return
   */
  def checkProhibitedExpr(expr: Expr): Seq[AnalysisError] = expr match {
    case WildCard | ColumnAccess(_, _) | RowId => List(IllegalExpr(expr.dotify(1)._1, expr.pos))
    case _ => expr.children.flatMap(checkProhibitedExpr)
  }

  /**
   * Recursively type check expressions in a relational algebra operator and its arguments.
   * Filter and having must be boolean expressions, similarly for conditions in a join.
   * @param r
   * @return
   */
  def checkExprInRelAlg(r: RelAlg): Seq[AnalysisError] = {
    val selfErrors = r match {
      case Filter(_, fs, _) => r.expr.flatMap(checkTypeTag(bool, _))
      case GroupBy(_, _, having, _) =>
        // note that we check having twice, just for simplicity of code
        having.flatMap(checkTypeTag(bool, _)) ++ r.expr.flatMap(checkExpr(_)._2)
      case _: Join => r.expr.flatMap(checkTypeTag(bool, _))
      case SortBy(_, _, _) => checkSortExpr(r.expr)
      case _ => r.expr.flatMap(checkExpr(_)._2)
    }
    // recursively check
    selfErrors ++ r.children.flatMap(checkExprInRelAlg)
  }

  /**
   * Check that the expression used as sorting only correspond to simple columns and accesses
   * of the form t.c1
   * @param s
   * @return
   */
  def checkSortExpr(s: Seq[Expr]): Seq[AnalysisError] = {
    s.collect {
      case x if !x.isInstanceOf[Id] && !x.isInstanceOf[ColumnAccess] =>
        IllegalExpr(x.dotify(1)._1, x.pos)
    }
  }

  /**
   * Get all tables available in a query
   * @param r
   * @return
   */
  def allTablesInQuery(r: RelAlg): Seq[Table] = r match {
    case t @ Table(_, _, _) => List(t)
    case _ => r.children.flatMap(allTablesInQuery)
  }

  /**
   * Get all distinct column accesses (e.g. t.c) in a relational algebra operation
   * @param r
   * @return
   */
  def allColAccesses(r: RelAlg): Set[ColumnAccess] = {
    (r +: r.children).flatMap(_.expr.flatMap(allColAccesses)).toSet
  }

  /**
   * Get all distinct column accesses (e.g. t.c) in an expression
   * @param e
   * @return
   */
  def allColAccesses(e: Expr): Set[ColumnAccess] = e match {
    case c @ ColumnAccess(_, _) => Set(c)
    case _ => e.children.flatMap(allColAccesses).toSet
  }

  def checkColAccesses(tables: Seq[String], cas: Set[ColumnAccess]): Seq[AnalysisError] = {
    // count times each table name appears
    val countByName = tables.groupBy(identity).mapValues(_.size)
    // remove any table names that might be ambiguous
    val ambigTables = tables.filter(countByName(_) > 1)
    // check column access based on unique table names
    cas.collect {
      case ca if ambigTables.contains(ca.t) => AmbigColAccess(ca.t + "." + ca.c, ca.pos)
      case ca if !tables.contains(ca.t) => UnknownCorrName(ca.t + "." + ca.c, ca.pos)
    }.toSeq
  }

  /**
   * Type check a query. Checks expressions (along with necessary constraints on the expressions).
   * Checks that table names are unique (or disambiguated with correlation names).
   * Checks that all column accesses of the form t.c refer to tables available in the query
   * @param r
   * @return
   */
  def checkRelAlg(r: RelAlg): Seq[AnalysisError] = {
    // get full name of a table
    def fullName(t: Table): String = t.alias.map(a => t.n + " as " + a).getOrElse(t.n)
    // check a sequence of tables for duplicates when grouping along a given dimension
    def checkDuplicates[A](ts: Seq[Table], grp: Table => A): Seq[AnalysisError] = {
      // take first 2 duplicates in each key
      val dupes = ts.groupBy(grp).values.toList.collect { case x if x.length > 1 => x.take(2) }
      // map all duplicates to errors
      dupes.map { case Seq(t1, t2) => DuplicateTableName(fullName(t1), fullName(t2), t1.pos, t2.pos) }
    }
    // errors stemming from expressions
    val exprErrors = checkExprInRelAlg(r)
    // all tables available in the query
    val tables = allTablesInQuery(r)
    // check correlation names for duplication
    val dupCorrErrors = checkDuplicates(tables.filter(_.alias.isDefined), _.alias)
    // check table names for duplications (note that same table name, diff corr name is not a dupe)
    val dupTableErrors = checkDuplicates(tables, identity)
    // if the table has an alias, that is all it can be referred as
    val tableNames = tables.flatMap(t => Set(t.alias.getOrElse(t.n)))
    val unkCorrErrors = checkColAccesses(tableNames, allColAccesses(r))
    exprErrors ++ dupCorrErrors ++ dupTableErrors ++ unkCorrErrors
  }


  /**
   * Check local queries and the main query in a full query construct
   * @param q
   * @return
   */
  def checkQuery(q: Query): Seq[AnalysisError] =
    q.local.flatMap(lq => checkRelAlg(lq._3)) ++ checkRelAlg(q.main)

  /**
   * Check update and delete. Imposes similar restrictions on where and having clauses as
   * checkRelAlg. Also checks column accesses to make sure refer to appropriate table name.
   * @param q
   * @return
   */
  def checkModificationQuery(q: ModificationQuery): Seq[AnalysisError] = q match {
    case Update(t, u, o, w, g, h) =>
      u.flatMap(c => checkExpr(c._2)._2) ++
        checkSortExpr(o.map(_._2)) ++
        w.flatMap(c => checkTypeTag(bool, c)) ++
        g.flatMap(c => checkExpr(c)._2) ++
        h.flatMap(c => checkTypeTag(bool, c)) ++
        checkColAccesses(List(t), q.expr.flatMap(allColAccesses).toSet)
    case Delete(t, del, o, g, h) =>
      (del match {
        case Right(w) => w.flatMap(c => checkTypeTag(bool, c))
        case _ => Nil
      }) ++
        checkSortExpr(o.map(_._2)) ++
        g.flatMap(c => checkExpr(c)._2) ++
        h.flatMap(c => checkTypeTag(bool, c)) ++
        checkColAccesses(List(t), q.expr.flatMap(allColAccesses).toSet)
  }

  /**
   * Checks table creation and insertion. Note that this DOES NOT check that the values
   * being inserted into a table match the appropriate types. This stems from the observation
   * that for the most part we won't have the types of columns until runtime, so most will be
   * TUnknown, making this check unlikely to spot meaningful issues.
   * @param m
   * @return
   */
  def checkTableModification(m: TableModification): Seq[AnalysisError] = m match {
    case Create(_, Right(q)) => checkQuery(q)
    case Insert(_, o, _, Left(e)) =>
      e.flatMap(checkExpr(_)._2) ++ checkSortExpr(o.map(_._2)) ++ e.flatMap(checkProhibitedExpr)
    case Insert(_, o, _, Right(q)) => checkQuery(q) ++ checkSortExpr(o.map(_._2))
    case _ => Nil
  }

  /**
   * Checks UDF. Checks all expressions in the UDF body
   * @param f
   * @return
   */
  def checkUDF(f: UDF): Seq[AnalysisError] =
    f.cs.flatMap {
      case Left(as) => checkExpr(as.e)._2 ++ checkProhibitedExpr(as.e)
      case Right(e) => checkExpr(e)._2 ++ checkProhibitedExpr(e)
    }

  /**
   * Check a top level construct
   * @param com
   * @return
   */
  def checkTopLevel(com: TopLevel): Seq[AnalysisError] = com match {
    case q: Query => checkQuery(q)
    case mq: ModificationQuery => checkModificationQuery(mq)
    case tm: TableModification => checkTableModification(tm)
    case u: UDF => checkUDF(u)
    // we obviously don't type check verbatim code
    case v: VerbatimCode => List()
  }

  def typeCheck(prog: Seq[TopLevel]): Seq[AnalysisError] = prog.flatMap(checkTopLevel)

}

object TypeChecker {
  def apply(prog: Seq[TopLevel]): TypeChecker = {
    // UDFs are checked solely for number of args to call
    val ctFunArgs = (s: FunctionInfo, f: UDF) => {
      s.write(f.n, new UDFSummary(f.n, { case x if x.length == f.args.length => TUnknown }))
    }
    // environment is collected sequentially
    val env = FunctionInfo(prog.collect { case f: UDF => f }, ctFunArgs)
    // create type checker with the current environment
   new TypeChecker(env)
  }

  def apply(): TypeChecker = apply(Nil)
}
