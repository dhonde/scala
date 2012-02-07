/* NSC -- new Scala compiler
 * Copyright 2005-2011 LAMP/EPFL
 * @author
 */

package scala.tools.nsc
package transform

import symtab._
import Flags._
import util.TreeSet
import scala.collection.{ mutable, immutable }
import scala.collection.mutable.LinkedHashMap

abstract class LambdaLift extends InfoTransform {
  import global._
  import definitions._

  /** the following two members override abstract members in Transform */
  val phaseName: String = "lambdalift"
    
  /** Converts types of captured variables to *Ref types.
   */
  def boxIfCaptured(sym: Symbol, tpe: Type, erasedTypes: Boolean) =
    if (sym.isCapturedVariable) {
      val symClass = tpe.typeSymbol
      def refType(valueRef: Map[Symbol, Symbol], objectRefClass: Symbol) =
        if (isPrimitiveValueClass(symClass) && symClass != UnitClass) valueRef(symClass).tpe
        else if (erasedTypes) objectRefClass.tpe
        else appliedType(objectRefClass.typeConstructor, List(tpe))
      if (sym.hasAnnotation(VolatileAttr)) refType(volatileRefClass, VolatileObjectRefClass)
      else refType(refClass, ObjectRefClass)
    } else tpe

  private val lifted = new TypeMap {
    def apply(tp: Type): Type = tp match {
      case TypeRef(NoPrefix, sym, Nil) if sym.isClass && !sym.isPackageClass =>
        typeRef(apply(sym.owner.enclClass.thisType), sym, Nil)
      case ClassInfoType(parents, decls, clazz) =>
        val parents1 = parents mapConserve this
        if (parents1 eq parents) tp
        else ClassInfoType(parents1, decls, clazz)
      case _ =>
        mapOver(tp)
    }
  }

  def transformInfo(sym: Symbol, tp: Type): Type =
    boxIfCaptured(sym, lifted(tp), erasedTypes = true)

  protected def newTransformer(unit: CompilationUnit): Transformer =
    new LambdaLifter(unit)

  class LambdaLifter(unit: CompilationUnit) extends explicitOuter.OuterPathTransformer(unit) {

    /** A map storing free variables of functions and classes */
    private val free = new LinkedHashMap[Symbol, SymSet]

    /** A map storing the free variable proxies of functions and classes */
    private val proxies = new LinkedHashMap[Symbol, List[Symbol]]

    /** A hashtable storing calls between functions */
    private val called = new LinkedHashMap[Symbol, SymSet]

    /** The set of symbols that need to be renamed. */
    private val renamable = newSymSet

    private val renamableImplClasses = mutable.HashMap[Name, Symbol]() withDefaultValue NoSymbol

    /** A flag to indicate whether new free variables have been found */
    private var changedFreeVars: Boolean = _

    /** Buffers for lifted out classes and methods */
    private val liftedDefs = new LinkedHashMap[Symbol, List[Tree]]
    
    /** True if we are transforming under a ReferenceToBoxed node */
    private var isBoxedRef = false
    
    private type SymSet = TreeSet[Symbol]

    private def newSymSet = new TreeSet[Symbol](_ isLess _)

    private def symSet(f: LinkedHashMap[Symbol, SymSet], sym: Symbol): SymSet =
      f.getOrElseUpdate(sym, newSymSet)

    private def isSameOwnerEnclosure(sym: Symbol) =
      sym.owner.logicallyEnclosingMember == currentOwner.logicallyEnclosingMember

