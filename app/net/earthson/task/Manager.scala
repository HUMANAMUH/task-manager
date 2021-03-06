package net.earthson.task

import java.util.concurrent.Executors

import akka.actor.Actor
import com.typesafe.scalalogging.LazyLogging
import net.earthson.task.db.TaskDB
import play.api.Application
import slick.jdbc.GetResult

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.util._

object Manager {

  case object JobComplete

  case object TimeoutCleaned

}

class Manager(override val app: Application)
  extends Actor
    with TaskDB
    with LazyLogging {

  import Manager._
  import driver.api._

  var lock: Boolean = false
  val buffer: TQueue[(TaskCtrl, Promise[Any])] = new TQueue[(TaskCtrl, Promise[Any])]()

  implicit val execContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8))

  implicit val getTaskResult = GetResult(r => Task(r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<))

  context.system.scheduler.schedule(2 seconds, 1 seconds) {
    self ! DoTaskTimeout
  }

  def withTaskType[T <: TaskCtrl : ClassTag](cond: TaskCtrl => Boolean): Seq[(T, Promise[Any])] = {
    val todo = buffer.dequeueBulk {
      case (c, p) => cond(c)
    }
    todo.map(x => (x._1.asInstanceOf[T], x._2))
  }

  def bulkOp[T <: TaskCtrl : ClassTag](testF: T => Boolean = (_: T) => true)(opF: Seq[T] => Future[Seq[Any]]): Unit = {
    val todo = withTaskType[T] {
      case v: T => testF(v)
      case _ => false
    }
    val ctrls = todo.map(_._1)
    val promises = todo.map(_._2)
    val futureRes = opF(ctrls)
    futureRes.onComplete {
      _ => self ! JobComplete
    }
    futureRes.onComplete {
      case Success(vs) =>
        (vs zip promises).foreach {
          case (v, p) => p.success(v)
        }
      case Failure(e) =>
        logger.error("Operations Fail", e)
        promises.foreach {
          _.failure(e)
        }
    }
  }

  def headOp[T <: TaskCtrl : ClassTag](opF: T => Future[Any]): Unit = {
    buffer.dequeue() match {
      case Some((ctrl: T, promise)) =>
        val futureRes = opF(ctrl)
        futureRes.onComplete {
          _ => self ! JobComplete
        }
        futureRes.onComplete {
          case Success(vs) =>
            promise.success(vs)
          case Failure(e) =>
            logger.error("Operations Fail", e)
            promise.failure(e)
        }
      case _ =>
        logger.warn("Unexpected code here")
    }
  }

  def tryDoJob(): Unit =
  //    logger.debug(s"lock: $lock, buffer: ${buffer.size}")
    if (!lock && buffer.nonEmpty) {
      lock = true
      logger.debug(s"buffer size: ${buffer.size}")
      buffer.head._1 match {
        case FetchTask(pool, limit) =>
          bulkOp[FetchTask](_.pool == pool) {
            ctrls =>
              val size = ctrls.map(_.limit).sum
              val curTime = System.currentTimeMillis()
              taskDBConfig.db.run {
                {
                  for {
                    res <- TableTask.filter(_.pool === pool)
                      .filter(_.scheduledTime <= curTime) //task could be delay
                      .filter(_.status === Task.Status.Pending)
                      .sortBy(_.scheduledTime)
                      .take(size).result
                    _ <- {
                      val idset = res.map(_.id).mkString("(", ", ", ")")
                      if (res.nonEmpty) {
                        logger.debug(s"task size: ${res.size}")
                        sql"""
                           UPDATE task
                           SET
                           try_count = try_count + 1,
                           status = 'active',
                           start_time = ${curTime},
                           end_time = NULL,
                           timeout_at = ${curTime} + timeout * 1000
                           WHERE
                           id IN #${idset}
                    """.asUpdate
                      } else {
                        DBIO.successful(1)
                      }
                    }
                  } yield res
                }.transactionally
              }.map {
                res => {
                  val resBuff = Array.fill[List[Task]](ctrls.length)(Nil)
                  var cur = 0
                  res.foreach {
                    t => {
                      if (resBuff(cur).length >= ctrls(cur).limit) cur += 1
                      resBuff(cur) = t :: resBuff(cur)
                    }
                  }
                  logger.debug(s"${ctrls.length} - ${resBuff.size}")
                  resBuff
                }
              }
          }
        case _: FailTask =>
          bulkOp[FailTask]() {
            ctrls =>
              val curTime = System.currentTimeMillis()
              taskDBConfig.db.run {
                DBIO.seq(
                  ctrls.map {
                    case FailTask(id, log, delay) =>
                      val newPendingTime = curTime + delay.getOrElse(0L)
                      sqlu"""
                     UPDATE task SET
                     status = CASE WHEN try_count >= try_limit AND status != 'success' THEN 'fail' ELSE 'pending' END,
                     end_time = ${curTime},
                     timeout_at = NULL,
                     scheduled_time = CASE WHEN try_count >= try_limit AND status != 'success' THEN scheduled_time ELSE ${newPendingTime} END,
                     log = ${log}
                     WHERE id = ${id}
                """
                  }: _*
                ).transactionally
              }.map(_ => ctrls.map(_ => true))
          }
        case _: SucceedTask =>
          bulkOp[SucceedTask]() {
            ctrls =>
              val currentTime = System.currentTimeMillis()
              val ids = ctrls.map(_.id)
              val idsS = ids.mkString("(", ", ", ")")
              for {
                tasks <- taskDBConfig.db.run {
                  TableTask.filter(_.id inSet ids).result
                }.map(_.map(_.copy(
                  status = Task.Status.Success,
                  endTime = Some(currentTime),
                  log = "",
                  timeoutAt = None
                )))
                _ <- successDBConfig.db.run {
                  TableTask ++= tasks
                }
                _ <- taskDBConfig.db.run {
                  TableTask.filter(_.id inSet ids).delete
                }
              } yield ctrls.map(_ => true)
          }
        case _: AddTask =>
          bulkOp[AddTask]() {
            ctrls =>
              val tasks = ctrls.map(_.task)
              assert(tasks.map(_.id).toSet.size == tasks.size)
              taskDBConfig.db.run {
                DBIO.seq(
                  tasks.map {
                    t =>
                      sqlu"""
                         INSERT OR IGNORE INTO
                         task(
                         "id",
                         "pool",
                         "type",
                         "key",
                         "group",
                         "create_time",
                         "options",
                         "status",
                         "scheduled_at",
                         "scheduled_time",
                         "try_count",
                         "try_limit",
                         "timeout",
                         "log",
                         "timeout_at"
                         ) VALUES (
                        ${t.id},
                        ${t.pool},
                        ${t.`type`},
                        ${t.key},
                        ${t.group},
                        ${t.createTime},
                        ${t.options},
                        ${t.status},
                        ${t.scheduledAt},
                        ${t.scheduledTime},
                        ${t.tryCount},
                        ${t.tryLimit},
                        ${t.timeout},
                        ${t.log},
                        ${t.timeoutAt}
                        )
                    """
                  }: _*
                ).transactionally
              }.map(_ => ctrls.map(_ => true))
          }
        case _: DeleteTask =>
          bulkOp[DeleteTask]() {
            ctrls =>
              val ids = ctrls.map(_.id)
              taskDBConfig.db.run {
                TableTask.filter(_.id inSet ids).delete
              }.map(_ => ctrls.map(_ => true))
          }
        case _: BlockTask =>
          bulkOp[BlockTask]() {
            ctrls =>
              val ids = ctrls.map(_.id)
              taskDBConfig.db.run {
                TableTask
                  .filter(_.id inSet ids)
                  .filter(_.status === Task.Status.Pending)
                  .map(_.status)
                  .update(Task.Status.Block)
              }.map(_ => ctrls.map(_ => true))
          }
        case _: UnblockTask =>
          bulkOp[UnblockTask]() {
            ctrls =>
              val ids = ctrls.map(_.id)
              taskDBConfig.db.run {
                TableTask
                  .filter(_.id inSet ids)
                  .filter(_.status === Task.Status.Block)
                  .map(_.status)
                  .update(Task.Status.Pending)
              }.map(_ => ctrls.map(_ => true))
          }
        case _: RecoverTask =>
          //recover failed task
          bulkOp[RecoverTask]() {
            ctrls =>
              val ids = ctrls.map(_.id)
              taskDBConfig.db.run {
                TableTask
                  .filter(_.id inSet ids)
                  .filter(_.status === Task.Status.Failed)
                  .map(t => (t.status, t.tryCount))
                  .update((Task.Status.Pending, 0))
              }.map(_ => ctrls.map(_ => true))
          }
        case _: RecoverPool =>
          //recover failed task
          bulkOp[RecoverPool]() {
            ctrls =>
              val pools = ctrls.map(_.pool).toSet
              taskDBConfig.db.run {
                TableTask
                  .filter(_.pool inSet pools)
                  .filter(_.status === Task.Status.Failed)
                  .map(t => (t.status, t.tryCount))
                  .update((Task.Status.Pending, 0))
              }.map(_ => ctrls.map(_ => true))
          }
        case h: GetLastTask =>
          bulkOp[GetLastTask] {
            g => g.pool == h.pool
          } {
            ctrls =>
              val types = ctrls.map(c => s"""'${c.`type`}'""").mkString("(", ", ", ")")
              taskDBConfig.db.run {
                sql"""
                 SELECT task.*
                 FROM task
                 JOIN (
                    SELECT pool, "type", MAX(scheduled_at) AS scheduled_at
                    FROM task
                    WHERE pool=${h.pool} AND "type" IN #${types}
                    GROUP BY "type"
                 ) t
                 ON task.pool=t.pool AND task."type"=t."type" AND task.scheduled_at=t.scheduled_at
                 """.as[Task]
              }.map {
                res => {
                  val resMap = res.map {
                    t => (t.`type`, t)
                  }.toMap
                  ctrls.map(c => resMap.get(c.`type`))
                }
              }
          }
        case h: GetLastGroupTask =>
          bulkOp[GetLastGroupTask] {
            g => g.pool == h.pool
          } {
            ctrls =>
              val groups = ctrls.map(c => c.group.map(s => s"""'$s'""").getOrElse("NULL")).mkString("(", ", ", ")")
              taskDBConfig.db.run {
                sql"""
                 SELECT task.*
                 FROM task
                 JOIN (
                    SELECT pool, "group", MAX(scheduled_at) AS scheduled_at
                    FROM task
                    WHERE pool=${h.pool} AND "group" IN #${groups}
                    GROUP BY "group"
                 ) t
                 ON task.pool=t.pool AND task."group"=t."group" AND task.scheduled_at=t.scheduled_at
                 """.as[Task]
              }.map {
                res => {
                  val resMap = res.map {
                    t => (t.group, t)
                  }.toMap
                  ctrls.map(c => resMap.get(c.group))
                }
              }
          }
        case DoTaskTimeout =>
          bulkOp[DoTaskTimeout.type]() {
            ctrls => {
              val curTime = System.currentTimeMillis()
              taskDBConfig.db.run {
                sql"""
                      UPDATE task
                      SET
                      status = 'pending',
                      start_time = NULL,
                      timeout_at = NULL,
                      scheduled_time = ${curTime}
                      WHERE timeout_at < ${curTime}
                  """.asUpdate
              }.map(_ => ctrls.map(_ => TimeoutCleaned))
            }
          }

      }
    }

  override def receive: Receive = {
    case t: TaskCtrl =>
      //      logger.debug(s"TASK: $t")
      val dst = sender()
      val p = Promise[Any]()
      p.future.onComplete(dst ! _)
      buffer.enqueue(t -> p)
      tryDoJob()
    case JobComplete =>
      lock = false
      tryDoJob()
    case Success(TimeoutCleaned) =>
      logger.debug("Timeout Clearned")
    case Failure(e) =>
      logger.error("Error", e)
  }
}

