package foo

class Foo {
  private[foo] def bar(x: Int, y: Int) = x + y
}

object Foo {
  private[foo] def bar(x: Int, y: Int) = x + y
}

object Lib {
  val foo  = new Foo
  def doIt = foo.bar(1, 0) + Foo.bar(1, 0)
}
