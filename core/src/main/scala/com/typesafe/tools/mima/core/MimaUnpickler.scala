package com.typesafe.tools.mima.core

import PickleFormat._

object MimaUnpickler {
  def unpickleClass(buf: PickleBuffer, clazz: ClassInfo, path: String) = {
    buf.readNat(); buf.readNat() // major, minor version

    val index    = buf.createIndex
    val classes  = new Array[ClassInfo](index.length)
    val     syms = new Array[SymbolInfo](index.length)
    def   nnSyms = syms.iterator.zipWithIndex.filter(_._1 != null)
    def defnSyms = nnSyms.filter { case (sym, _) => sym.tag == CLASSsym || sym.tag == MODULEsym }
    def methSyms = nnSyms.filter { case (sym, _) => sym.tag == VALsym }
    val entries  = PickleEntries(buf.toIndexedSeq.zipWithIndex.map { case ((tag, data), num) =>
      PickleEntry(num, index(num), tag, data)
    })

    // SymbolInfo = name_Ref owner_Ref flags_LongNat [privateWithin_Ref] info_Ref
    def readSymbol(): SymbolInfo = {
      val tag   = buf.lastByte()
      val end   = buf.readNat() + buf.readIndex
      val name  = entries.nameAt(buf.readNat())
      val owner = buf.readNat()
      val flags = buf.readLongNat()
      buf.readNat()     // privateWithin or symbol info (compare to end)
      val isScopedPrivate = buf.readIndex != end
      buf.readIndex = end
      SymbolInfo(tag, name, owner, flags, isScopedPrivate)
    }

    def symbolToClass(symbolInfo: SymbolInfo) = {
      if (symbolInfo.name == REFINE_CLASS_NAME) {
        // eg: CLASSsym 4: 89(<refinement>) 0 0[] 87
        // The UnPickler in the compiler also excludes these with "isRefinementSymbolEntry"
        NoClass
      } else if (symbolInfo.name == "<local child>") {
        // Predef$$less$colon$less$<local child>
        NoClass
      } else {
        val own = classes(symbolInfo.owner)
        def withOwner(cls: ClassInfo) = {
          val nme1 = cls.bytecodeName
          val nme2 = symbolInfo.name
          val conc = if (nme1.endsWith("$")) "" else "$"
          val suff = if (symbolInfo.isModuleOrModuleClass) "$" else ""
          val name = nme1 + conc + nme2 + suff
          clazz.owner.classes(name)
        }
        val fallback = if (symbolInfo.isModuleOrModuleClass) clazz.moduleClass else clazz
        own match {
          case null if symbolInfo.owner == 0 => withOwner(fallback)
          case null                          => fallback
          case cls                           => withOwner(cls)
        }
      }
    }

    def doMethods(clazz: ClassInfo, methods: List[SymbolInfo]) = {
      methods.iterator
        .filter(!_.isParam)
        .filter(_.name != "<init>") // TODO support package private constructors
        .toSeq.groupBy(_.name).foreach { case (name, overloads) =>
          val methods = clazz.methods.get(name).filter(!_.isBridge).toList
          if (methods.nonEmpty && overloads.exists(_.isScopedPrivate)) {
            assert(overloads.size == methods.size, s"method overloads mismatch; bytecode=$methods pickle=$overloads for ${clazz.description}")
            methods.zip(overloads).foreach { case (method, symbolInfo) =>
              method.scopedPrivate = symbolInfo.isScopedPrivate
            }
          }
      }
    }


    for (num <- index.indices) {
      buf.runAtIndex(index(num)) {
        val tag = buf.readByte()
        if (tag == CLASSsym || tag == MODULEsym || tag == VALsym)
          syms(num) = readSymbol()
      }
    }

    for ((sym, num) <- defnSyms)
      classes(num) = symbolToClass(sym)

    for ((clsSym, num) <- defnSyms) {
      val clazz = classes(num)
      if (clsSym.isScopedPrivate)
        clazz.module._scopedPrivate = true
      val methods = methSyms.collect { case (sym, _) if sym.owner == num => sym }
      doMethods(clazz, methods.toList)
    }
  }

  final case class SymbolInfo(tag: Int, name: String, owner: Int, flags: Long, isScopedPrivate: Boolean) {
    def hasFlag(flag: Long): Boolean = (flags & flag) != 0L
    def isModuleOrModuleClass        = hasFlag(Flags.MODULE_PKL)
    def isModuleClass                = isModuleOrModuleClass && tag == CLASSsym
    def isModule                     = isModuleOrModuleClass && tag == MODULEsym
    def isParam                      = hasFlag(Flags.PARAM)
    def mcmc                         = if (isModuleClass) "MC" else if (isModule) "M" else "C"
    override def toString = s"SymbolInfo(${tag2string(tag)}, $name, owner=$owner, isScopedPrivate=$isScopedPrivate)"
  }

  final case class PickleEntry(num: Int, startIndex: Int, tag: Int, bytes: Array[Byte]) {
    override def toString = s"$num,$startIndex: ${tag2string(tag)}"
  }

  final case class PickleEntries(entries: IndexedSeq[PickleEntry]) {
    def nameAt(idx: Int) = {
      val entry = entries(idx)
      def readStr()    = new String(entry.bytes, "UTF-8")
      def readStrRef() = new String(entries(readNat(entry.bytes)).bytes, "UTF-8")
      entry.tag match {
        case TERMname       => readStr()
        case TYPEname       => readStr()
        case   TYPEsym      => readStrRef()
        case  ALIASsym      => readStrRef()
        case  CLASSsym      => readStrRef()
        case MODULEsym      => readStrRef()
        case    VALsym      => readStrRef()
        case         EXTref => readStrRef()
        case EXTMODCLASSref => readStrRef()
        case _              => "?"
      }
    }
  }

  def readNat(data: Array[Byte]): Int = {
    var idx = 0
    var res = 0L
    var b   = 0L
    do {
      b    = data(idx).toLong
      idx += 1
      res  = (res << 7) + (b & 0x7f)
    } while ((b & 0x80) != 0L)
    res.toInt
  }

  object Flags {
    final val MODULE_PKL = 1L << 10
    final val PARAM      = 1L << 13
  }

  final val REFINE_CLASS_NAME = "<refinement>"
}
