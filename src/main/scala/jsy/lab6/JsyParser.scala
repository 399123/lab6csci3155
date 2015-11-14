/**
 *
 */
package jsy.lab6

import jsy.lab6.ast._
import scala.util.parsing.combinator._
import scala.util.parsing.input.StreamReader
import java.io.{InputStreamReader,FileInputStream}
import java.io.InputStream
import java.io.File
import scala.util.parsing.input.Position
import scala.util.parsing.input.NoPosition
import scala.collection.immutable.TreeMap

trait JSTokens extends token.StdTokens {
  case class FloatLiteral(chars: String) extends Token {
    override def toString = chars
  }
  case class RegExpLiteral(chars: String) extends Token {
    override def toString = chars
  }
}

class Lexer extends lexical.StdLexical with JSTokens {
  override def token: Parser[Token] =
    decimal ~ opt(exponent) ^^ {
      case dec ~ exp => FloatLiteral(List(Some(dec), exp).flatten.mkString)
    } |
    regexp ^^ { s => RegExpLiteral(s) } |
    super.token
    
  def decimal: Parser[String] =
    rep1(digit) ~ opt('.' ~ rep(digit)) ^^ {
      case ws ~ fs =>
        List(Some(ws), fs map { mkList }).flatten.flatten.mkString
    }
  
  def exponent: Parser[String] =
    (accept('e') | accept('E')) ~ opt(accept('+') | accept('-')) ~ rep1(digit) ^^ { 
      case exp ~ sign ~ digits =>
        List(Some(List(exp)), sign map { List(_) }, Some(digits)).flatten.flatten.mkString
    }
  
  import scala.util.parsing.input.CharArrayReader.EofCh
  def regexp: Parser[String] =
    '/' ~> '^' ~> rep( chrExcept('$', '/', EofCh) ) <~ '$' <~ '/' ^^ { cs => cs.mkString }
}

trait TokenParser extends syntactical.StdTokenParsers {
  type Tokens = JSTokens
  val lexical = new Lexer
  
  def floatLit: Parser[String] =
    elem("float", _.isInstanceOf[lexical.FloatLiteral]) ^^ (_.chars)
  def regexpLit: Parser[String] =
    elem("regexp", _.isInstanceOf[lexical.RegExpLiteral]) ^^ (_.chars)
}

class JsyParser(regExprParser: String => RegExpr) extends TokenParser {
  def this() = this(RegExprParser.parse)
  
  /* Lexer Set Up */
  lexical.reserved ++= List("jsy", "undefined", "true", "false", "print", "console", "log", "const", "function", "return", "number", "bool", "string", "Undefined", "null", "Null", "interface", "var", "name", "ref", "RegExp")
  lexical.delimiters ++= List("-", "!", ";", ",", "+", "*", "/", "=", "===", "!==", "<", "<=", ">", ">=", "&&", "||", "(", ")", ".", "{", "}", "?", ":", "=>", "/", "^", "$")
  
  /* Helpers */
  def seqExpr(e1: Expr, e2: Expr): Expr = Binary(Seq, e1, e2)
  
  /* EBNF 
   * 
   * prog ::= {stmt}
   * stmt ::= block | decl | ; | expr
   * block ::= '{' prog '}'
   * decl ::= const x = expr
   * expr ::= seq
   * seq ::= cond{,cond}
   * cond ::= binary [? cond : cond]
   * binary ::= unary{bop(_)unary}
   * unary ::= uop unary | term
   * term ::= x | n | b | undefined | jsy.print(expr) | (expr) | re
   */
  
  sealed abstract class PStmt
  case class ExprPStmt(e: Expr) extends PStmt
  case class DeclPStmt(d: Expr => Expr) extends PStmt
  case object EmpPStmt extends PStmt
  
  def prog: Parser[Expr] =
    stmts ^^ (s => s(None))
  
