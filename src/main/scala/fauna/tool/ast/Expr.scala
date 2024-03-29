package fauna.tool.ast

import fauna.tool.parser.ASTBuilder
import fauna.tool.validator.{ Constraint, ValidationResult }
import org.json4s.JsonAST.{
  JArray,
  JBool,
  JDecimal,
  JDouble,
  JField,
  JInt,
  JLong,
  JNothing,
  JNull,
  JObject,
  JString,
  JValue
}

abstract class Expr extends Product {

  def name: String = this.productPrefix

  private[ast] val classAccessors
    : List[(String, Boolean)] = this.getClass.getDeclaredFields.toList map {
    field: java.lang.reflect.Field =>
      (field.getName().replace("$at", "@"), field.getType.getName == "scala.Option")
  }

  val arity: Arity = {
    var args, optional: Int = 0
    classAccessors map {
      case (_, true)  => optional += 1; args += 1
      case (_, false) => args += 1
    }
    if (optional > 0) Arity.Between(args - optional, args) else Arity.Exact(args)
  }

  def children: Seq[Option[Expr]] = arity match {
    case Arity.Exact(e) =>
      for (i <- (0 until e); elem = this.productElement(i).asInstanceOf[Expr])
        yield Some(elem)
    case Arity.VarArgs =>
      this.productElement(0).asInstanceOf[Expr] match {
        case Array(l) => l.map(Some(_))
      }
    case Arity.Between(min, max) => {
      val required: Seq[Option[Expr]] =
        for (i <- (0 until min); elem = this.productElement(i).asInstanceOf[Expr])
          yield Some(elem)
      val optional: Seq[Option[Expr]] =
        for (i <- (min until max);
             elem = this.productElement(i).asInstanceOf[Option[Expr]]) yield elem
      required.:++(optional)
    }
  }

  def forEachChildren[T](fn: Expr => T): Unit = {
    fn(this)
    this.children.collect {
      case Some(e) => e.forEachChildren(fn)
    }
  }

  def register[T]()(implicit bf: ASTBuilder[T]) =
    bf.register(name, arity, classAccessors, build)

  def constraints: Map[String, Set[Constraint]] = Map()

  //Returns a new instance of self with random values
  def randomSelf(implicit r: fauna.tool.fuzzer.RandomGenerator): Expr = ???

//Returns a new expression with random children up to depth
  def random(depth: Int)(implicit r: fauna.tool.fuzzer.RandomGenerator): Expr = ???

  def toJson: JValue = Expr.toJson(this)

  def validate: Set[ValidationResult] = {

    classAccessors
      .zip(children)
      .collect {
        case (tpl, Some(expr)) if constraints.isDefinedAt(tpl._1) =>
          this.validate(expr, constraints(tpl._1))
      }
      .flatten
      .toSet
  }

  private def validate(
    expr: Expr,
    constraints: Set[Constraint]
  ): Set[ValidationResult] = constraints.map { c =>
    c.check(expr)
  }

  def build[T](value: T)(implicit bf: ASTBuilder[T]): Expr = ???

  //override this
  def effect: Effect = effect(Effect.Pure)

  private[ast] def effect(effect_ : Effect): Effect = {
    children.toList
      .collect { case Some(c) => c }
      .foldLeft(effect_)((acc, child) => {
        child.effect + acc
      })
  }

  def evaluatesTo: Type = AnyT
}

object Expr {

  //Maps a given function name to an instance of the class
  def stringFnToExpr(str: String)(implicit bf: ASTBuilder[_]): Option[Expr] =
    knownExprs.find(_.name.toLowerCase == str)

  def build[T](value: T)(implicit bf: ASTBuilder[T]): Expr = bf.build(value)

  def buildOpt[T](value: T)(implicit bf: ASTBuilder[T]): Option[Expr] =
    bf.buildOpt(value)

  def reg[T](implicit bf: ASTBuilder[T]) = {
    knownExprs.map(_.register()(bf))
  }

  def toJson(e: Expr) = e match {
    case l: Literal => literalToJson(l)
    case e: Expr =>
      JObject(e.classAccessors.zip(e.children).collect {
        case (ca, Some(expr)) => JField(ca._1, expr.toJson)
        case (ca, None)       => JField(ca._1, JNothing)
      })
  }

  private[tool] def literalToJson(l: Literal): JValue = l match {
    case LongL(l)    => JLong(l)
    case DoubleL(d)  => JDouble(d)
    case FloatL(f)   => JDecimal(f.toDouble)
    case DecimalL(d) => JDecimal(d)
    case IntL(i)     => JInt(i)
    case ArrayL(l)   => JArray(l.map(_.toJson))
    case ObjectL(m) =>
      JObject(JField("object", JObject(m.map {
        case (k, v) => JField(k, v.toJson)
      }.toList)))
    case StringL(s) => JString(s)
    case TrueL      => JBool(true)
    case FalseL     => JBool(false)
    case NullL      => JNull
  }

