package net.earthson.task

import play.api.libs.json.Json

/**
  * Created by earthson on 10/12/16.
  */
sealed trait TaskCtrl

case class FetchTask(pool: String, limit: Int = 1) extends TaskCtrl

object FetchTask {
  implicit val fmt = Json.format[FetchTask]
}

case class AddTask(
                       pool: String,
                       `type`: String,
                       key: String,
                       options: String,
                       tryLimit: Int,
                       timeout: Long
                     ) extends TaskCtrl {
  lazy val task = {
    val curTime = AddTask.getIdTime
    Task(curTime, pool, `type`, key, curTime, options, Task.Status.Pending, pendingTime = curTime, tryLimit = tryLimit, timeout = timeout)
  }
}

object AddTask {
  var prevTime = System.nanoTime()

  def getIdTime = {
    while(System.nanoTime() == prevTime) {}
    prevTime = System.nanoTime()
    prevTime
  }

  implicit val fmt = Json.format[AddTask]
}

case class FailTask(id: Long, log: String) extends TaskCtrl

object FailTask {
  implicit val fmt = Json.format[FailTask]
}

case class SucceedTask(id: Long) extends TaskCtrl

object SucceedTask {
  implicit val fmt = Json.format[SucceedTask]
}

case class BlockTask(id: Long) extends TaskCtrl

object BlockTask {
  implicit val fmt = Json.format[BlockTask]
}

case class DeleteTask(id: Long) extends TaskCtrl

object DeleteTask {
  implicit val fmt = Json.format[DeleteTask]
}

