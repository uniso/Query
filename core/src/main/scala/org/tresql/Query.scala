package org.tresql

import java.sql.{ Array => JArray }
import java.sql.{ Date, Timestamp, PreparedStatement, CallableStatement, ResultSet }
import org.tresql.metadata._
import sys._

object Query {

  def apply(expr: String, params: Any*): Any = if (params.size == 1) params(0) match {
    case l:List[_] => apply(expr, l)
    case m:Map[String, _] => apply(expr, m)
    case x => apply(expr, List(x))
  } else apply(expr, params.toList)

  def apply(expr: String, params: List[Any]): Any = {
    apply(expr, params.zipWithIndex.map(t => (t._2 + 1).toString -> t._1).toMap)
  }

  def apply(expr: String, params: Map[String, Any]): Any = {
    val exp = QueryBuilder(expr, Env(params, false))
    exp()
  }

  def apply(expr: Any) = {
    val exp = QueryBuilder(expr, Env(Map(), false))
    exp()
  }

  def parse(expr: String) = QueryParser.parseAll(expr)
  def build(expr: String): Expr = QueryBuilder(expr, Env(Map(), true))
  def build(expr: Any): Expr = QueryBuilder(expr, Env(Map(), true))

  def select(expr: String, params: Any*) = {
    apply(expr, params.toList).asInstanceOf[Result]
  }

  def select(expr: String, params: List[Any]) = {
    apply(expr, params).asInstanceOf[Result]
  }

  def select(expr: String, params: Map[String, Any]) = {
    apply(expr, params).asInstanceOf[Result]
  }

  def select(expr: String) = {
    apply(expr).asInstanceOf[Result]
  }

  def foreach(expr: String, params: Any*)(f: (RowLike) => Unit = (row) => ()) {
    (if (params.size == 1) params(0) match {
      case l: List[_] => select(expr, l)
      case m: Map[String, _] => select(expr, m)
      case x => select(expr, x)
    }
    else select(expr, params)) foreach f
  }