    /** Mark symbol `sym` as being free in `enclosure`, unless `sym`
     *  is defined in `enclosure` or there is a class between `enclosure`s owner
     *  and the owner of `sym`.
     *  Return `true` if there is no class between `enclosure` and
     *  the owner of sym.
     *  pre: sym.isLocal, (enclosure.isMethod || enclosure.isClass)
     *
     *  The idea of `markFree` is illustrated with an example:
     *
     *  def f(x: int) = {
     *    class C {
     *      class D {
     *        val y = x
     *      }
     *    }
     *  }
     *
     *  In this case `x` is free in the primary constructor of class `C`.
     *  but it is not free in `D`, because after lambda lift the code would be transformed
     *  as follows:
     *
     *  def f(x$0: int) {
     *    class C(x$0: int) {
     *      val x$1 = x$0
     *      class D {
     *        val y = outer.x$1
     *      }
     *    }
     *  }
     */
    private def markFree(sym: Symbol, enclosure: Symbol): Boolean = {
      debuglog("mark free: " + sym + " of " + sym.owner + " marked free in " + enclosure)
      if (enclosure == sym.owner.logicallyEnclosingMember) true
      else if (enclosure.isPackageClass || !markFree(sym, enclosure.skipConstructor.owner.logicallyEnclosingMember)) false
      else {
        val ss = symSet(free, enclosure)
        if (!ss(sym)) {
          ss addEntry sym
          renamable addEntry sym
          atPhase(currentRun.picklerPhase) {
            // The param symbol in the MethodType should not be renamed, only the symbol in scope. This way,
            // parameter names for named arguments are not changed. Example: without cloning the MethodType,
            //     def closure(x: Int) = { () => x }
            // would have the signature
            //     closure: (x$1: Int)() => Int
            if (sym.isParameter && sym.owner.info.paramss.exists(_ contains sym))
              sym.owner modifyInfo (_ cloneInfo sym.owner)
          }
          changedFreeVars = true
          debuglog("" + sym + " is free in " + enclosure);
          if (sym.isVariable) sym setFlag CAPTURED
        }
        !enclosure.isClass
      }
    }

    private def markCalled(sym: Symbol, owner: Symbol) {
      debuglog("mark called: " + sym + " of " + sym.owner + " is called by " + owner)
      symSet(called, owner) addEntry sym
    }

    /** The traverse function */
    private val freeVarTraverser = new Traverser {
      override def traverse(tree: Tree) {
       try { //debug
        val sym = tree.symbol;
        tree match {
          case ClassDef(_, _, _, _) =>
            liftedDefs(tree.symbol) = Nil
            if (sym.isLocal) {
              // Don't rename implementation classes independently of their interfaces. If
              // the interface is to be renamed, then we will rename the implementation
              // class at that time. You'd think we could call ".implClass" on the trait
              // rather than collecting them in another map, but that seems to fail for
              // exactly the traits being renamed here (i.e. defined in methods.)
              //
              // !!! - it makes no sense to have methods like "implClass" and
              // "companionClass" which fail for an arbitrary subset of nesting
              // arrangements, and then have separate methods which attempt to compensate
              // for that failure. There should be exactly one method for any given
              // entity which always gives the right answer.
              if (sym.isImplClass) renamableImplClasses(nme.interfaceName(sym.name)) = sym
              else renamable addEntry sym
            }
          case DefDef(_, _, _, _, _, _) =>
            if (sym.isLocal) {
              renamable addEntry sym
              sym setFlag (PrivateLocal | FINAL)
            } else if (sym.isPrimaryConstructor) {
              symSet(called, sym) addEntry sym.owner
            }
          case Ident(name) =>
            if (sym == NoSymbol) {
              assert(name == nme.WILDCARD)
            } else if (sym.isLocal) {
              val owner = currentOwner.logicallyEnclosingMember
              if (sym.isTerm && !sym.isMethod) markFree(sym, owner)
              else if (sym.isMethod) markCalled(sym, owner)
                //symSet(called, owner) addEntry sym
            }
          case Select(_, _) =>
            if (sym.isConstructor && sym.owner.isLocal)
              markCalled(sym, currentOwner.logicallyEnclosingMember)
          case _ =>
        }
        super.traverse(tree)
       } catch {//debug
         case ex: Throwable =>
           Console.println("exception when traversing " + tree)
           throw ex
       }
      }
    }

