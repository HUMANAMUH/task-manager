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

  var lock  : Boolean                               = false
  var buffer: ArrayBuffer[(TaskCtrl, Promise[Any])] = new collection.mutable.ArrayBuffer[(TaskCtrl, Promise[Any])]()

  implicit val execContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(32))

  def withTaskType[T <: TaskCtrl : ClassTag](cond: TaskCtrl => Boolean): Seq[(T, Promise[Any])] = {
    val (todo, newBuffer) = buffer.partition {
      case (c, p) => cond(c)
    }
    buffer = newBuffer
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
          case (v, p) => p.complete(Success(v))
        }
      case Failure(e) =>
        logger.error("Operations Fail", e)
        promises.foreach {
          _.failure(e)
        }
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
                      .filter(x => x.status === Task.Status.Pending || {
                        x.status === Task.Status.Active && (((LiteralColumn(curTime).bind - x.startTime.getOrElse(0L)) / LiteralColumn(1000000L).bind) > x.timeout)
                      })
                      .sortBy(_.pendingTime)
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
                    case FailTask(id, log) =>
                      sqlu"""
                     UPDATE task SET
                     status = CASE WHEN try_count >= try_limit AND status != 'success' THEN 'fail' ELSE 'pending' END,
                     end_time = ${curTime},
                     pending_time = CASE WHEN try_count >= try_limit AND status != 'success' THEN pending_time ELSE ${curTime} END,
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
                         INSERT OR IGNORE INTO task("id", "pool", "type", "key", "options", "status", "pending_time", "try_count", "try_limit", "timeout", "log")
                         VALUES(${t.id}, ${t.pool}, ${t.`type`}, ${t.key}, ${t.options}, ${t.status}, ${t.pendingTime}, ${t.tryCount}, ${t.tryLimit}, ${t.timeout}, ${t.log})
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
        case _: BlockTask => {
          logger.warn("Unsupported operation")
        }
      }
    }

  override def receive: Receive = {
    case t: TaskCtrl => {
      //      logger.debug(s"TASK: $t")
      val dst = sender()
      val p = Promise[Any]()
      p.future.onComplete(dst ! _)
      buffer.append(t -> p)
      tryDoJob()
    }
    case JobComplete => {
      lock = false
      tryDoJob()
    }
  }
}

