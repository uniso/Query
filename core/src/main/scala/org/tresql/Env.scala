package org.tresql

import sys._

/* Environment for expression building and execution */
/* TODO provider vs resourceProvider? Some clarification or reorganization needed perhaps. */
class Env(private val provider: EnvProvider, private val resourceProvider: ResourceProvider,
  val reusableExpr: Boolean) extends MetaData {

  private var providedEnvs: List[Env] = Nil

  if (provider != null) {
    def rootEnv(e: Env): Env = if (e.provider == null) e else rootEnv(e.provider.env)
    val root = rootEnv(this)
    root.providedEnvs = this :: root.providedEnvs
  }

  private val _vars = new ThreadLocal[scala.collection.mutable.Map[String, Any]]
  private def vars = _vars.get
  private val res = new ThreadLocal[Result]
  private val st = new ThreadLocal[java.sql.PreparedStatement]
  private val ids = new ThreadLocal[scala.collection.mutable.Map[String, Any]]
  ids set scala.collection.mutable.Map[String, Any]()

  def this(provider: EnvProvider, reusableExpr: Boolean) = this(provider, null, reusableExpr)
  def this(vars: Map[String, Any], resourceProvider: ResourceProvider, reusableExpr: Boolean) = {
    this(null.asInstanceOf[EnvProvider], resourceProvider, reusableExpr)
    update(vars)
  }

  def apply(name: String):Any = if (vars != null) vars(name) else provider.env(name) match {
    case e: Expr => e()
    case x => x
  }

  def conn: java.sql.Connection = if (provider != null) provider.env.conn else resourceProvider.conn
  def dialect: Expr => String = if (provider != null) provider.env.dialect else resourceProvider.dialect

  private def metadata = if (resourceProvider.metaData != null) resourceProvider.metaData
                         else error("Meta data not set. Shortcut syntax not available.")
  //meta data methods
  def dbName = if (provider != null) provider.env.dbName else metadata.dbName
  def table(name: String) = if (provider != null) provider.env.table(name) else metadata.table(name)
  def procedure(name: String) = if (provider != null) provider.env.procedure(name)
      else metadata.procedure(name)

  def apply(rIdx: Int) = {
    var i = 0
    var e: Env = this
    while (i < rIdx && e != null) {
      if (e.provider != null) e = e.provider.env else e = null
      i += 1
    }
    if (i == rIdx && e != null) e.res.get else error("Result not available at index: " + rIdx)
  }

  def statement = this.st.get

  def contains(name: String): Boolean = if (vars != null) vars.contains(name)
  else provider.env.contains(name)

  def update(name: String, value: Any) {
    this.vars(name) = value
  }

  def update(vars: Map[String, Any]) {
    this._vars set scala.collection.mutable.Map(vars.toList: _*)
  }

  def update(r: Result) = this.res set r
  def update(st: java.sql.PreparedStatement) = this.st set st

  def closeStatement {
    val st = this.st.get
    if (st != null) {
      this.st set null
      st.close
    }
    this.providedEnvs foreach (_.closeStatement)
  }
  
  def nextId(seqName: String): Any = if (provider != null) provider.env.nextId(seqName) else {
    //TODO perhaps built expressions can be used?
    val id = Query.unique[Any](resourceProvider.idExpr(seqName))
    ids.get(seqName) = id
    id
  }
  def currId(seqName: String): Any = if (provider != null) provider.env.currId(seqName) else ids.get()(seqName)

}

object Env extends ResourceProvider {
  private var logger: (=> String, Int) => Unit = null
  //meta data object must be thread safe!
  private var md: MetaData = metadata.JDBCMetaData("")
  private val threadConn = new ThreadLocal[java.sql.Connection]
  private var sqlDialect: Expr => String = null
  private var _idExpr: String => String = s => "nextval('" + s + "')" 
  //this is for scala interperter since it executes every command in separate thread from console thread
  var sharedConn: java.sql.Connection = null
  //available functions
  private var functions = Functions.getClass.getDeclaredMethods map (_.getName) toSet
  def apply(params: Map[String, Any], reusableExpr: Boolean): Env = {
    new Env(params, Env, reusableExpr)
  }
  def conn = { val c = threadConn.get; if (c == null) sharedConn else c }
  def metaData = md
  def dialect = sqlDialect
  def idExpr = _idExpr 
  def update(md: MetaData) = this.md = md
  def update(conn: java.sql.Connection) = this.threadConn set conn
  def update(logger: (=> String, Int) => Unit) = this.logger = logger
  def update(dialect: Expr => String) = this.sqlDialect = dialect
  def updateIdExpr(idExpr: String => String) = this._idExpr = idExpr
  def availableFunctions(list: Traversable[String]) = functions = list.toSet
  def isDefined(functionName: String) = functions.contains(functionName) 
  def log(msg: => String, level: Int = 0): Unit = if (logger != null) logger(msg, level)
}

trait EnvProvider {
  def env: Env
}

trait ResourceProvider {
  def conn: java.sql.Connection
  def metaData: MetaData
  def dialect: Expr => String
  def idExpr: String => String
}