    /** Compute free variables map `fvs`.
     *  Also assign unique names to all
     *  value/variable/let that are free in some function or class, and to
     *  all class/function symbols that are owned by some function.
     */
    private def computeFreeVars() {
      freeVarTraverser.traverse(unit.body)

      do {
        changedFreeVars = false
        for (caller <- called.keys ; callee <- called(caller) ; fvs <- free get callee ; fv <- fvs)
          markFree(fv, caller)
      } while (changedFreeVars)
      
      def renameSym(sym: Symbol) {
        val originalName = sym.name
        val base = sym.name + nme.NAME_JOIN_STRING + (
          if (sym.isAnonymousFunction && sym.owner.isMethod)
            sym.owner.name + nme.NAME_JOIN_STRING
          else ""
        )
        sym.name =
          if (sym.name.isTypeName) unit.freshTypeName(base)
          else unit.freshTermName(base)

        debuglog("renaming in %s: %s => %s".format(sym.owner.fullLocationString, originalName, sym.name))
      }

      /** Rename a trait's interface and implementation class in coordinated fashion.
       */
      def renameTrait(traitSym: Symbol, implSym: Symbol) {
        val originalImplName = implSym.name
        renameSym(traitSym)
        implSym.name = nme.implClassName(traitSym.name)

        debuglog("renaming impl class in step with %s: %s => %s".format(traitSym, originalImplName, implSym.name))
      }

      for (sym <- renamable) {
        // If we renamed a trait from Foo to Foo$1, we must rename the implementation
        // class from Foo$class to Foo$1$class.  (Without special consideration it would
        // become Foo$class$1 instead.)
        val implClass = if (sym.isTrait) renamableImplClasses(sym.name) else NoSymbol
        if ((implClass ne NoSymbol) && (sym.owner == implClass.owner)) renameTrait(sym, implClass)
        else renameSym(sym)
      }

      atPhase(phase.next) {
        for ((owner, freeValues) <- free.toList) {
          val newFlags = SYNTHETIC | ( if (owner.isClass) PARAMACCESSOR | PrivateLocal else PARAM )
          debuglog("free var proxy: %s, %s".format(owner.fullLocationString, freeValues.toList.mkString(", ")))
          proxies(owner) =
            for (fv <- freeValues.toList) yield {
              val proxy = owner.newValue(fv.name, owner.pos, newFlags) setInfo fv.info
              if (owner.isClass) owner.info.decls enter proxy
              proxy
            }
        }
      }
    }

    private def proxy(sym: Symbol) = {
      def searchIn(enclosure: Symbol): Symbol = {
        if (enclosure eq NoSymbol) throw new IllegalArgumentException("Could not find proxy for "+ sym.defString +" in "+ sym.ownerChain +" (currentOwner= "+ currentOwner +" )")
        debuglog("searching for " + sym + "(" + sym.owner + ") in " + enclosure + " " + enclosure.logicallyEnclosingMember)

        val ps = (proxies get enclosure.logicallyEnclosingMember).toList.flatten filter (_.name == sym.name)
        if (ps.isEmpty) searchIn(enclosure.skipConstructor.owner)
        else ps.head
      }
      debuglog("proxy " + sym + " in " + sym.owner + " from " + currentOwner.ownerChain.mkString(" -> ") +
          " " + sym.owner.logicallyEnclosingMember)

      if (isSameOwnerEnclosure(sym)) sym
      else searchIn(currentOwner)
    }

    private def memberRef(sym: Symbol) = {
      val clazz = sym.owner.enclClass
      //Console.println("memberRef from "+currentClass+" to "+sym+" in "+clazz)
      val qual = if (clazz == currentClass) gen.mkAttributedThis(clazz)
                 else {
                   sym resetFlag(LOCAL | PRIVATE)
                   if (clazz.isStaticOwner) gen.mkAttributedQualifier(clazz.thisType)
                   else outerPath(outerValue, currentClass.outerClass, clazz)
                 }
      Select(qual, sym) setType sym.tpe
    }

    private def proxyRef(sym: Symbol) = {
      val psym = proxy(sym)
      if (psym.isLocal) gen.mkAttributedIdent(psym) else memberRef(psym)
    }

    private def addFreeArgs(pos: Position, sym: Symbol, args: List[Tree]) = {
      free get sym match {
        case Some(fvs) => args ++ (fvs.toList map (fv => atPos(pos)(proxyRef(fv))))
        case _         => args
      }
    }

