package ssol.tools.mima.analyze

import ssol.tools.mima.{ Problem, MissingMethodProblem, IncompatibleTemplateDefProblem, CyclicTypeReferenceProblem, ClassInfo }
import ssol.tools.mima.analyze.field.BaseFieldChecker
import ssol.tools.mima.analyze.method.BaseMethodChecker
import ssol.tools.mima.analyze.template.TemplateChecker

object Analyzer {
  def apply(oldclz: ClassInfo, newclz: ClassInfo): List[Problem] = {
    if(oldclz.isClass && newclz.isClass) ClassAnalyzer(oldclz, newclz)
    else TraitAnalyzer(oldclz,newclz)
  }
}

private[analyze] trait Analyzer extends Function2[ClassInfo, ClassInfo, List[Problem]]{

  implicit def option2list(v: Option[Problem]): List[Problem] = v match {
    case None    => Nil
    case Some(p) => List(p)
  }

  implicit def listOfOption2list(xs: List[Option[Problem]]): List[Problem] =
    xs collect { case Some(p) => p }

  protected val fieldChecker: BaseFieldChecker
  protected val methodChecker: BaseMethodChecker

  def apply(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] = 
    analyze(oldclazz, newclazz)
  
  def analyze(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] = {
    assert(oldclazz.name == newclazz.name)
    val templateProblems = analyzeTemplateDecl(oldclazz, newclazz) 
    
    if(templateProblems.exists(p => p.isInstanceOf[IncompatibleTemplateDefProblem] || 
    								p.isInstanceOf[CyclicTypeReferenceProblem]))
      templateProblems // IncompatibleTemplateDefProblem implies major incompatibility, does not make sense to continue
    else 
      templateProblems ::: analyzeMembers(oldclazz, newclazz)
  }

  def analyzeTemplateDecl(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] =
    TemplateChecker(oldclazz, newclazz)

  def analyzeMembers(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] =
    analyzeFields(oldclazz, newclazz) ::: analyzeMethods(oldclazz, newclazz)

  def analyzeFields(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] = {
    for (oldfld <- oldclazz.fields.iterator.toList) yield fieldChecker.check(oldfld, newclazz)
  }

  def analyzeMethods(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] =
    analyzeOldClassMethods(oldclazz, newclazz) ::: analyzeNewClassMethods(oldclazz, newclazz)

  /** Analyze incompatibilities that may derive from methods in the `oldclazz` that are no longer 
   * declared in `newclazz`*/
  def analyzeOldClassMethods(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] = {
    for (oldmeth <- oldclazz.methods.iterator.toList) yield methodChecker.check(oldmeth, newclazz)
  }

  /** Analyze incompatibilities that may derive from methods added in the `newclazz` */
  def analyzeNewClassMethods(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] = {
    for (newAbstrMeth <- newclazz.deferredMethods) yield methodChecker.check(newAbstrMeth, newclazz) match {
      case Some(_) =>
        val p = MissingMethodProblem(newAbstrMeth)
        p.affectedVersion = Problem.ClassVersion.Old
        p.status = Problem.Status.Upgradable
        Some(p)
      case none => none
    }
  }
}

private[analyze] object ClassAnalyzer extends Analyzer {
  import ssol.tools.mima.analyze.field.ClassFieldChecker
  import ssol.tools.mima.analyze.method.ClassMethodChecker
  protected val fieldChecker = ClassFieldChecker
  protected val methodChecker = ClassMethodChecker
  
  override def analyze(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] = {
    if (oldclazz.isImplClass)  
      Nil // do not analyze trait's implementation classes
    else
      super.analyze(oldclazz,newclazz)
  }
}

private[analyze] object TraitAnalyzer extends Analyzer {
  import ssol.tools.mima.analyze.field.ClassFieldChecker
  import ssol.tools.mima.analyze.method.TraitMethodChecker
  protected val fieldChecker = ClassFieldChecker
  protected val methodChecker = TraitMethodChecker
  
  override def analyzeNewClassMethods(oldclazz: ClassInfo, newclazz: ClassInfo): List[Problem] = {
    val res = collection.mutable.ListBuffer.empty[Problem]
    
    for (newmeth <- newclazz.concreteMethods if !oldclazz.hasStaticImpl(newmeth))  {
      if (!oldclazz.lookupMethods(newmeth.name).exists(_.sig == newmeth.sig)) {
        // this means that the method is brand new and therefore the implementation 
        // has to be injected 
        val problem = MissingMethodProblem(newmeth)
        problem.affectedVersion = Problem.ClassVersion.Old
        problem.status = Problem.Status.Upgradable
        res += problem
      }
      // else a static implementation for the same method existed already, therefore 
      // class that mixed-in the trait already have a forwarder to the implementation 
      // class. Mind that, despite no binary incompatibility arises, program's 
      // semantic may be severely affected.
    }
    
    for(newmeth <- newclazz.deferredMethods) {
      val oldmeths = oldclazz.lookupMethods(newmeth.name)
      oldmeths find (_.sig == newmeth.sig) match {
        case Some(oldmeth) => ()
        case _ =>
          val problem = MissingMethodProblem(newmeth)
          problem.status = Problem.Status.Upgradable
          problem.affectedVersion = Problem.ClassVersion.Old
          res += problem
      }
    }
    
    res.toList 
  }
}