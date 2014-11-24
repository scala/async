/*
 * Copyright (C) 2012-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package scala.async.internal

import scala.reflect.macros.Context
import reflect.ClassTag
import scala.collection.immutable.ListMap

/**
 * Utilities used in both `ExprBuilder` and `AnfTransform`.
 */
private[async] trait TransformUtils {
  self: AsyncMacro =>

  import c.universe._
  import c.internal._
  import decorators._

  object name {
    val resume        = newTermName("resume")
    val apply         = newTermName("apply")
    val matchRes      = "matchres"
    val ifRes         = "ifres"
    val await         = "await"
    val bindSuffix    = "$bind"

    val state         = newTermName("state")
    val result        = newTermName("result")
    val execContext   = newTermName("execContext")
    val stateMachine  = newTermName(fresh("stateMachine"))
    val stateMachineT = stateMachine.toTypeName
    val tr            = newTermName("tr")
    val t             = newTermName("throwable")

    def fresh(name: TermName): TermName = c.freshName(name)

    def fresh(name: String): String = c.freshName(name)
  }

  def isAwait(fun: Tree) =
    fun.symbol == defn.Async_await

  // Copy pasted from TreeInfo in the compiler.
  // Using a quasiquote pattern like `case q"$fun[..$targs](...$args)" => is not
  // sufficient since https://github.com/scala/scala/pull/3656 as it doesn't match
  // constructor invocations.
  class Applied(val tree: Tree) {
    /** The tree stripped of the possibly nested applications.
     *  The original tree if it's not an application.
     */
    def callee: Tree = {
      def loop(tree: Tree): Tree = tree match {
        case Apply(fn, _) => loop(fn)
        case tree         => tree
      }
      loop(tree)
    }

    /** The `callee` unwrapped from type applications.
     *  The original `callee` if it's not a type application.
     */
    def core: Tree = callee match {
      case TypeApply(fn, _)       => fn
      case AppliedTypeTree(fn, _) => fn
      case tree                   => tree
    }

    /** The type arguments of the `callee`.
     *  `Nil` if the `callee` is not a type application.
     */
    def targs: List[Tree] = callee match {
      case TypeApply(_, args)       => args
      case AppliedTypeTree(_, args) => args
      case _                        => Nil
    }

    /** (Possibly multiple lists of) value arguments of an application.
     *  `Nil` if the `callee` is not an application.
     */
    def argss: List[List[Tree]] = {
      def loop(tree: Tree): List[List[Tree]] = tree match {
        case Apply(fn, args) => loop(fn) :+ args
        case _               => Nil
      }
      loop(tree)
    }
  }

  /** Returns a wrapper that knows how to destructure and analyze applications.
   */
  def dissectApplied(tree: Tree) = new Applied(tree)

  /** Destructures applications into important subparts described in `Applied` class,
   *  namely into: core, targs and argss (in the specified order).
   *
   *  Trees which are not applications are also accepted. Their callee and core will
   *  be equal to the input, while targs and argss will be Nil.
   *
   *  The provided extractors don't expose all the API of the `Applied` class.
   *  For advanced use, call `dissectApplied` explicitly and use its methods instead of pattern matching.
   */
  object Applied {
    def apply(tree: Tree): Applied = new Applied(tree)

    def unapply(applied: Applied): Option[(Tree, List[Tree], List[List[Tree]])] =
      Some((applied.core, applied.targs, applied.argss))

    def unapply(tree: Tree): Option[(Tree, List[Tree], List[List[Tree]])] =
      unapply(dissectApplied(tree))
  }
  private lazy val Boolean_ShortCircuits: Set[Symbol] = {
    import definitions.BooleanClass
    def BooleanTermMember(name: String) = BooleanClass.typeSignature.member(newTermName(name).encodedName)
    val Boolean_&& = BooleanTermMember("&&")
    val Boolean_|| = BooleanTermMember("||")
    Set(Boolean_&&, Boolean_||)
  }

  private def isByName(fun: Tree): ((Int, Int) => Boolean) = {
    if (Boolean_ShortCircuits contains fun.symbol) (i, j) => true
    else {
      val paramss = fun.tpe.paramss
      val byNamess = paramss.map(_.map(_.asTerm.isByNameParam))
      (i, j) => util.Try(byNamess(i)(j)).getOrElse(false)
    }
  }
  private def argName(fun: Tree): ((Int, Int) => String) = {
    val paramss = fun.tpe.paramss
    val namess = paramss.map(_.map(_.name.toString))
    (i, j) => util.Try(namess(i)(j)).getOrElse(s"arg_${i}_${j}")
  }

  object defn {
    def mkList_apply[A](args: List[Expr[A]]): Expr[List[A]] = {
      c.Expr(Apply(Ident(definitions.List_apply), args.map(_.tree)))
    }

    def mkList_contains[A](self: Expr[List[A]])(elem: Expr[Any]) = reify {
      self.splice.contains(elem.splice)
    }

    def mkFunction_apply[A, B](self: Expr[Function1[A, B]])(arg: Expr[A]) = reify {
      self.splice.apply(arg.splice)
    }

    def mkAny_==(self: Expr[Any])(other: Expr[Any]) = reify {
      self.splice == other.splice
    }

    def mkTry_get[A](self: Expr[util.Try[A]]) = reify {
      self.splice.get
    }

    val NonFatalClass = rootMirror.staticModule("scala.util.control.NonFatal")
    val Async_await   = asyncBase.awaitMethod(c.universe)(c.macroApplication.symbol).ensuring(_ != NoSymbol)
  }

  // `while(await(x))` ... or `do { await(x); ... } while(...)` contain an `If` that loops;
  // we must break that `If` into states so that it convert the label jump into a state machine
  // transition
  final def containsForiegnLabelJump(t: Tree): Boolean = {
    val labelDefs = t.collect {
      case ld: LabelDef => ld.symbol
    }.toSet
    t.exists {
      case rt: RefTree => rt.symbol != null && isLabel(rt.symbol) && !(labelDefs contains rt.symbol)
      case _ => false
    }
  }

  private def isLabel(sym: Symbol): Boolean = {
    val LABEL = 1L << 17 // not in the public reflection API.
    (internal.flags(sym).asInstanceOf[Long] & LABEL) != 0L
  }


  /** Map a list of arguments to:
    * - A list of argument Trees
    * - A list of auxillary results.
    *
    * The function unwraps and rewraps the `arg :_*` construct.
    *
    * @param args The original argument trees
    * @param f  A function from argument (with '_*' unwrapped) and argument index to argument.
    * @tparam A The type of the auxillary result
    */
  private def mapArguments[A](args: List[Tree])(f: (Tree, Int) => (A, Tree)): (List[A], List[Tree]) = {
    args match {
      case args :+ Typed(tree, Ident(tpnme.WILDCARD_STAR)) =>
        val (a, argExprs :+ lastArgExpr) = (args :+ tree).zipWithIndex.map(f.tupled).unzip
        val exprs = argExprs :+ atPos(lastArgExpr.pos.makeTransparent)(Typed(lastArgExpr, Ident(tpnme.WILDCARD_STAR)))
        (a, exprs)
      case args                                            =>
        args.zipWithIndex.map(f.tupled).unzip
    }
  }

  case class Arg(expr: Tree, isByName: Boolean, argName: String)

  /**
   * Transform a list of argument lists, producing the transformed lists, and lists of auxillary
   * results.
   *
   * The function `f` need not concern itself with varargs arguments e.g (`xs : _*`). It will
   * receive `xs`, and it's result will be re-wrapped as `f(xs) : _*`.
   *
   * @param fun   The function being applied
   * @param argss The argument lists
   * @return      (auxillary results, mapped argument trees)
   */
  def mapArgumentss[A](fun: Tree, argss: List[List[Tree]])(f: Arg => (A, Tree)): (List[List[A]], List[List[Tree]]) = {
    val isByNamess: (Int, Int) => Boolean = isByName(fun)
    val argNamess: (Int, Int) => String = argName(fun)
    argss.zipWithIndex.map { case (args, i) =>
      mapArguments[A](args) {
        (tree, j) => f(Arg(tree, isByNamess(i, j), argNamess(i, j)))
      }
    }.unzip
  }


  def statsAndExpr(tree: Tree): (List[Tree], Tree) = tree match {
    case Block(stats, expr) => (stats, expr)
    case _                  => (List(tree), Literal(Constant(())))
  }

  def emptyConstructor: DefDef = {
    val emptySuperCall = Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), Nil)
    DefDef(NoMods, nme.CONSTRUCTOR, List(), List(List()), TypeTree(), Block(List(emptySuperCall), Literal(Constant(()))))
  }

  def applied(className: String, types: List[Type]): AppliedTypeTree =
    AppliedTypeTree(Ident(rootMirror.staticClass(className)), types.map(TypeTree(_)))

  /** Descends into the regions of the tree that are subject to the
    * translation to a state machine by `async`. When a nested template,
    * function, or by-name argument is encountered, the descent stops,
    * and `nestedClass` etc are invoked.
    */
  trait AsyncTraverser extends Traverser {
    def nestedClass(classDef: ClassDef) {
    }

    def nestedModule(module: ModuleDef) {
    }

    def nestedMethod(defdef: DefDef) {
    }

    def byNameArgument(arg: Tree) {
    }

    def function(function: Function) {
    }

    def patMatFunction(tree: Match) {
    }

    override def traverse(tree: Tree) {
      tree match {
        case cd: ClassDef          => nestedClass(cd)
        case md: ModuleDef         => nestedModule(md)
        case dd: DefDef            => nestedMethod(dd)
        case fun: Function         => function(fun)
        case m@Match(EmptyTree, _) => patMatFunction(m) // Pattern matching anonymous function under -Xoldpatmat of after `restorePatternMatchingFunctions`
        case q"$fun[..$targs](...$argss)" if argss.nonEmpty =>
          val isInByName = isByName(fun)
          for ((args, i) <- argss.zipWithIndex) {
            for ((arg, j) <- args.zipWithIndex) {
              if (!isInByName(i, j)) traverse(arg)
              else byNameArgument(arg)
            }
          }
          traverse(fun)
        case _                     => super.traverse(tree)
      }
    }
  }

  def transformAt(tree: Tree)(f: PartialFunction[Tree, (TypingTransformApi => Tree)]) = {
    typingTransform(tree)((tree, api) => {
      if (f.isDefinedAt(tree)) f(tree)(api)
      else api.default(tree)
    })
  }

  def toMultiMap[A, B](as: Iterable[(A, B)]): Map[A, List[B]] =
    as.toList.groupBy(_._1).mapValues(_.map(_._2).toList).toMap

  // Attributed version of `TreeGen#mkCastPreservingAnnotations`
  def mkAttributedCastPreservingAnnotations(tree: Tree, tp: Type): Tree = {
    atPos(tree.pos) {
      val casted = c.typecheck(gen.mkCast(tree, uncheckedBounds(withoutAnnotations(tp)).dealias))
      Typed(casted, TypeTree(tp)).setType(tp)
    }
  }

  def deconst(tp: Type): Type = tp match {
    case AnnotatedType(anns, underlying) => annotatedType(anns, deconst(underlying))
    case ExistentialType(quants, underlying) => existentialType(quants, deconst(underlying))
    case ConstantType(value) => deconst(value.tpe)
    case _ => tp
  }

  def withAnnotation(tp: Type, ann: Annotation): Type = withAnnotations(tp, List(ann))

  def withAnnotations(tp: Type, anns: List[Annotation]): Type = tp match {
    case AnnotatedType(existingAnns, underlying) => annotatedType(anns ::: existingAnns, underlying)
    case ExistentialType(quants, underlying) => existentialType(quants, withAnnotations(underlying, anns))
    case _ => annotatedType(anns, tp)
  }

  def withoutAnnotations(tp: Type): Type = tp match {
    case AnnotatedType(anns, underlying) => withoutAnnotations(underlying)
    case ExistentialType(quants, underlying) => existentialType(quants, withoutAnnotations(underlying))
    case _ => tp
  }

  def tpe(sym: Symbol): Type = {
    if (sym.isType) sym.asType.toType
    else sym.info
  }

  def thisType(sym: Symbol): Type = {
    if (sym.isClass) sym.asClass.thisPrefix
    else NoPrefix
  }

  private def derivedValueClassUnbox(cls: Symbol) =
    (cls.info.decls.find(sym => sym.isMethod && sym.asTerm.isParamAccessor) getOrElse NoSymbol)

  def mkZero(tp: Type): Tree = {
    if (tp.typeSymbol.isClass && tp.typeSymbol.asClass.isDerivedValueClass) {
      val argZero = mkZero(derivedValueClassUnbox(tp.typeSymbol).infoIn(tp).resultType)
      val baseType = tp.baseType(tp.typeSymbol) // use base type here to dealias / strip phantom "tagged types" etc.

      // By explicitly attributing the types and symbols here, we subvert privacy.
      // Otherwise, ticket86PrivateValueClass would fail.

      // Approximately:
      // q"new ${valueClass}[$..targs](argZero)"
      val target: Tree = gen.mkAttributedSelect(
        c.typecheck(atMacroPos(
        New(TypeTree(baseType)))), tp.typeSymbol.asClass.primaryConstructor)

      val zero = gen.mkMethodCall(target, argZero :: Nil)

      // restore the original type which we might otherwise have weakened with `baseType` above
      gen.mkCast(zero, tp)
    } else {
      gen.mkZero(tp)
    }
  }

  // =====================================
  // Copy/Pasted from Scala 2.10.3. See SI-7694.
  private lazy val UncheckedBoundsClass = {
    try c.mirror.staticClass("scala.reflect.internal.annotations.uncheckedBounds")
    catch { case _: ScalaReflectionException => NoSymbol }
  }
  final def uncheckedBounds(tp: Type): Type = {
    if (tp.typeArgs.isEmpty || UncheckedBoundsClass == NoSymbol) tp
    else withAnnotation(tp, Annotation(UncheckedBoundsClass.asType.toType, Nil, ListMap()))
  }
  // =====================================
}
