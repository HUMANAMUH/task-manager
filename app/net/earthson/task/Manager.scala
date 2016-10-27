package net.earthson.task

import java.util.concurrent.Executors

import akka.actor.Actor
import com.typesafe.scalalogging.LazyLogging
import net.earthson.task.db.TaskDB
import play.api.db.slick.DatabaseConfigProvider

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.util._

object Manager {

  case object JobComplete

}

class Manager(implicit override val databaseConfigProvider: DatabaseConfigProvider)
  extends Actor
    with TaskDB
    with LazyLogging {

  import Manager._
  import driver.api._

  var lock: Boolean = false
  val buffer: TQueue[(TaskCtrl, Promise[Any])] = new TQueue[(TaskCtrl, Promise[Any])]()

  implicit val execContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(32))

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
              val curTime = System.nanoTime()
              db.run {
                {
                  for {
                    res <- TableTask.filter(_.pool === pool)
                      .filter(_.scheduledTime <= curTime) //task could be delay
                      .filter(x => x.status === Task.Status.Pending || {
                      x.status === Task.Status.Active && (((LiteralColumn(curTime).bind - x.startTime.getOrElse(0L)) / LiteralColumn(1000000L).bind) > x.timeout)
                    })
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
                           end_time = NULL
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
              val curTime = System.nanoTime()
              db.run {
                DBIO.seq(
                  ctrls.map {
                    case FailTask(id, log, delay) =>
                      val newPendingTime = curTime + delay.getOrElse(0L) * 1000000
                      sqlu"""
                     UPDATE task SET
                     status = CASE WHEN try_count >= try_limit AND status != 'success' THEN 'fail' ELSE 'pending' END,
                     end_time = ${curTime},
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
              val ids = ctrls.map(_.id)
              val idsS = ids.mkString("(", ", ", ")")
              db.run {
                sql"""
                   UPDATE task SET status = 'success', end_time = ${System.nanoTime()}, log = ''
                   WHERE id IN #${idsS}
              """.asUpdate
              }.map(_ => ctrls.map(_ => true))
          }
        case _: AddTask =>
          bulkOp[AddTask]() {
            ctrls =>
              val tasks = ctrls.map(_.task)
              assert(tasks.map(_.id).toSet.size == tasks.size)
              db.run {
                DBIO.seq(
                  tasks.map {
                    t =>
                      sqlu"""
                         INSERT OR IGNORE INTO task("id", "pool", "type", "key", "group", "options", "status", "scheduled_at", "scheduled_time", "try_count", "try_limit", "timeout", "log")
                         VALUES(${t.id}, ${t.pool}, ${t.`type`}, ${t.key}, ${t.group}, ${t.options}, ${t.status}, ${t.scheduledAt}, ${t.scheduledTime}, ${t.tryCount}, ${t.tryLimit}, ${t.timeout}, ${t.log})
                    """
                  }: _*
                ).transactionally
              }.map(_ => ctrls.map(_ => true))
          }
        case _: DeleteTask =>
          bulkOp[DeleteTask]() {
            ctrls =>
              val ids = ctrls.map(_.id)
              db.run {
                TableTask.filter(_.id inSet ids).delete
              }.map(_ => ctrls.map(_ => true))
          }
        case _: BlockTask =>
          bulkOp[BlockTask]() {
            ctrls =>
              val ids = ctrls.map(_.id)
              db.run {
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
              db.run {
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
              db.run {
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
              db.run {
                TableTask
                  .filter(_.pool inSet pools)
                  .filter(_.status === Task.Status.Failed)
                  .map(t => (t.status, t.tryCount))
                  .update((Task.Status.Pending, 0))
              }.map(_ => ctrls.map(_ => true))
          }
        case _: GetLastTask =>
          headOp[GetLastTask] {
            case GetLastTask(pool, tp) =>
              db.run {
                TableTask
                  .filter(_.pool === pool)
                  .filter(_.`type` === tp)
                  .sortBy(_.scheduledAt)
                  .take(1)
                  .result
              }.map(_.headOption)
          }
        case _: GetLastGroupTask =>
          headOp[GetLastGroupTask] {
            case GetLastGroupTask(pool, group) =>
              db.run {
                TableTask
                  .filter(_.pool === pool)
                  .filter(_.group === group)
                  .sortBy(_.scheduledAt)
                  .take(1)
                  .result
              }.map(_.headOption)
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
  }
}

