package net.earthson.task

import akka.actor.Actor
import com.typesafe.scalalogging.LazyLogging
import net.earthson.task.Manager.JobComplete
import net.earthson.task.db.TaskDB
import play.api.db.slick.DatabaseConfigProvider

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Promise
import scala.util._

class Manager(implicit override val databaseConfigProvider: DatabaseConfigProvider)
  extends Actor
    with TaskDB
    with LazyLogging {

  import driver.api._

  var lock: Boolean = false
  var buffer: ArrayBuffer[(TaskCtrl, Promise[Any])] = new collection.mutable.ArrayBuffer[(TaskCtrl, Promise[Any])]()

  def tryDoJob(): Unit = {
    if (!lock && buffer.nonEmpty) {
      lock = true
      buffer.head._1 match {
        case FetchTask(pool, limit) => {
          val (todo, newBuffer) = buffer.partition {
            _._1 match {
              case FetchTask(p, l) => p == pool
              case _ => false
            }
          }
          val typedTODO = todo.map(x => (x._1.asInstanceOf[FetchTask], x._2))
          val size = typedTODO.map(_._1.limit).sum

          buffer = newBuffer

          val curTime = System.nanoTime()
          val x = LiteralColumn(curTime)
          val futureRes = {
            db.run {
              {
                for {
                  res <- TableTask.filter(_.pool === pool)
                    .filter(x => x.status === Task.Status.Pending || {
                      x.status === Task.Status.Active && (((LiteralColumn(curTime).bind - x.startTime.get) / LiteralColumn(1000000L).bind) > x.timeout)
                    })
                    .take(size).result
                  _ <-
                  sqlu"""
                           UPDATE task
                           SET
                           tryCount = tryCount + 1,
                           status = 'active',
                           start_time = ${curTime},
                           end_time = NULL,
                           WHERE
                           id IN ${res.map(_.id).mkString("(", ", ", ")")}
                    """
                } yield res
              }.transactionally
            }
          }
          futureRes.onComplete {
            case Success(tasks) => {
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
            case err => todo.map(_._2).foreach(_.complete(err))
          }
          futureRes.onComplete(_ => self ! JobComplete)
        }
        case _: FailTask => {
          val (todo, newBuffer) = buffer.partition {
            _._1.isInstanceOf[FailTask]
          }
          buffer = newBuffer
          val typedTODO = todo.map(x => (x._1.asInstanceOf[FailTask], x._2))
          val ids: ArrayBuffer[Long] = typedTODO.map(_._1.id)
          val idsS = ids.mkString("(", ", ", ")")
          val futureRes = db.run {
            sqlu"""
                     UPDATE task SET status = CASE WHEN tryCount >= tryLimit AND status != 'success' THEN 'fail' ELSE 'pending' END, end_time = ${System.nanoTime()}
                     WHERE id IN ${idsS}
                """
          }
          futureRes.onComplete {
            case Success(_) => typedTODO.foreach(_._2.complete(Success(true)))
            case err => typedTODO.foreach(_._2.complete(err))
          }
          futureRes.onComplete(_ => self ! JobComplete)
        }
        case _: SucceedTask => {
          val (todo, newBuffer) = buffer.partition {
            _._1.isInstanceOf[SucceedTask]
          }
          buffer = newBuffer
          val typedTODO = todo.map(x => (x._1.asInstanceOf[FailTask], x._2))
          val ids = typedTODO.map(_._1.id)
          val idsS = ids.mkString("(", ", ", ")")
          val futureRes = db.run {
            sqlu"""
                   UPDATE task SET status = 'success', end_time = ${System.nanoTime()}
                   WHERE id IN ${idsS}
              """
          }
          futureRes.onComplete {
            case Success(_) => typedTODO.foreach(_._2.complete(Success(true)))
            case err => typedTODO.foreach(_._2.complete(err))
          }
          futureRes.onComplete(_ => self ! JobComplete)
        }
      }
    }
  }

  override def receive: Receive = {
    case t: TaskCtrl => {
      val dst = sender()
      val p = Promise[Any]()
      p.future.onComplete(dst ! _)
      tryDoJob()
      buffer.append(t -> p)
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
