package net.earthson.task.db

import net.earthson.task.{Task, TaskIn}
import slick.driver.JdbcProfile

trait TaskTables {
  protected val driver: JdbcProfile

  import driver.api._


  /**
    *
    * @param tag
    */
  class TableTask(tag: Tag) extends Table[Task](tag, "task") {
    def id = column[Long]("id", O.PrimaryKey)

    def pool = column[String]("pool")

    def `type` = column[String]("type")

    def key = column[String]("key")

    def startTime = column[Option[Long]]("start_time")

    def endTime = column[Option[Long]]("end_time")

    def options = column[String]("options")

    def status = column[String]("status")

    def tryCount = column[Int]("tryCount")

    def tryLimit = column[Int]("tryLimit")

    def timeout = column[Long]("timeout")

    def log = column[String]("log")

    def idx1 = index("idx_query", (pool, `type`, key), unique = true)

    def idx2 = index("idx_status", status)

    def * =
      (
        id,
        pool,
        `type`,
        key,
        id,
        options,
        status,
        startTime,
        endTime,
        tryCount,
        tryLimit,
        timeout,
        log
        ) <>
        ((Task.apply _).tupled, Task.unapply)
  }

  val TableTask = TableQuery[TableTask]

  val tables =
    Seq(
      TableTask
    )
}