    private def addFreeParams(tree: Tree, sym: Symbol): Tree = proxies.get(sym) match {
      case Some(ps) =>
        val freeParams = ps map (p => ValDef(p) setPos tree.pos setType NoType)
        tree match {
          case DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
            val addParams = cloneSymbols(ps).map(_.setFlag(PARAM))
            sym.updateInfo(
              lifted(MethodType(sym.info.params ::: addParams, sym.info.resultType)))
            treeCopy.DefDef(tree, mods, name, tparams, List(vparamss.head ++ freeParams), tpt, rhs)
          case ClassDef(mods, name, tparams, impl @ Template(parents, self, body)) =>
            // Disabled attempt to to add getters to freeParams
            // this does not work yet. Problem is that local symbols need local names
            // and references to local symbols need to be transformed into
            // method calls to setters.
            // def paramGetter(param: Symbol): Tree = {
            //   val getter = param.newGetter setFlag TRANS_FLAG resetFlag PARAMACCESSOR // mark because we have to add them to interface
            //   sym.info.decls.enter(getter)
            //   val rhs = Select(gen.mkAttributedThis(sym), param) setType param.tpe
            //   DefDef(getter, rhs) setPos tree.pos setType NoType
            // }
            // val newDefs = if (sym.isTrait) freeParams ::: (ps map paramGetter) else freeParams
            treeCopy.ClassDef(tree, mods, name, tparams,
                              treeCopy.Template(impl, parents, self, body ::: freeParams))
        }
      case None =>
        tree
    }

/*  Something like this will be necessary to eliminate the implementation
 *  restiction from paramGetter above:
 *  We need to pass getters to the interface of an implementation class.
    private def fixTraitGetters(lifted: List[Tree]): List[Tree] =
      for (stat <- lifted) yield stat match {
        case ClassDef(mods, name, tparams, templ @ Template(parents, self, body))
        if stat.symbol.isTrait && !stat.symbol.isImplClass =>
          val iface = stat.symbol
          lifted.find(l => l.symbol.isImplClass && l.symbol.toInterface == iface) match {
            case Some(implDef) =>
              val impl = implDef.symbol
              val implGetters = impl.info.decls.toList filter (_ hasFlag TRANS_FLAG)
              if (implGetters.nonEmpty) {
                val ifaceGetters = implGetters map { ig =>
                  ig resetFlag TRANS_FLAG
                  val getter = ig cloneSymbol iface setFlag DEFERRED
                  iface.info.decls enter getter
                  getter
                }
                val ifaceGetterDefs = ifaceGetters map (DefDef(_, EmptyTree) setType NoType)
                treeCopy.ClassDef(
                  stat, mods, name, tparams,
                  treeCopy.Template(templ, parents, self, body ::: ifaceGetterDefs))
              } else
                stat
            case None =>
              stat
          }
        case _ =>
          stat
      }
*/
    private def liftDef(tree: Tree): Tree = {
      val sym = tree.symbol
      val oldOwner = sym.owner
      if (sym.owner.isAuxiliaryConstructor && sym.isMethod)  // # bug 1909
    	  sym setFlag STATIC
      sym.owner = sym.owner.enclClass
      if (sym.isClass) sym.owner = sym.owner.toInterface
      if (sym.isMethod) sym setFlag LIFTED
      liftedDefs(sym.owner) ::= tree
      sym.owner.info.decls enterUnique sym
      debuglog("lifted: " + sym + " from " + oldOwner + " to " + sym.owner)
      EmptyTree
    }

