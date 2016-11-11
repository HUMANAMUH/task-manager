package net.earthson.task.db

import java.sql.SQLException

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import play.api.Application
import play.api.db.slick.DatabaseConfigProvider
import slick.driver.{JdbcProfile, SQLiteDriver}

import scala.util.{Failure, Success, Try}

trait TaskDB
  extends TaskTables
    with LazyLogging {

  implicit def app: Application

  val taskDBConfig = DatabaseConfigProvider.get[JdbcProfile]("default")

  val successDBConfig = DatabaseConfigProvider.get[JdbcProfile]("success")

  val driver = SQLiteDriver

  def doOnComplete[T](dst: ActorRef, id: Option[String] = None)(result: Try[T]): Unit = result match {
    //case Failure(_: MySQLIntegrityConstraintViolationException) => dst ! Failure(KeyDuplicateError(id.get))
    case Failure(e: SQLException) => {
      dst ! Failure(e)
      var ex = e
      while(ex.getNextException != null) {
        ex = ex.getNextException
        logger.error(ex.getMessage, ex)
      }
    }
    case Failure(e: Exception) => dst ! Failure(e)
    case Success(x) => dst ! Success(x)
    case Failure(e: Throwable) => throw e
  }
}

/**
  * Created by Earthson on 5/21/15.
  *
  */
object TaskDB
  extends TaskTables {

  override protected val driver: JdbcProfile = SQLiteDriver
  import driver.SchemaDescription
  import driver.api._

  def evolutionStr(schemas: Seq[SchemaDescription]) = {
    val createAll =
      schemas.flatMap(_.createStatements.map(_ + ";")).mkString("\n")
    val dropAll =
      schemas.reverse.flatMap(_.dropStatements.map(_ + ";")).mkString("\n")
    s"""
       |# --- !Ups
       |
       |$createAll
       |
       |# --- !Downs
       |
       |$dropAll
       |
     """.stripMargin
  }

  def writeToFile(path: String)(f: => String): Unit = {
    import java.io._
    val pw = new PrintWriter(new File(path))
    pw.write(f)
    pw.close()
  }

  def writeEvolutions(version: Int): Unit = {
    writeToFile(s"conf/evolutions/default/$version.sql")(evolutionStr(TableTask.schema :: Nil))
    writeToFile(s"conf/evolutions/success/$version.sql")(evolutionStr(TableSuccess.schema :: Nil))
  }
}