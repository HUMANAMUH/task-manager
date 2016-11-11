package net.earthson.task.db

import net.earthson.task.Task
import slick.driver.JdbcProfile

trait TaskTables {
  protected val driver: JdbcProfile

  import driver.api._


  abstract class TableTaskBase(tag: Tag) extends Table[Task](tag, "task") {

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

    def idxStatus = index("idx_status", (status, scheduledTime))

    def idxGroupSched = index("idx_group_sched", (pool, group, scheduledAt))

    def idxTypeSched = index("idx_type_sched", (pool, `type`, scheduledAt))

    def projection =
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

  class TableTask(tag: Tag) extends TableTaskBase(tag) {
    def idxMain = index("idx_query", (pool, `type`, key), unique = true)

    override def * = projection
  }

  class TableSuccess(tag: Tag) extends TableTaskBase(tag) {
    def idxMain = index("idx_query", (pool, `type`, key))

    override def * = projection
  }

  val TableTask = TableQuery[TableTask]

  val TableSuccess = TableQuery[TableSuccess]
}