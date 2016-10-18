package net.earthson.task

import java.util.concurrent.Executors

import akka.actor.Actor
import com.typesafe.scalalogging.LazyLogging
import net.earthson.task.Manager.JobComplete
import net.earthson.task.db.TaskDB
import play.api.db.slick.DatabaseConfigProvider

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Promise}
import scala.reflect.ClassTag
import scala.util._

class Manager(implicit override val databaseConfigProvider: DatabaseConfigProvider)
  extends Actor
    with TaskDB
    with LazyLogging {

  import driver.api._

  var lock: Boolean = false
  var buffer: ArrayBuffer[(TaskCtrl, Promise[Any])] = new collection.mutable.ArrayBuffer[(TaskCtrl, Promise[Any])]()

  implicit val execContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(32))

  def withTaskType[T <: TaskCtrl : ClassTag](cond: TaskCtrl => Boolean): Seq[(T, Promise[Any])] = {
    val (todo, newBuffer) = buffer.partition {
      case (c, p) => cond(c)
    }
    buffer = newBuffer
    todo.map(x => (x._1.asInstanceOf[T], x._2))
  }

  def tryDoJob(): Unit = {
//    logger.debug(s"lock: $lock, buffer: ${buffer.size}")
    if (!lock && buffer.nonEmpty) {
      lock = true
      logger.debug(s"buffer size: ${buffer.size}")
      buffer.head._1 match {
        case FetchTask(pool, limit) => {
          logger.debug("fetch task")
          val typedTODO = withTaskType[FetchTask] {
            case FetchTask(p, l) => p == pool
            case _ => false
          }
          val size = typedTODO.map(_._1.limit).sum

          val curTime = System.nanoTime()
          val x = LiteralColumn(curTime)
          val futureRes = {
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
            }
          }
          futureRes.onComplete {
            case Success(tasks) => {
              logger.debug("fetch task success")
              typedTODO.foldLeft(tasks) {
                (ts, c) => {
                  if (ts.nonEmpty) {
                    val limit = c._1.limit
                    c._2.complete(Success(ts.take(limit)))
                    ts.drop(limit)
                  } else {
                    c._2.complete(Success(Nil))
                    ts
                  }
                }
              }
            }
            case err => typedTODO.map(_._2).foreach(_.complete(err))
          }
          futureRes.onComplete(_ => self ! JobComplete)
        }
        case _: FailTask => {
          logger.debug("fail task")
          val typedTODO = withTaskType[FailTask](_.isInstanceOf[FailTask])
          val tasks = typedTODO.map(_._1)
          val curTime = System.nanoTime()
          val futureRes = db.run {
            DBIO.seq(
              tasks.map {
                case FailTask(id, log) =>
                  sqlu"""
                     UPDATE task SET
                     status = CASE WHEN try_count >= try_limit AND status != 'success' THEN 'fail' ELSE 'pending' END,
                     end_time = ${curTime},
                     pending_time = CASE WHEN try_count >= try_limit AND status != 'success' THEN pending_time ELSE ${curTime} END,
                     log = ${log}
                     WHERE id = ${id}
                """
              } :_*
            )
          }
          futureRes.onComplete {
            case Success(_) => typedTODO.foreach(_._2.complete(Success(true)))
            case err => typedTODO.foreach(_._2.complete(err))
          }
          futureRes.onComplete(_ => self ! JobComplete)
        }
        case _: SucceedTask => {
          logger.debug("succeed task")
          val typedTODO = withTaskType[SucceedTask](_.isInstanceOf[SucceedTask])
          val ids = typedTODO.map(_._1.id)
          val idsS = ids.mkString("(", ", ", ")")
          val futureRes = db.run {
            sql"""
                   UPDATE task SET status = 'success', end_time = ${System.nanoTime()}, log = ''
                   WHERE id IN #${idsS}
              """.asUpdate
          }
          futureRes.onComplete {
            case Success(_) => typedTODO.foreach(_._2.complete(Success(true)))
            case err => typedTODO.foreach(_._2.complete(err))
          }
          futureRes.onComplete(_ => self ! JobComplete)
        }
        case _: AddTask => {
          logger.debug("add task")
          val typedTODO = withTaskType[AddTask](_.isInstanceOf[AddTask])
          val tasks = typedTODO.map(_._1.task)
          assert(tasks.map(_.id).toSet.size == tasks.size)
          val futureRes = db.run {
            DBIO.seq(
              tasks.map {
                t =>
                  sqlu"""
                         INSERT OR IGNORE INTO task("id", "pool", "type", "key", "options", "status", "pending_time", "try_count", "try_limit", "timeout", "log")
                         VALUES(${t.id}, ${t.pool}, ${t.`type`}, ${t.key}, ${t.options}, ${t.status}, ${t.pendingTime}, ${t.tryCount}, ${t.tryLimit}, ${t.timeout}, ${t.log})
                    """
              }: _*
            ).transactionally
          }
          futureRes.onComplete {
            case Success(_) => {
              logger.debug("add task success")
              typedTODO.foreach(_._2.complete(Success(true)))
            }
            case err => {
              logger.error("add task failed", err)
              typedTODO.foreach(_._2.complete(err))
            }
          }
          futureRes.onComplete(_ => self ! JobComplete)
        }
        case _: DeleteTask => {
          logger.debug("delete task")
          val typedTODO = withTaskType[DeleteTask](_.isInstanceOf[DeleteTask])
          val ids = typedTODO.map(_._1.id)
          val futureRes = db.run {
            TableTask.filter(_.id inSet ids).delete
          }
          futureRes.onComplete {
            case Success(_) => typedTODO.foreach(_._2.complete(Success(true)))
            case err => typedTODO.foreach(_._2.complete(err))
          }
          futureRes.onComplete(_ => self ! JobComplete)
        }
        case _: BlockTask => {
          logger.warn("Unsupported operation")
        }
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

object Manager {

  case object JobComplete

}
