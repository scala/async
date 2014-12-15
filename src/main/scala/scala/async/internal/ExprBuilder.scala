/*
 * Copyright (C) 2012-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package scala.async.internal

import scala.reflect.macros.Context
import scala.collection.mutable.ListBuffer
import collection.mutable
import language.existentials
import scala.reflect.api.Universe
import scala.reflect.api

trait ExprBuilder {
  builder: AsyncMacro =>

  import global._
  import defn._

  val futureSystem: FutureSystem
  val futureSystemOps: futureSystem.Ops { val universe: global.type }

  val stateAssigner  = new StateAssigner
  val labelDefStates = collection.mutable.Map[Symbol, Int]()

  trait AsyncState {
    def state: Int

    def nextStates: List[Int]

    def mkHandlerCaseForState: CaseDef

    def mkOnCompleteHandler[T: WeakTypeTag]: Option[CaseDef] = None

    var stats: List[Tree]

    final def allStats: List[Tree] = this match {
      case a: AsyncStateWithAwait => stats :+ a.awaitable.resultValDef
      case _ => stats
    }

    final def body: Tree = stats match {
      case stat :: Nil => stat
      case init :+ last => Block(init, last)
    }
  }

  /** A sequence of statements that concludes with a unconditional transition to `nextState` */
  final class SimpleAsyncState(var stats: List[Tree], val state: Int, nextState: Int, symLookup: SymLookup)
    extends AsyncState {

    def nextStates: List[Int] =
      List(nextState)

    def mkHandlerCaseForState: CaseDef =
      mkHandlerCase(state, stats :+ mkStateTree(nextState, symLookup))

    override val toString: String =
      s"AsyncState #$state, next = $nextState"
  }

  /** A sequence of statements with a conditional transition to the next state, which will represent
    * a branch of an `if` or a `match`.
    */
  final class AsyncStateWithoutAwait(var stats: List[Tree], val state: Int, val nextStates: List[Int]) extends AsyncState {
    override def mkHandlerCaseForState: CaseDef =
      mkHandlerCase(state, stats)

    override val toString: String =
      s"AsyncStateWithoutAwait #$state, nextStates = $nextStates"
  }

  /** A sequence of statements that concludes with an `await` call. The `onComplete`
    * handler will unconditionally transition to `nextState`.
    */
  final class AsyncStateWithAwait(var stats: List[Tree], val state: Int, onCompleteState: Int, nextState: Int,
                                  val awaitable: Awaitable, symLookup: SymLookup)
    extends AsyncState {

    def nextStates: List[Int] =
      List(nextState)

    override def mkHandlerCaseForState: CaseDef = {
      val callOnComplete = futureSystemOps.onComplete(Expr(awaitable.expr),
        Expr(This(tpnme.EMPTY)), Expr(Ident(name.execContext))).tree
      mkHandlerCase(state, stats ++ List(mkStateTree(onCompleteState, symLookup), callOnComplete, Return(literalUnit)))
    }

    override def mkOnCompleteHandler[T: WeakTypeTag]: Option[CaseDef] = {
      val tryGetTree =
        Assign(
          Ident(awaitable.resultName),
          TypeApply(Select(futureSystemOps.tryyGet[T](Expr[futureSystem.Tryy[T]](Ident(symLookup.applyTrParam))).tree, newTermName("asInstanceOf")), List(TypeTree(awaitable.resultType)))
        )

      /* if (tr.isFailure)
       *   result.complete(tr.asInstanceOf[Try[T]])
       * else {
       *   <resultName> = tr.get.asInstanceOf[<resultType>]
       *   <nextState>
       *   <mkResumeApply>
       * }
       */
      val ifIsFailureTree =
        If(futureSystemOps.tryyIsFailure(Expr[futureSystem.Tryy[T]](Ident(symLookup.applyTrParam))).tree,
           Block(futureSystemOps.completeProm[T](
             Expr[futureSystem.Prom[T]](symLookup.memberRef(name.result)),
             Expr[futureSystem.Tryy[T]](
               TypeApply(Select(Ident(symLookup.applyTrParam), newTermName("asInstanceOf")),
                         List(TypeTree(futureSystemOps.tryType[T]))))).tree :: Nil,
             Return(literalUnit)),
           Block(List(tryGetTree), mkStateTree(nextState, symLookup))
         )

      Some(mkHandlerCase(onCompleteState, List(ifIsFailureTree)))
    }

    override val toString: String =
      s"AsyncStateWithAwait #$state, next = $nextState"
  }

  /*
   * Builder for a single state of an async expression.
   */
  final class AsyncStateBuilder(state: Int, private val symLookup: SymLookup) {
    /* Statements preceding an await call. */
    private val stats                      = ListBuffer[Tree]()
    /** The state of the target of a LabelDef application (while loop jump) */
    private var nextJumpState: Option[Int] = None

    def +=(stat: Tree): this.type = {
      stat match {
        case Literal(Constant(())) => // This case occurs in do/while
        case _ =>
          assert(nextJumpState.isEmpty, s"statement appeared after a label jump: $stat")
      }
      def addStat() = stats += stat
      stat match {
        case Apply(fun, Nil) =>
          // labelDefStates belongs to the current ExprBuilder
          labelDefStates get fun.symbol match {
            case opt @ Some(nextState) => nextJumpState = opt // re-use object
            case None                  => addStat()
          }
        case _               => addStat()
      }
      this
    }

    def resultWithAwait(awaitable: Awaitable,
                        onCompleteState: Int,
                        nextState: Int): AsyncState = {
      val effectiveNextState = nextJumpState.getOrElse(nextState)
      new AsyncStateWithAwait(stats.toList, state, onCompleteState, effectiveNextState, awaitable, symLookup)
    }

    def resultSimple(nextState: Int): AsyncState = {
      val effectiveNextState = nextJumpState.getOrElse(nextState)
      new SimpleAsyncState(stats.toList, state, effectiveNextState, symLookup)
    }

    def resultWithIf(condTree: Tree, thenState: Int, elseState: Int): AsyncState = {
      def mkBranch(state: Int) = mkStateTree(state, symLookup)
      this += If(condTree, mkBranch(thenState), mkBranch(elseState))
      new AsyncStateWithoutAwait(stats.toList, state, List(thenState, elseState))
    }

    /**
     * Build `AsyncState` ending with a match expression.
     *
     * The cases of the match simply resume at the state of their corresponding right-hand side.
     *
     * @param scrutTree       tree of the scrutinee
     * @param cases           list of case definitions
     * @param caseStates      starting state of the right-hand side of the each case
     * @return                an `AsyncState` representing the match expression
     */
    def resultWithMatch(scrutTree: Tree, cases: List[CaseDef], caseStates: List[Int], symLookup: SymLookup): AsyncState = {
      // 1. build list of changed cases
      val newCases = for ((cas, num) <- cases.zipWithIndex) yield cas match {
        case CaseDef(pat, guard, rhs) =>
          val bindAssigns = rhs.children.takeWhile(isSyntheticBindVal)
          CaseDef(pat, guard, Block(bindAssigns, mkStateTree(caseStates(num), symLookup)))
      }
      // 2. insert changed match tree at the end of the current state
      this += Match(scrutTree, newCases)
      new AsyncStateWithoutAwait(stats.toList, state, caseStates)
    }

    def resultWithLabel(startLabelState: Int, symLookup: SymLookup): AsyncState = {
      this += mkStateTree(startLabelState, symLookup)
      new AsyncStateWithoutAwait(stats.toList, state, List(startLabelState))
    }

    override def toString: String = {
      val statsBeforeAwait = stats.mkString("\n")
      s"ASYNC STATE:\n$statsBeforeAwait"
    }
  }

  /**
   * An `AsyncBlockBuilder` builds a `ListBuffer[AsyncState]` based on the expressions of a `Block(stats, expr)` (see `Async.asyncImpl`).
   *
   * @param stats       a list of expressions
   * @param expr        the last expression of the block
   * @param startState  the start state
   * @param endState    the state to continue with
   */
  final private class AsyncBlockBuilder(stats: List[Tree], expr: Tree, startState: Int, endState: Int,
                                        private val symLookup: SymLookup) {
    val asyncStates = ListBuffer[AsyncState]()

    var stateBuilder = new AsyncStateBuilder(startState, symLookup)
    var currState    = startState

    def checkForUnsupportedAwait(tree: Tree) = if (tree exists {
      case Apply(fun, _) if isAwait(fun) => true
      case _                             => false
    }) abort(tree.pos, "await must not be used in this position")

    def nestedBlockBuilder(nestedTree: Tree, startState: Int, endState: Int) = {
      val (nestedStats, nestedExpr) = statsAndExpr(nestedTree)
      new AsyncBlockBuilder(nestedStats, nestedExpr, startState, endState, symLookup)
    }

    import stateAssigner.nextState

    // populate asyncStates
    for (stat <- stats) stat match {
      // the val name = await(..) pattern
      case vd @ ValDef(mods, name, tpt, Apply(fun, arg :: Nil)) if isAwait(fun) =>
        val onCompleteState = nextState()
        val afterAwaitState = nextState()
        val awaitable = Awaitable(arg, stat.symbol, tpt.tpe, vd)
        asyncStates += stateBuilder.resultWithAwait(awaitable, onCompleteState, afterAwaitState) // complete with await
        currState = afterAwaitState
        stateBuilder = new AsyncStateBuilder(currState, symLookup)

      case If(cond, thenp, elsep) if (stat exists isAwait) || containsForiegnLabelJump(stat) =>
        checkForUnsupportedAwait(cond)

        val thenStartState = nextState()
        val elseStartState = nextState()
        val afterIfState = nextState()

        asyncStates +=
          // the two Int arguments are the start state of the then branch and the else branch, respectively
          stateBuilder.resultWithIf(cond, thenStartState, elseStartState)

        List((thenp, thenStartState), (elsep, elseStartState)) foreach {
          case (branchTree, state) =>
            val builder = nestedBlockBuilder(branchTree, state, afterIfState)
            asyncStates ++= builder.asyncStates
        }

        currState = afterIfState
        stateBuilder = new AsyncStateBuilder(currState, symLookup)

      case Match(scrutinee, cases) if stat exists isAwait =>
        checkForUnsupportedAwait(scrutinee)

        val caseStates = cases.map(_ => nextState())
        val afterMatchState = nextState()

        asyncStates +=
          stateBuilder.resultWithMatch(scrutinee, cases, caseStates, symLookup)

        for ((cas, num) <- cases.zipWithIndex) {
          val (stats, expr) = statsAndExpr(cas.body)
          val stats1 = stats.dropWhile(isSyntheticBindVal)
          val builder = nestedBlockBuilder(Block(stats1, expr), caseStates(num), afterMatchState)
          asyncStates ++= builder.asyncStates
        }

        currState = afterMatchState
        stateBuilder = new AsyncStateBuilder(currState, symLookup)

      case ld @ LabelDef(name, params, rhs) if rhs exists isAwait =>
        val startLabelState = nextState()
        val afterLabelState = nextState()
        asyncStates += stateBuilder.resultWithLabel(startLabelState, symLookup)
        labelDefStates(ld.symbol) = startLabelState
        val builder = nestedBlockBuilder(rhs, startLabelState, afterLabelState)
        asyncStates ++= builder.asyncStates

        currState = afterLabelState
        stateBuilder = new AsyncStateBuilder(currState, symLookup)

      case _ =>
        checkForUnsupportedAwait(stat)
        stateBuilder += stat
    }
    // complete last state builder (representing the expressions after the last await)
    stateBuilder += expr
    val lastState = stateBuilder.resultSimple(endState)
    asyncStates += lastState
  }

  trait AsyncBlock {
    def asyncStates: List[AsyncState]

    def onCompleteHandler[T: WeakTypeTag]: Tree
  }

  case class SymLookup(stateMachineClass: Symbol, applyTrParam: Symbol) {
    def stateMachineMember(name: TermName): Symbol =
      stateMachineClass.info.member(name)
    def memberRef(name: TermName): Tree =
      gen.mkAttributedRef(stateMachineMember(name))
  }

  /**
   * Uses `AsyncBlockBuilder` to create an instance of `AsyncBlock`.
   *
   * @param  block      a `Block` tree in ANF
   * @param  symLookup  helper for looking up members of the state machine class
   * @return            an `AsyncBlock`
   */
  def buildAsyncBlock(block: Block, symLookup: SymLookup): AsyncBlock = {
    val Block(stats, expr) = block
    val startState = stateAssigner.nextState()
    val endState = Int.MaxValue

    val blockBuilder = new AsyncBlockBuilder(stats, expr, startState, endState, symLookup)

    new AsyncBlock {
      def asyncStates = blockBuilder.asyncStates.toList

      def mkCombinedHandlerCases[T: WeakTypeTag]: List[CaseDef] = {
        val caseForLastState: CaseDef = {
          val lastState = asyncStates.last
          val lastStateBody = Expr[T](lastState.body)
          val rhs = futureSystemOps.completeProm(
            Expr[futureSystem.Prom[T]](symLookup.memberRef(name.result)), futureSystemOps.tryySuccess[T](lastStateBody))
          mkHandlerCase(lastState.state, Block(rhs.tree, Return(literalUnit)))
        }
        asyncStates.toList match {
          case s :: Nil =>
            List(caseForLastState)
          case _        =>
            val initCases = for (state <- asyncStates.toList.init) yield state.mkHandlerCaseForState
            initCases :+ caseForLastState
        }
      }

      val initStates = asyncStates.init

      /**
       * Builds the definition of the `resume` method.
       *
       * The resulting tree has the following shape:
       *
       *     def resume(): Unit = {
       *       try {
       *         state match {
       *           case 0 => {
       *             f11 = exprReturningFuture
       *             f11.onComplete(onCompleteHandler)(context)
       *           }
       *           ...
       *         }
       *       } catch {
       *         case NonFatal(t) => result.failure(t)
       *       }
       *     }
       */
      private def resumeFunTree[T: WeakTypeTag]: Tree =
          Try(
            Match(symLookup.memberRef(name.state), mkCombinedHandlerCases[T] ++ initStates.flatMap(_.mkOnCompleteHandler[T]) ),
            List(
              CaseDef(
                Bind(name.t, Ident(nme.WILDCARD)),
                Apply(Ident(defn.NonFatalClass), List(Ident(name.t))), {
                  val t = Expr[Throwable](Ident(name.t))
                  val complete = futureSystemOps.completeProm[T](
                    Expr[futureSystem.Prom[T]](symLookup.memberRef(name.result)), futureSystemOps.tryyFailure[T](t)).tree
                  Block(complete :: Nil, Return(literalUnit))
                })), EmptyTree)

      def forever(t: Tree): Tree = {
        val labelName = name.fresh("while$")
        LabelDef(labelName, Nil, Block(t :: Nil, Apply(Ident(labelName), Nil)))
      }

      /**
       * Builds a `match` expression used as an onComplete handler.
       *
       * Assumes `tr: Try[Any]` is in scope. The resulting tree has the following shape:
       *
       *     state match {
       *       case 0 =>
       *         x11 = tr.get.asInstanceOf[Double]
       *         state = 1
       *         resume()
       *     }
       */
      def onCompleteHandler[T: WeakTypeTag]: Tree = {
        val onCompletes = initStates.flatMap(_.mkOnCompleteHandler[T]).toList
        forever {
          Block(resumeFunTree :: Nil, literalUnit)
        }
      }
    }
  }

  private def isSyntheticBindVal(tree: Tree) = tree match {
    case vd@ValDef(_, lname, _, Ident(rname)) => lname.toString.contains(name.bindSuffix)
    case _                                    => false
  }

  case class Awaitable(expr: Tree, resultName: Symbol, resultType: Type, resultValDef: ValDef)

  private def mkStateTree(nextState: Int, symLookup: SymLookup): Tree =
    Assign(symLookup.memberRef(name.state), Literal(Constant(nextState)))

  private def mkHandlerCase(num: Int, rhs: List[Tree]): CaseDef =
    mkHandlerCase(num, Block(rhs, literalUnit))

  private def mkHandlerCase(num: Int, rhs: Tree): CaseDef =
    CaseDef(Literal(Constant(num)), EmptyTree, rhs)

  def literalUnit = Literal(Constant(()))

  def literalNull = Literal(Constant(null))
}
