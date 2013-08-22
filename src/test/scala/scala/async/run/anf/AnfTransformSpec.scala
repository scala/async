/*
 * Copyright (C) 2012 Typesafe Inc. <http://www.typesafe.com>
 */

package scala.async
package run
package anf

import language.{reflectiveCalls, postfixOps}
import scala.concurrent.{Future, ExecutionContext, future, Await}
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import org.junit.Test
import scala.async.internal.AsyncId


class AnfTestClass {

  import ExecutionContext.Implicits.global

  def base(x: Int): Future[Int] = future {
    x + 2
  }

  def m(y: Int): Future[Int] = async {
    val blerg = base(y)
    await(blerg)
  }

  def m2(y: Int): Future[Int] = async {
    val f = base(y)
    val f2 = base(y + 1)
    await(f) + await(f2)
  }

  def m3(y: Int): Future[Int] = async {
    val f = base(y)
    var z = 0
    if (y > 0) {
      z = await(f) + 2
    } else {
      z = await(f) - 2
    }
    z
  }

  def m4(y: Int): Future[Int] = async {
    val f = base(y)
    val z = if (y > 0) {
      await(f) + 2
    } else {
      await(f) - 2
    }
    z + 1
  }

  def futureUnitIfElse(y: Int): Future[Unit] = async {
    val f = base(y)
    if (y > 0) {
      State.result = await(f) + 2
    } else {
      State.result = await(f) - 2
    }
  }
}

object State {
  @volatile var result: Int = 0
}

class AnfTransformSpec {

  @Test
  def `simple ANF transform`() {
    val o = new AnfTestClass
    val fut = o.m(10)
    val res = Await.result(fut, 2 seconds)
    res mustBe (12)
  }

  @Test
  def `simple ANF transform 2`() {
    val o = new AnfTestClass
    val fut = o.m2(10)
    val res = Await.result(fut, 2 seconds)
    res mustBe (25)
  }

  @Test
  def `simple ANF transform 3`() {
    val o = new AnfTestClass
    val fut = o.m3(10)
    val res = Await.result(fut, 2 seconds)
    res mustBe (14)
  }

  @Test
  def `ANF transform of assigning the result of an if-else`() {
    val o = new AnfTestClass
    val fut = o.m4(10)
    val res = Await.result(fut, 2 seconds)
    res mustBe (15)
  }

  @Test
  def `Unit-typed if-else in tail position`() {
    val o = new AnfTestClass
    val fut = o.futureUnitIfElse(10)
    Await.result(fut, 2 seconds)
    State.result mustBe (14)
  }

  @Test
  def `inlining block does not produce duplicate definition`() {
    AsyncId.async {
      val f = 12
      val x = AsyncId.await(f)

      {
        type X = Int
        val x: X = 42
        println(x)
      }
      type X = Int
      x: X
    }
  }

  @Test
  def `inlining block in tail position does not produce duplicate definition`() {
    AsyncId.async {
      val f = 12
      val x = AsyncId.await(f)

      {
        val x = 42
        x
      }
    } mustBe (42)
  }

  @Test
  def `match as expression 1`() {
    import ExecutionContext.Implicits.global
    val result = AsyncId.async {
      val x = "" match {
        case _ => AsyncId.await(1) + 1
      }
      x
    }
    result mustBe (2)
  }

  @Test
  def `match as expression 2`() {
    import ExecutionContext.Implicits.global
    val result = AsyncId.async {
      val x = "" match {
        case "" if false => AsyncId.await(1) + 1
        case _           => 2 + AsyncId.await(1)
      }
      val y = x
      "" match {
        case _ => AsyncId.await(y) + 100
      }
    }
    result mustBe (103)
  }

  @Test
  def nestedAwaitAsBareExpression() {
    import ExecutionContext.Implicits.global
    import AsyncId.{async, await}
    val result = async {
      await(await("").isEmpty)
    }
    result mustBe (true)
  }

  @Test
  def nestedAwaitInBlock() {
    import ExecutionContext.Implicits.global
    import AsyncId.{async, await}
    val result = async {
      ()
      await(await("").isEmpty)
    }
    result mustBe (true)
  }

  @Test
  def nestedAwaitInIf() {
    import ExecutionContext.Implicits.global
    import AsyncId.{async, await}
    val result = async {
      if ("".isEmpty)
        await(await("").isEmpty)
      else 0
    }
    result mustBe (true)
  }

  @Test
  def byNameExpressionsArentLifted() {
    import AsyncId.{async, await}
    def foo(ignored: => Any, b: Int) = b
    val result = async {
      foo(???, await(1))
    }
    result mustBe (1)
  }

  @Test
  def evaluationOrderRespected() {
    import AsyncId.{async, await}
    def foo(a: Int, b: Int) = (a, b)
    val result = async {
      var i = 0
      def next() = {
        i += 1;
        i
      }
      foo(next(), await(next()))
    }
    result mustBe ((1, 2))
  }

