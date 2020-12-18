package foo

// All eight combinations of class/object nesting to three levels.
// O-O-O means object { object { object } }
// C-C-C means class  { class  { class  } }
// etc..
// Because of scala/bug#2034 we can't put these all in one package (you get "name clash" errors)
// So instead we'll split them in 4 nice and even packages
package l1  { object x { private[foo] def go() = 1 }; class x { private[foo] def go() = 1 } }

package l2a { object x { object y { private[foo] def go() = 2 }; class y { private[foo] def go() = 2 } } }
package l2b { class  x { object y { private[foo] def go() = 2 }; class y { private[foo] def go() = 2 } } }

package l3a { object x { object y { object z { private[foo] def go() = 3 }; class z { private[foo] def go() = 3 } } } }
package l3b { object x { class  y { object z { private[foo] def go() = 3 }; class z { private[foo] def go() = 3 } } } }
package l3c { class  x { object y { object z { private[foo] def go() = 3 }; class z { private[foo] def go() = 3 } } } }
package l3d { class  x { class  y { object z { private[foo] def go() = 3 }; class z { private[foo] def go() = 3 } } } }

object Lib {
  def doIt = { doL1(); doL2(); doL3() }

  def doL1() = {
    val o =     l1.x
    val c = new l1.x()

    o.go()
    c.go()
  }

  def doL2() = {
    val o =     l2a.x
    val c = new l2b.x()

    val oo =     o.y
    val oc = new o.y()
    val co =     c.y
    val cc = new c.y()

    oo.go()
    oc.go()
    co.go()
    cc.go()
  }

  def doL3() = {
    val o1 =     l3a.x
    val o2 =     l3b.x
    val c3 = new l3c.x()
    val c4 = new l3d.x()

    val oo =     o1.y
    val oc = new o2.y()
    val co =     c3.y
    val cc = new c4.y()

    val ooo =     oo.z
    val ooc = new oo.z()
    val oco =     oc.z
    val occ = new oc.z()
    val coo =     co.z
    val coc = new co.z()
    val cco =     cc.z
    val ccc = new cc.z()

    ooo.go()
    ooc.go()
    oco.go()
    occ.go()
    coo.go()
    coc.go()
    cco.go()
    ccc.go()
  }
}
