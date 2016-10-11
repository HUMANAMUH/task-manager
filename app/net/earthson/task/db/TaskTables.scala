package net.earthson.task.db

import net.earthson.task.Task
import slick.driver.JdbcProfile

trait TaskTables {
  protected val driver: JdbcProfile

  import driver.api._


  /**
    *
    * @param tag
    */
  class TableTask(tag: Tag) extends Table[Task](tag, "algo_pack") {
    def pool = column[String]("pool", O.PrimaryKey, O.SqlType("VARCHAR(128)"))

    def `type` = column[String]("type", O.SqlType("VARCHAR(32)"))

    def key = column[String]("key", O.SqlType("VARCHAR(128)"))

    def rangeL = column[Long]("range_l")

    def rangeR = column[Long]("range_r")

    def createTime = column[Long]("create_time")

    def startTime = column[Option[Long]]("start_time")

    def endTime = column[Option[Long]]("end_time")

    def options = column[String]("options")

    def status = column[String]("status")

    def tryCount = column[Int]("tryCount")

    def tryLimit = column[Int]("tryLimit")

    def log = column[String]("log")

    def * =
      (
        pool,
        `type`,
        key,
        rangeL,
        rangeR,
        createTime,
        options,
        status,
        startTime,
        endTime,
        tryCount,
        tryLimit,
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