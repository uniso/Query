package uniso.query

class Env(private val md: MetaData,  private val connection: java.sql.Connection,
    private val provider: EnvProvider)
  extends (String => Any) with MetaData {

  private val vars: ThreadLocal[scala.collection.mutable.Map[String, Any]] = new ThreadLocal()

  def this(provider: EnvProvider) = this(null, null, provider)
  def this(vars: Map[String, Any], md: MetaData, connection: java.sql.Connection) = {
    this(md, connection, null)
    update(vars)
  }

  def apply(name: String) = if (provider != null) provider.env(name) else vars.get()(name) match {
    case e: Expr => e()
    case x => x
  }
  def update(name: String, value: Any) {
    if (provider != null) provider.env(name) = value else this.vars.get()(name) = value
  }
  def update(vars: Map[String, Any]) {
    if (provider != null) provider.env.update(vars) 
    else this.vars set scala.collection.mutable.Map(vars.toList: _*)
  }
  def contains(name: String): Boolean = if (provider != null) provider.env.contains(name)
  else vars.get().contains(name)
  override implicit def conn = if (provider != null) provider.env.conn else connection
  def dbName = if (provider != null) provider.env.dbName else md.dbName
  override def table(name: String)(implicit conn: java.sql.Connection) = if (provider != null)
    provider.env.table(name) else md.table(name)(conn)
  private var res: Result = null
  def result(r: Result) = this.res = r
  def apply(rIdx: Int) = {
    var i = 0
    var e: Env = this
    while (i < rIdx && e != null) {
      if (e.provider != null) e = e.provider.env else e = null
      i += 1
    }
    if (i == rIdx && e != null) e.res else error("Result not available at index: " + rIdx)
  }
}

object Env {
  private var md: MetaData = null
  def apply(params: Map[String, String])(implicit connection: java.sql.Connection): Env = {
    new Env(params mapValues (Query(_)(connection)), md, connection)
  }
  def apply(params: Map[String, Any], parseParams: Boolean)(implicit connection: java.sql.Connection): Env = {
    if (parseParams) apply(params.asInstanceOf[Map[String, String]])(connection)
    else new Env(params, md, connection)
  }
  def metaData(md: MetaData) = this.md = md
}

trait EnvProvider {
  def env: Env
}