  def stmts: Parser[Option[Expr] => Expr] =
    rep(stmt) ^^ { (stmts: List[PStmt]) => (body: Option[Expr]) =>
      (stmts :\ body){
        case (EmpPStmt, eopt) => eopt
        case (ExprPStmt(e), None) => Some(e)
        case (ExprPStmt(e1), Some(e2)) => Some(seqExpr(e1, e2))
        case (DeclPStmt(d), None) => Some(d(Undefined))
        case (DeclPStmt(d), Some(e)) => Some(d(e))
      } match {
        case None => Undefined
        case Some(e) => e
      }
    }

  def stmt: Parser[PStmt] =
    block ^^ ExprPStmt |
    decl ^^ DeclPStmt |
    expr ^^ ExprPStmt |
    empty_stmt
  
  def empty_stmt: Parser[PStmt] =
    ";" ^^ (_ => EmpPStmt)
    
  def block: Parser[Expr] =
    "{" ~> prog <~ "}"
    
  def decl: Parser[Expr => Expr] =
    (mut ~ ident) ~ withpos("=" ~> expr) ^^ {
      case mut ~ x ~ ((pos,e1)) => ((e2: Expr) => Decl(mut, x, e1, e2) setPos pos)
    } |
    ("interface" ~> ident) ~ withpos(record(";", TObj, ty)) ^^ {
      case tyvar ~ ((pos,ty)) => ((e: Expr) => InterfaceDecl(tyvar, ty, e) setPos pos) 
    }
    
  def mut: Parser[Mutability] =
    "const" ^^ (_ => MConst) |
    "var" ^^ (_ => MVar)
    
  def expr: Parser[Expr] =
    seq
    
  def seq: Parser[Expr] =
    noseq ~ withposrep("," ~> noseq) ^^ {
      case e0 ~ es => 
        (es :\ (None: Option[(Position,Expr)])){
          case ((posi,ei), None) => Some(posi,ei)
          case ((posi,ei), Some((pos,e))) => Some(posi, seqExpr(ei,e) setPos pos)
        } match {
          case None => e0
          case Some((pos,e)) => seqExpr(e0, e) setPos pos
        }
    }
  
  def noseq: Parser[Expr] =
    assign
    
  def assign: Parser[Expr] =
    cond ~ opt(withpos(("=" ~> assign))) ^^ {
      case e1 ~ None => e1
      case e1 ~ Some((pos,e2)) => Assign(e1, e2) setPos pos
    }
    
  def cond: Parser[Expr] =
    binary(0) ~ opt(withpos(("?" ~> noseq) ~ (":" ~> noseq))) ^^ {
      case e1 ~ None => e1
      case e1 ~ Some((pos, e2 ~ e3)) => If(e1, e2, e3) setPos pos
    }
    
  val binaryOperators: Vector[List[(String, (Expr, Expr) => Expr)]] = {
    def createBinaryFunction(op: Bop): (Expr, Expr) => Expr =
      { Binary(op, _, _) }
    Vector() ++ List(
      List("||" -> createBinaryFunction(Or)),
      List("&&" -> createBinaryFunction(And)),
      List("===" -> createBinaryFunction(Eq),
           "!==" -> createBinaryFunction(Ne)),
      List("<" -> createBinaryFunction(Lt),
           "<=" -> createBinaryFunction(Le),
           ">" -> createBinaryFunction(Gt),
           ">=" -> createBinaryFunction(Ge)),
      List("+" -> createBinaryFunction(Plus),
           "-" -> createBinaryFunction(Minus)),
      List("*" -> createBinaryFunction(Times),
      	   "/" -> createBinaryFunction(Div))
    )
  }

  def binary(level: Int): Parser[Expr] =
    if (level >= binaryOperators.length)
      unary
    else
      binary(level + 1) * bop(level)

