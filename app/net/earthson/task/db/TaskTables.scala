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

    def group = column[Option[String]]("group")

    def createTime = column[Long]("create_time")

    def scheduledAt = column[Long]("scheduled_at")

    def scheduledTime = column[Long]("scheduled_time")

    def startTime = column[Option[Long]]("start_time")

    def endTime = column[Option[Long]]("end_time")

    def options = column[String]("options")

    def status = column[String]("status")

    def tryCount = column[Int]("try_count")

    def tryLimit = column[Int]("try_limit")

    def timeout = column[Long]("timeout")

    def log = column[String]("log")

    def timeoutAt = column[Option[Long]]("timeout_at")

    def idx1 = index("idx_query", (pool, `type`, key), unique = true)

    def idx2 = index("idx_status", (status, scheduledTime))

    def idx3 = index("idx_group_sched", (pool, group, scheduledAt))

    def idx4 = index("idx_type_sched", (pool, `type`, scheduledAt))

    def * =
      (
        id,
        pool,
        `type`,
        key,
        group,
        createTime,
        options,
        status,
        scheduledAt,
        scheduledTime,
        startTime,
        endTime,
        tryCount,
        tryLimit,
        timeout,
        log,
        timeoutAt
        ) <>
        ((Task.apply _).tupled, Task.unapply)
  }

  val TableTask = TableQuery[TableTask]

  val tables =
    Seq(
      TableTask
    )
}