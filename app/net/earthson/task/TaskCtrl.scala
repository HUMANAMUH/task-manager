package net.earthson.task

import play.api.libs.json.Json

/**
  * Created by earthson on 10/12/16.
  */
sealed trait TaskCtrl

case class FetchTask(pool: String, limit: Int = 1) extends TaskCtrl

case class AddTask(
                       pool: String,
                       `type`: String,
                       key: String,
                       options: String,
                       tryLimit: Int,
                       timeout: Long
                     ) extends TaskCtrl {
  lazy val task = {
    val id = System.nanoTime()
    Task(id, pool, `type`, key, id, options, Task.Status.Pending, tryLimit = tryLimit, timeout = timeout)
  }
}

object AddTask {
  implicit val fmt = Json.format[AddTask]
}

case class FailTask(id: Long) extends TaskCtrl

case class SucceedTask(id: Long) extends TaskCtrl

case class BlockTask(id: Long) extends TaskCtrl

case class DeleteTask(id: Long) extends TaskCtrl