  def bop(level: Int): Parser[(Expr, Expr) => Expr] = {
    def doBop(opf: (String, (Expr, Expr) => Expr)): Parser[(Expr, Expr) => Expr] = {
      val (op, f) = opf
      withpos(op) ^^ { case (pos, _) => ((e1, e2) => f(e1, e2) setPos pos) }
    }
    val bopf0 :: bopfrest = binaryOperators(level)
    (doBop(bopf0) /: bopfrest)((acc, bopf) => acc | doBop(bopf))
  }

  def unary: Parser[Expr] =
    positioned(uop ~ unary ^^ { case op ~ e => op(e) }) |
    call

  def uop: Parser[Expr => Expr] =
    "-" ^^ (_ => (e: Expr) => Unary(Neg, e)) |
    "!" ^^ (_ => (e: Expr) => Unary(Not, e)) |
    "<" ~> ty <~ ">" ^^ (t => (e: Expr) => Unary(Cast(t), e))
    
  def call: Parser[Expr] =
    term ~ rep(callop | derefop) ^^ { case e0 ~ callderefs => (e0 /: callderefs){ case (acc, mk) => mk(acc) } }
  
  def callop: Parser[Expr => Expr] =
    withpos("(" ~> repsep(noseq, ",") <~ ")") ^^ { case (pos, args) => (e0 => Call(e0, args) setPos pos) }
  
  def derefop: Parser[Expr => Expr] =
    withpos("." ~> ident) ^^ { case (pos,f) => (e0 => GetField(e0, f) setPos pos) }

  def term: Parser[Expr] =
    positioned(
      ident ^^ (s => Var(s)) |
      floatLit ^^ (s => N(s.toDouble)) |
      stringLit ^^ (s => S(s)) |
      regexpLit ^^ (s => RE(regExprParser(s))) |
      "true" ^^ (_ => B(true)) |
      "false" ^^ (_ => B(false)) |
      "undefined" ^^ (_ => Undefined) |
      "null" ^^ (_ => Null) |
      ("console" ~ "." ~ "log") ~> "(" ~> expr <~ ")" ^^ (e => Print(e)) |
      function |
      record(",", Obj, noseq)
    ) |
    "jsy" ~> "." ~> withpos(ident) ~ ("(" ~> repsep(noseq, ",") <~ ")") ^^ {
      case ((pos, "print")) ~ List(e) => Print(e)
      case ((pos, f)) ~ args => RuntimeCall(f, args) setPos pos
    } |
    "(" ~> expr <~ ")" |
    "{" ~> "{" ~> prog <~ "}" <~ "}" |
    failure("atomic expression expected")
    
  def function: Parser[Expr] =
    ("function" ~> opt(ident)) ~ ("(" ~> params <~ ")") ~ opt(":" ~> ty) ~ ("{" ~> stmts ~ ret <~ rep(empty_stmt) <~ "}") ^^ {
      case f ~ params ~ retty ~ (stmts ~ ret) =>
        val body = stmts(Some(ret))
        Function(f, params, retty, body)
    } |
    ("(" ~> params <~ ")") ~ (withpos("=>" ~> noseq)) ^^ {
      case params ~ ((pos, body)) => Function(None, params, None, body) setPos pos
    }
    
  def params: Parser[ Either[ List[(String,Typ)], (PMode,String,Typ) ] ] =
    pmode ~ colonpair(ty) ^^ {
      case mode ~ ((x,t)) => Right((mode, x, t))
    } |
    repsep(colonpair(ty), ",") ^^  {
      case params => Left(params)
    }
    
  def pmode: Parser[PMode] =
    "name" ^^ (_ => PName) |
    "var" ^^ (_ => PVar) |
    "ref" ^^ (_ => PRef)
        
  def ret: Parser[Expr] =
    "return" ~> expr
    
