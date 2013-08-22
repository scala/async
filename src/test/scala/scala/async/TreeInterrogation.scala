/*
 * Copyright (C) 2012 Typesafe Inc. <http://www.typesafe.com>
 */

package scala.async

import org.junit.Test
import scala.async.internal.AsyncId
import AsyncId._
import tools.reflect.ToolBox

class TreeInterrogation {
  @Test
  def `a minimal set of vals are lifted to vars`() {
    val cm = reflect.runtime.currentMirror
    val tb = mkToolbox(s"-cp ${toolboxClasspath}")
    val tree = tb.parse(
      """| import _root_.scala.async.internal.AsyncId._
        | async {
        |   val x = await(1)
        |   val y = x * 2
        |   def foo(a: Int) = { def nested = 0; a } // don't lift `nested`.
        |   val z = await(x * 3)
        |   foo(z)
        |   z
        | }""".stripMargin)
    val tree1 = tb.typeCheck(tree)

    //println(cm.universe.show(tree1))

    import tb.u._
    val functions = tree1.collect {
      case f: Function => f
      case t: Template => t
    }
    functions.size mustBe 1

    val varDefs = tree1.collect {
      case ValDef(mods, name, _, _) if mods.hasFlag(Flag.MUTABLE) => name
    }
    varDefs.map(_.decoded.trim).toSet mustBe (Set("state", "await$1$1", "await$2$1"))

    val defDefs = tree1.collect {
      case t: Template =>
        val stats: List[Tree] = t.body
        stats.collect {
          case dd : DefDef
            if !dd.symbol.isImplementationArtifact
              && !dd.symbol.asTerm.isAccessor && !dd.symbol.asTerm.isSetter => dd.name
        }
    }.flatten
    defDefs.map(_.decoded.trim).toSet mustBe (Set("foo$1", "apply", "resume", "<init>"))
  }
}

object TreeInterrogation extends App {
  def withDebug[T](t: => T) {
    def set(level: String, value: Boolean) = System.setProperty(s"scala.async.$level", value.toString)
    val levels = Seq("trace", "debug")
    def setAll(value: Boolean) = levels.foreach(set(_, value))

    setAll(value = true)
    try t finally setAll(value = false)
  }

  withDebug {
    val cm = reflect.runtime.currentMirror
    val tb = mkToolbox("-cp ${toolboxClasspath} -Xprint:typer -uniqid")
    import scala.async.Async._
    val tree = tb.parse(
      """ import _root_.scala.async.internal.AsyncId.{async, await}
        | async {
        |   implicit def view(a: Int): String = ""
        |   await(0).length
        | }
        | """.stripMargin)
    println(tree)
    val tree1 = tb.typeCheck(tree.duplicate)
    println(cm.universe.show(tree1))
    println(tb.eval(tree))
  }
}