    private def postTransform(tree: Tree, isBoxedRef: Boolean = false): Tree = {
      val sym = tree.symbol
      tree match {
        case ClassDef(_, _, _, _) =>
          val tree1 = addFreeParams(tree, sym)
          if (sym.isLocal) liftDef(tree1) else tree1
        case DefDef(_, _, _, _, _, _) =>
          val tree1 = addFreeParams(tree, sym)
          if (sym.isLocal) liftDef(tree1) else tree1
        case ValDef(mods, name, tpt, rhs) =>
          if (sym.isCapturedVariable) {
            val tpt1 = TypeTree(sym.tpe) setPos tpt.pos
            /* Creating a constructor argument if one isn't present. */
            val constructorArg = rhs match {
              case EmptyTree =>
                sym.primaryConstructor.info.paramTypes match {
                  case List(tp) => gen.mkZero(tp)
                  case _        =>
                    log("Couldn't determine how to properly construct " + sym)
                    rhs
                }
              case arg => arg
            }
            /** Wrap expr argument in new *Ref(..) constructor, but make
             *  sure that Try expressions stay at toplevel.
             */
            def refConstr(expr: Tree): Tree = expr match {
              case Try(block, catches, finalizer) =>
                Try(refConstr(block), catches map refConstrCase, finalizer)
              case _ => 
                Apply(Select(New(TypeTree(sym.tpe)), nme.CONSTRUCTOR), List(expr))
            }
            def refConstrCase(cdef: CaseDef): CaseDef = 
              CaseDef(cdef.pat, cdef.guard, refConstr(cdef.body))
            treeCopy.ValDef(tree, mods, name, tpt1, typer.typedPos(rhs.pos) {
              refConstr(constructorArg)
            })
          } else tree
        case Return(Block(stats, value)) =>
          Block(stats, treeCopy.Return(tree, value)) setType tree.tpe setPos tree.pos
        case Return(expr) =>
          assert(sym == currentMethod, sym)
          tree
        case Apply(fn, args) =>
          treeCopy.Apply(tree, fn, addFreeArgs(tree.pos, sym, args))
        case Assign(Apply(TypeApply(sel @ Select(qual, _), _), List()), rhs) =>
          // eliminate casts introduced by selecting a captured variable field
          // on the lhs of an assignment.
          assert(sel.symbol == Object_asInstanceOf)
          treeCopy.Assign(tree, qual, rhs)
        case Ident(name) =>
          val tree1 =
            if (sym != NoSymbol && sym.isTerm && !sym.isLabel)
              if (sym.isMethod)
                atPos(tree.pos)(memberRef(sym))
              else if (sym.isLocal && !isSameOwnerEnclosure(sym))
                atPos(tree.pos)(proxyRef(sym))
              else tree
            else tree
          if (sym.isCapturedVariable && !isBoxedRef)
            atPos(tree.pos) {
              val tp = tree.tpe
              val elemTree = typer typed Select(tree1 setType sym.tpe, nme.elem)
              if (elemTree.tpe.typeSymbol != tp.typeSymbol) gen.mkAttributedCast(elemTree, tp) else elemTree
            }
          else tree1
        case Block(stats, expr0) =>
          val (lzyVals, rest) = stats partition {
            case stat: ValDef => stat.symbol.isLazy || stat.symbol.isModuleVar
            case _            => false
          }
          if (lzyVals.isEmpty) tree
          else treeCopy.Block(tree, lzyVals ::: rest, expr0)
        case _ =>
          tree
      }
    }
    
    private def preTransform(tree: Tree) = super.transform(tree) setType lifted(tree.tpe)

    override def transform(tree: Tree): Tree = tree match {
      case ReferenceToBoxed(idt) =>
        postTransform(preTransform(idt), isBoxedRef = true)
      case _ =>
        postTransform(preTransform(tree))
    }
      
    /** Transform statements and add lifted definitions to them. */
    override def transformStats(stats: List[Tree], exprOwner: Symbol): List[Tree] = {
      def addLifted(stat: Tree): Tree = stat match {
        case ClassDef(mods, name, tparams, impl @ Template(parents, self, body)) =>
          val lifted = liftedDefs get stat.symbol match {
            case Some(xs) => xs reverseMap addLifted
            case _        => log("unexpectedly no lifted defs for " + stat.symbol) ; Nil
          }
          val result = treeCopy.ClassDef(
            stat, mods, name, tparams, treeCopy.Template(impl, parents, self, body ::: lifted))
          liftedDefs -= stat.symbol
          result
        case DefDef(mods, name, tp, vp, tpt, Block(Nil, expr)) if !stat.symbol.isConstructor =>
          treeCopy.DefDef(stat, mods, name, tp, vp, tpt, expr)
        case _ =>
          stat
      }
      super.transformStats(stats, exprOwner) map addLifted
    }

    override def transformUnit(unit: CompilationUnit) {
      computeFreeVars
      atPhase(phase.next)(super.transformUnit(unit))
      assert(liftedDefs.isEmpty, liftedDefs.keys mkString ", ")
    }
  } // class LambdaLifter

}