  def record[A](sep: String, node: Map[String, A] => A, q: => Parser[A]): Parser[A] = {
    lazy val p = q
    "{" ~> repsep(colonpair(p), sep) <~ (opt(",") ~ "}") ^^ { flist =>
      val fmap = flist.foldLeft(TreeMap.empty: TreeMap[String, A]){
        case (fmap, (f, e)) => fmap + (f -> e)
      }
      node(fmap)
    }
  }
    
  def anncolonpair[A,B](default: B, qann: => Parser[B], q: => Parser[A]): Parser[(String, (B, A))] = {
    lazy val pann = qann
    opt(pann) ~ colonpair(q) ^^ {
      case None ~ ((f,e)) => (f, (default,e))
      case Some(ann) ~ ((f,e)) => (f, (ann, e))
    }
  }
    
  def colonpair[A](q: => Parser[A]): Parser[(String, A)] = {
    lazy val p = q
    ident ~ (":" ~> p) ^^ { case f ~ e => (f, e) }
  }
  
  def ty: Parser[Typ] =
    ident ^^ TVar |
    "number" ^^ (_ => TNumber) |
    "bool" ^^ (_ => TBool) |
    "string" ^^ (_ => TString) |
    "Undefined" ^^ (_ => TUndefined) |
    "Null" ^^ (_ => TNull) |
    "RegExp" ^^ (_ => TRegExp) |
    record(";", TObj, ty) |
    tyfunction |
    failure("type expected")
    
  def tyfunction: Parser[Typ] =
    ("(" ~> params <~ ")") ~ ("=>" ~> ty) ^^ {
      case params ~ t2 => TFunction(params, t2)
    }
  
  def withpos[T](q: => Parser[T]): Parser[(Position, T)] = Parser { in =>
    q(in) match {
      case Success(t, in1) => Success((in.pos,t), in1)
      case ns: NoSuccess => ns
    }
  }
  
  def withposrep[T](q: => Parser[T]): Parser[List[(Position,T)]] =
    rep(withpos(q))
    
  def withposrep1[T](q: => Parser[T]): Parser[List[(Position,T)]] =
    rep1(withpos(q))
   
  def parseTokens(tokens: lexical.Scanner): Expr = {
    phrase(prog)(tokens) match {
      case Success(e, _) => e
      case NoSuccess(msg, next) => throw new SyntaxError(msg, next.pos)
    }
  }
  
  def parseTypTokens(tokens: lexical.Scanner): Typ = {
    phrase(ty)(tokens) match {
      case Success(t, _) => t
      case NoSuccess(msg, next) => throw new SyntaxError(msg, next.pos)
    }
  }
  
  /*** External Interface ***/
  
  def parse(s: String): Expr = {
    parseTokens(new lexical.Scanner(s))
  }
  
  def parseTyp(s: String): Typ = {
    parseTypTokens(new lexical.Scanner(s))
  }
  
  def parse(in: InputStream): Expr = {
    val reader = StreamReader(new InputStreamReader(in))
    parseTokens(new lexical.Scanner(reader))
  }
  
  def parseFile(filename: String): Expr = {
    JsyParser.parseSource = filename
    parse(new FileInputStream(filename))
  }
  
  def parseFile(file: File): Expr = {
    JsyParser.parseSource = file.getName
    parse(new FileInputStream(file))
  }

  implicit class StringExpr(s: String) {
    val e = parse(s)
    def a: Expr = e
  }

  implicit class StringTyp(s: String) {
    val typ = parseTyp(s)
    def t: Typ = typ
  }
}

object JsyParser {
  private var parseSource: String = "<source>"
    
  def formatErrorMessage(pos: Position, kind: String, msg: String): String =
    if (pos != NoPosition)
      "%s\n%s:%s:%s: %s\n%s".format(kind, parseSource, pos.line, pos.column, msg, pos.longString)
    else
      "%s\n%s: %s".format(kind, parseSource, msg)
    
  /*** External Interface ***/
  
  def formatErrorMessage(e: Expr, kind: String, msg: String): String =
    formatErrorMessage(e.pos, kind, msg)
}