  @Test
  def awaitInNonPrimaryParamSection1() {
    import AsyncId.{async, await}
    def foo(a0: Int)(b0: Int) = s"a0 = $a0, b0 = $b0"
    val res = async {
      var i = 0
      def get = {i += 1; i}
      foo(get)(await(get))
    }
    res mustBe "a0 = 1, b0 = 2"
  }

  @Test
  def awaitInNonPrimaryParamSection2() {
    import AsyncId.{async, await}
    def foo[T](a0: Int)(b0: Int*) = s"a0 = $a0, b0 = ${b0.head}"
    val res = async {
      var i = 0
      def get = async {i += 1; i}
      foo[Int](await(get))(await(get) :: await(async(Nil)) : _*)
    }
    res mustBe "a0 = 1, b0 = 2"
  }

  @Test
  def awaitInNonPrimaryParamSectionWithLazy1() {
    import AsyncId.{async, await}
    def foo[T](a: => Int)(b: Int) = b
    val res = async {
      def get = async {0}
      foo[Int](???)(await(get))
    }
    res mustBe 0
  }

  @Test
  def awaitInNonPrimaryParamSectionWithLazy2() {
    import AsyncId.{async, await}
    def foo[T](a: Int)(b: => Int) = a
    val res = async {
      def get = async {0}
      foo[Int](await(get))(???)
    }
    res mustBe 0
  }

  @Test
  def awaitWithLazy() {
    import AsyncId.{async, await}
    def foo[T](a: Int, b: => Int) = a
    val res = async {
      def get = async {0}
      foo[Int](await(get), ???)
    }
    res mustBe 0
  }

  @Test
  def awaitOkInReciever() {
    import AsyncId.{async, await}
    class Foo { def bar(a: Int)(b: Int) = a + b }
    async {
      await(async(new Foo)).bar(1)(2)
    }
  }

  @Test
  def namedArgumentsRespectEvaluationOrder() {
    import AsyncId.{async, await}
    def foo(a: Int, b: Int) = (a, b)
    val result = async {
      var i = 0
      def next() = {
        i += 1;
        i
      }
      foo(b = next(), a = await(next()))
    }
    result mustBe ((2, 1))
  }

  @Test
  def namedAndDefaultArgumentsRespectEvaluationOrder() {
    import AsyncId.{async, await}
    var i = 0
    def next() = {
      i += 1;
      i
    }
    def foo(a: Int = next(), b: Int = next()) = (a, b)
    async {
      foo(b = await(next()))
    } mustBe ((2, 1))
    i = 0
    async {
      foo(a = await(next()))
    } mustBe ((1, 2))
  }

  @Test
  def repeatedParams1() {
    import AsyncId.{async, await}
    var i = 0
    def foo(a: Int, b: Int*) = b.toList
    def id(i: Int) = i
    async {
      foo(await(0), id(1), id(2), id(3), await(4))
    } mustBe (List(1, 2, 3, 4))
  }

  @Test
  def repeatedParams2() {
    import AsyncId.{async, await}
    var i = 0
    def foo(a: Int, b: Int*) = b.toList
    def id(i: Int) = i
    async {
      foo(await(0), List(id(1), id(2), id(3)): _*)
    } mustBe (List(1, 2, 3))
  }

  @Test
  def awaitInThrow() {
    import _root_.scala.async.internal.AsyncId.{async, await}
    intercept[Exception](
      async {
        throw new Exception("msg: " + await(0))
      }
    ).getMessage mustBe "msg: 0"
  }

  @Test
  def awaitInTyped() {
    import _root_.scala.async.internal.AsyncId.{async, await}
    async {
      (("msg: " + await(0)): String).toString
    } mustBe "msg: 0"
  }


  @Test
  def awaitInAssign() {
    import _root_.scala.async.internal.AsyncId.{async, await}
    async {
      var x = 0
      x = await(1)
      x
    } mustBe 1
  }

  @Test
  def caseBodyMustBeTypedAsUnit() {
    import _root_.scala.async.internal.AsyncId.{async, await}
    val Up = 1
    val Down = 2
    val sign = async {
      await(1) match {
        case Up   => 1.0
        case Down => -1.0
      }
    }
    sign mustBe 1.0
  }

  @Test
  def awaitInImplicitApply() {
    val tb = mkToolbox(s"-cp ${toolboxClasspath}")
    val tree = tb.typeCheck(tb.parse {
      """
        | import language.implicitConversions
        | import _root_.scala.async.internal.AsyncId.{async, await}
        | implicit def view(a: Int): String = ""
        | async {
        |   await(0).length
        | }
      """.stripMargin
    })
    val applyImplicitView = tree.collect { case x if x.getClass.getName.endsWith("ApplyImplicitView") => x }
    applyImplicitView.map(_.toString) mustBe List("view(a$1)")
  }
}
