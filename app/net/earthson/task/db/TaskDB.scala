package net.earthson.task.db

import java.sql.SQLException

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfig}
import slick.driver.{JdbcProfile, PostgresDriver}

import scala.util.{Failure, Success, Try}

trait TaskDB
  extends TaskTables
    with HasDatabaseConfig[JdbcProfile]
    with LazyLogging {
  def dbConfigProvider: DatabaseConfigProvider

  override val dbConfig = dbConfigProvider.get[JdbcProfile]

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
object WhaleDB
  extends TaskTables {

  override protected val driver: JdbcProfile = PostgresDriver
  import driver.api._

  def evolutionStr() = {
    val createAll =
      tables.flatMap(_.schema.createStatements.map(_ + ";")).mkString("\n")
    val dropAll =
      tables.reverse.flatMap(_.schema.dropStatements.map(_ + ";")).mkString("\n")
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

  def genEvolutions(version: Int) = {
    import java.io._
    val pw = new PrintWriter(new File(s"conf/evolutions/default/$version.sql"))
    pw.write(evolutionStr())
    pw.close()
  }
}