  //Nulled instances of all known Expr
  //Used by ASTBuilder to register each Expr builder
  private[tool] val knownExprs: Seq[Expr] = Seq(
    Var(NullL),
    Max(NullL),
    Min(NullL),
    QueryV(NullL),
    SetV(NullL),
    DateV(NullL),
    BytesV(NullL),
    TimestampV(NullL),
    Ref(NullL),
    Ref2(NullL, None, None),
    Let(NullL, NullL),
    ForEach(NullL, NullL),
    Merge(NullL, NullL, None),
    Lambda(NullL, NullL),
    Reduce(NullL, NullL, NullL),
    MapFn(NullL, NullL),
    Call(NullL, None),
    If(NullL, NullL, NullL),
    Do(NullL)
    //Comparison
    ,
    LT(NullL),
    LTE(NullL),
    GT(NullL),
    GTE(NullL)

    //collection
    ,
    Prepend(NullL, NullL),
    Append(NullL, NullL),
    Take(NullL, NullL),
    Drop(NullL, NullL),
    IsEmpty(NullL),
    IsNonEmpty(NullL)

    //string
    ,
    Concat(NullL, None),
    CaseFold(NullL, None),
    RegexEscape(NullL),
    StartsWith(NullL, NullL),
    EndsWith(NullL, NullL),
    ContainsStr(NullL, NullL),
    ContainsStrRegex(NullL, NullL),
    FindStr(NullL, NullL, None),
    FindStrRegex(NullL, NullL, None, None),
    Length(NullL),
    LowerCase(NullL),
    LTrim(NullL),
    NGram(NullL, None, None),
    Repeat(NullL, None),
    ReplaceStr(NullL, NullL, NullL),
    ReplaceStrRegex(NullL, NullL, NullL, None),
    Rtrim(NullL),
    Space(NullL),
    SubString(NullL, NullL, None),
    TitleCase(NullL),
    Trim(NullL),
    Uppercase(NullL),
    Format(NullL, None)

    //Numerics
    ,
    Abs(NullL),
    Add(NullL),
    BitAnd(NullL),
    BitNot(NullL),
    BitOr(NullL),
    BitXor(NullL),
    Ceil(NullL),
    Divide(NullL),
    Floor(NullL),
    Modulo(NullL),
    Multiply(NullL),
    Round(NullL, None),
    Subtract(NullL),
    Sign(NullL),
    Sqrt(NullL),
    Trunc(NullL, None),
    Mean(NullL),
    Count(NullL),
    Sum(NullL),
    Any(NullL),
    All(NullL)

    //trig
    ,
    ACos(NullL),
    ASin(NullL),
    ATan(NullL),
    Cos(NullL),
    Cosh(NullL),
    Degrees(NullL),
    Exp(NullL),
    Hypot(NullL, None),
    Ln(NullL),
    Log(NullL),
    Pow(NullL, None),
    Radians(NullL),
    Sin(NullL),
    Sinh(NullL),
    Tan(NullL),
    Tanh(NullL)

    //bool
    ,
    And(NullL),
    Or(NullL),
    Not(NullL)

    //refs

    // Native classref constructors
    ,
    Databases(NullL),
    Indexes(NullL),
    Classes(NullL),
    Collections(NullL),
    Keys(NullL),
    Tokens(NullL),
    Credentials(NullL),
    Functions(NullL),
    Roles(NullL)

    // Set ref constructors
    ,
    Singleton(NullL),
    Events(NullL)
    //abstract class AbstractMatch(`match`: Expr, index: Option[Expr], terms: Option[Expr]) extends FnExpr
    ,
    Match(NullL),
    MatchIndex(NullL, NullL),
    MatchTerms(NullL, NullL),
    Union(NullL),
    Intersection(NullL),
    Difference(NullL),
    Distinct(NullL),
    Range(NullL, NullL, NullL)

    //reads
    ,
    Exists(NullL, None),
    Get(NullL, None),
    KeyFromSecret(NullL),
    //abstract class AbstractPaginate(paginate: Expr, before: Option[Expr], after: Option[Expr], cursor: Option[Expr], ts: Option[Expr], size: Option[Expr], events: Option[Expr], sources: Option[Expr]) extends FnExpr
    PaginateAfter(NullL, None, None, None, None, None),
    PaginateCursor(NullL, NullL, None, None, None, None),
    PaginateBefore(NullL, NullL, None, None, None, None),
    //writes
    Create(NullL, None),
    Update(NullL, None),
    Replace(NullL, None),
    Delete(NullL),
    CreateClass(NullL),
    CreateCollection(NullL),
    CreateDatabase(NullL),
    CreateIndex(NullL),
    CreateKey(NullL),
    CreateFunction(NullL),
    CreateRole(NullL),
    Insert(NullL, NullL, NullL, None),
    Remove(NullL, NullL, NullL),
    MoveDatabase(NullL, NullL)

    //auth
    ,
    Identity(NullL),
    HasIdentity(NullL),
    Identify(NullL, NullL),
    Login(NullL, NullL),
    Logout(NullL)

    //time and date
    ,
    Time(NullL),
    Now(NullL),
    Epoch(NullL, NullL),
    Date(NullL),
    ToMicros(NullL),
    ToMillis(NullL),
    ToSeconds(NullL),
    Second(NullL),
    Minute(NullL),
    Hour(NullL),
    DayOfMonth(NullL),
    DayOfWeek(NullL),
    DayOfYear(NullL),
    Month(NullL),
    Year(NullL),
    TimeAdd(NullL, NullL, NullL),
    TimeSubtract(NullL, NullL, NullL),
    TimeDiff(NullL, NullL, NullL)

    //conversion
    ,
    ToString(NullL),
    ToNumber(NullL),
    ToTime(NullL),
    ToDate(NullL),
    ToObject(NullL),
    ToArray(NullL)

    //select
    ,
    Abort(NullL),
    Equals(NullL),
    Contains(NullL, NullL),
    Select(NullL, NullL, None),
    SelectAll(NullL, NullL, None),
    SelectAsIndex(NullL, NullL, None),
    Class(NullL, None)
    //case class Ref(ref, id, scope: Option[Expr])
    ,
    Database(NullL, None),
    Index(NullL, None),
    Class(NullL, None),
    Collection(NullL, None),
    Function(NullL, None),
    Role(NullL, None)
    //deprecated, use new_id
    ,
    NextID(NullL),
    NewID(NullL),
    ObjectExpr(NullL)
  )

}