  private[tresql] def select(sql: String, cols: List[QueryBuilder#ColExpr],
    bindVariables: List[Expr], env: Env, allCols: Boolean): Result = {
    Env log sql
    val st = statement(sql, env)
    bindVars(st, bindVariables)
    var i = 0
    val rs = st.executeQuery
    def rcol(c: QueryBuilder#ColExpr) = if (c.separateQuery) Column(-1, c.aliasOrName, c.col) else {
      i += 1; Column(i, c.aliasOrName, null)
    }
    val r = new Result(rs, Vector((if (!allCols) cols.map { rcol(_) }
    else cols.flatMap { c =>
      (if (c.col.isInstanceOf[QueryBuilder#AllExpr]) {
        var (md, l) = (rs.getMetaData, List[Column]())
        1 to md.getColumnCount foreach { j => i += 1; l = Column(i, md.getColumnLabel(j), null) :: l }
        l.reverse
      } else List(rcol(c)))
    }): _*), env)
    env update r
    r
  }

  private[tresql] def update(sql: String, bindVariables: List[Expr], env: Env) = {
    Env log sql
    val st = statement(sql, env)
    bindVars(st, bindVariables)
    val r = st.executeUpdate
    if (!env.reusableExpr) {
      st.close
      env update (null: PreparedStatement)
    }
    r
  }
  
  private[tresql] def call(sql: String, bindVariables: List[Expr], env: Env) = {
    Env log sql
    val st = statement(sql, env, true).asInstanceOf[CallableStatement]
    bindVars(st, bindVariables)
    val result = if(st.execute) {
      val rs = st.getResultSet
      val md = rs.getMetaData
      val res = new Result(rs, Vector(1 to md.getColumnCount map 
          {i=> Column(i, md.getColumnLabel(i), null)}:_*), env)
      env update res
      res
    }
    val outs = bindVariables map (_()) filter (_.isInstanceOf[OutPar]) map {x=>
      val p = x.asInstanceOf[OutPar]
      p.value = p.value match {
        case null => st.getObject(p.idx)
        case i: Int => val x = st.getInt(p.idx); if(st.wasNull) null else x
        case l: Long => val x = st.getLong(p.idx); if(st.wasNull) null else x
        case d: Double => val x = st.getDouble(p.idx); if(st.wasNull) null else x
        case f: Float => val x = st.getFloat(p.idx); if(st.wasNull) null else x
        // Allow the user to specify how they want the Date handled based on the input type
        case t: java.sql.Timestamp => st.getTimestamp(p.idx)
        case d: java.sql.Date => st.getDate(p.idx)
        case t: java.sql.Time => st.getTime(p.idx)
        /* java.util.Date has to go last, since the java.sql date/time classes subclass it. By default we
* assume a Timestamp value */
        case d: java.util.Date => st.getTimestamp(p.idx)
        case b: Boolean => st.getBoolean(p.idx)
        case s: String => st.getString(p.idx)
        case bn: java.math.BigDecimal => st.getBigDecimal(p.idx)
        case bd: BigDecimal => val x = st.getBigDecimal(p.idx); if (st.wasNull) null else BigDecimal(x)
      }
      p.value
    }    
    if (result == () && !env.reusableExpr) {
      st.close
      env update (null: PreparedStatement)
    }
    result :: outs match {
      case ()::Nil => ()
      case List(r:Result) => r
      case l@List(r:Result, x, _*) => l
      case ()::l => l
      case x => error("Knipis: " + x)
    }
  }

  private def statement(sql: String, env: Env, call: Boolean = false) = {
    val conn = env.conn
    if (conn == null) throw new NullPointerException(
      """Connection not found in environment. Check if "Env update conn" (in this case statement execution must be done in the same thread) or "Env.sharedConn = conn" is called.""")
    if (env.reusableExpr)
      if (env.statement == null) {
        val s = if (call) conn.prepareCall(sql) else {
          conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
        }
        env.update(s)
        s
      } else env.statement
    else if (call) conn.prepareCall(sql)
    else conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
  }

  private def bindVars(st: PreparedStatement, bindVariables: List[Expr]) {
    Env.log(bindVariables.map(_.toString).mkString("Bind vars: ", ", ", ""), 1)
    var idx = 1
    def bindVar(p: Any) {
      p match {
        case null => st.setNull(idx, java.sql.Types.NULL)
        case i: Int => st.setInt(idx, i)
        case l: Long => st.setLong(idx, l)
        case d: Double => st.setDouble(idx, d)
        case f: Float => st.setFloat(idx, f)
        // Allow the user to specify how they want the Date handled based on the input type
        case t: java.sql.Timestamp => st.setTimestamp(idx, t)
        case d: java.sql.Date => st.setDate(idx, d)
        case t: java.sql.Time => st.setTime(idx, t)
        /* java.util.Date has to go last, since the java.sql date/time classes subclass it. By default we
* assume a Timestamp value */
        case d: java.util.Date => st.setTimestamp(idx, new java.sql.Timestamp(d.getTime))
        case b: Boolean => st.setBoolean(idx, b)
        case s: String => st.setString(idx, s)
        case bn: java.math.BigDecimal => st.setBigDecimal(idx, bn)
        case bd: BigDecimal => st.setBigDecimal(idx, bd.bigDecimal)
        //array binding
        case i: scala.collection.Traversable[_] => i foreach (bindVar(_)); idx -= 1
        case a: Array[_] => a foreach (bindVar(_)); idx -= 1
        case p@InOutPar(v) => {
          bindVar(v)
          idx -= 1
          registerOutPar(st.asInstanceOf[CallableStatement], p, idx)
        }
        //OutPar must be matched bellow InOutPar since it is superclass of InOutPar
        case p@OutPar(_) => registerOutPar(st.asInstanceOf[CallableStatement], p, idx)
        //unknown object
        case obj => st.setObject(idx, obj)
      }
      idx += 1
    }
    bindVariables.map(_()).foreach { bindVar(_) }
  }
  private def registerOutPar(st: CallableStatement, par: OutPar, idx: Int) {
    import java.sql.Types._
    par.idx = idx
    par.value match {
      case null => st.registerOutParameter(idx, NULL)
      case i: Int => st.registerOutParameter(idx, INTEGER)
      case l: Long => st.registerOutParameter(idx, BIGINT)
      case d: Double => st.registerOutParameter(idx, DECIMAL)
      case f: Float => st.registerOutParameter(idx, DECIMAL)
      // Allow the user to specify how they want the Date handled based on the input type
      case t: java.sql.Timestamp => st.registerOutParameter(idx, TIMESTAMP)
      case d: java.sql.Date => st.registerOutParameter(idx, DATE)
      case t: java.sql.Time => st.registerOutParameter(idx, TIME)
      /* java.util.Date has to go last, since the java.sql date/time classes subclass it. By default we
* assume a Timestamp value */
      case d: java.util.Date => st.registerOutParameter(idx, TIMESTAMP)
      case b: Boolean => st.registerOutParameter(idx, BOOLEAN)
      case s: String => st.registerOutParameter(idx, VARCHAR)
      case bn: java.math.BigDecimal => st.registerOutParameter(idx, DECIMAL, bn.scale)
      case bd: BigDecimal => st.registerOutParameter(idx, DECIMAL, bd.scale)
      //unknown object
      case obj => st.registerOutParameter(idx, OTHER)
    }
  }
  
  /*---------------- Single value methods -------------*/
  def head[T](expr: String, params: Any*)(implicit m:scala.reflect.Manifest[T]): T = {
    if (params.size == 1) params(0) match {
      case l: List[_] => typedValue[T](select(expr, l), HEAD)
      case map: Map[String, _] => typedValue[T](select(expr, map), HEAD)
      case x => typedValue[T](select(expr, x), HEAD)
    }
    else typedValue[T](select(expr, params), HEAD)
  }
  def headOption[T](expr: String, params: Any*)(implicit m:scala.reflect.Manifest[T]): Option[T] = {
    try {
      if (params.size == 1) params(0) match {
        case l: List[_] => Some(typedValue(select(expr, l), HEAD_OPTION))
        case map: Map[String, _] => Some(typedValue(select(expr, map), HEAD_OPTION))
        case x => Some(typedValue(select(expr, x), HEAD_OPTION))
      }
      else Some(typedValue[T](select(expr, params), HEAD_OPTION))
    } catch {
      case e: NoSuchElementException => None
    }
  }
  def unique[T](expr: String, params: Any*)(implicit m:scala.reflect.Manifest[T]): T = {
    if (params.size == 1) params(0) match {
      case l: List[_] => typedValue[T](select(expr, l), UNIQUE)
      case map: Map[String, _] => typedValue[T](select(expr, map), UNIQUE)
      case x => typedValue[T](select(expr, x), UNIQUE)
    }
    else typedValue[T](select(expr, params), UNIQUE)
  }

  private val HEAD = 1
  private val HEAD_OPTION = 2
  private val UNIQUE = 3
  private def typedValue[T](r: Result, mode: Int)(implicit m:scala.reflect.Manifest[T]):T = {
    mode match {
      case HEAD | HEAD_OPTION => {
        r hasNext match {
          case true => r next; val v = r.typed[T](0); r close; v
          case false => throw new NoSuchElementException("No rows in result")
        }
      }
      case UNIQUE => r hasNext match {
        case true =>
          r next; val v = r.typed(0); if (r hasNext) {
                                  r.close; error("More than one row for unique result")
                                } else v
        case false => error("No rows in result")
      }
      case x => error("Knipis: " + x)
    }
  }  
}

/** Out parameter box for callable statement */
class OutPar(var value: Any) {
  private[tresql] var idx = 0
  def this() = this(null)
  override def toString = "OutPar(" + value + ")"
}
object OutPar {
  def apply() = new OutPar()
  def apply(value: Any) = new OutPar(value)
  def unapply(par: OutPar): Option[Any] = Some(par.value)
}

/** In out parameter box for callable statement */
class InOutPar(v: Any) extends OutPar(v) {
  override def toString = "InOutPar(" + value + ")"
}
object InOutPar {
  def apply(value: Any) = new InOutPar(value)
  def unapply(par: InOutPar): Option[Any] = Some(par.value)
}