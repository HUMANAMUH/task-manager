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

    def idx = index("idx_query", (pool, `type`, key))

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

  abstract class TableTaskQueue(tag: Tag, tableName: String) extends Table[TaskIn](tag, tableName) {
    def id = column[Long]("id", O.PrimaryKey)

    def time = column[Long]("time")

    def fk = foreignKey("id", id, TableTask)(_.id, onDelete = ForeignKeyAction.Cascade)

    def * = (id, time) <> ((TaskIn.apply _).tupled, TaskIn.unapply)
  }

  class TableTaskPending(tag: Tag) extends TableTaskQueue(tag, "task_pending")

  val TableTaskPending = TableQuery[TableTaskPending]

  class TableTaskSuccess(tag: Tag) extends TableTaskQueue(tag, "task_success")

  val TableTaskSuccess = TableQuery[TableTaskSuccess]

  class TableTaskFail(tag: Tag) extends TableTaskQueue(tag, "task_fail")

  val TableTaskFail = TableQuery[TableTaskFail]

  class TableTaskBlock(tag: Tag) extends TableTaskQueue(tag, "task_block")

  val TableTaskBlock = TableQuery[TableTaskBlock]

  val tables =
    Seq(
      TableTask,
      TableTaskPending,
      TableTaskSuccess,
      TableTaskFail,
      TableTaskBlock
    )
}