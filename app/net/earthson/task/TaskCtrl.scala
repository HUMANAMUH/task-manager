package net.earthson.task

/**
  * Created by earthson on 10/12/16.
  */
sealed trait TaskCtrl

case class FetchTask(pool: String) extends TaskCtrl

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

case class FailTask(id: String) extends TaskCtrl

case class SucceedTask(id: String) extends TaskCtrl

case class BlockTask(id: String) extends TaskCtrl

