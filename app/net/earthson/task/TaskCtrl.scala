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

/**
  *
  * @param pool
  * @param `type`
  * @param key
  * @param options
  * @param scheduledTime in milliseconds
  * @param tryLimit
  * @param timeout
  */
case class AddTask(
                       pool: String,
                       `type`: String,
                       key: String,
                       options: String,
                       scheduledTime: Option[Long],
                       tryLimit: Int,
                       timeout: Long
                     ) extends TaskCtrl {
  lazy val task = {
    val curTime = AddTask.getIdTime
    val destTime = scheduledTime.map(_ * 1000000).getOrElse(curTime)
    Task(curTime, pool, `type`, key, curTime, options, Task.Status.Pending, scheduledTime = destTime, tryLimit = tryLimit, timeout = timeout)
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

/***
  *
  * @param id
  * @param log
  * @param delay milliseconds to delay
  */
case class FailTask(id: Long, log: String, delay: Option[Long]) extends TaskCtrl

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

case class RecoverTask(id: Long) extends TaskCtrl

object RecoverTask {
  implicit val fmt = Json.format[RecoverTask]
}

case class UnblockTask(id: Long) extends TaskCtrl

object UnblockTask {
  implicit val fmt = Json.format[UnblockTask]
}

/**
  *
  * @param pool
  */
case class RecoverPool(pool: String) extends TaskCtrl

object RecoverPool {
  implicit val fmt = Json.format[RecoverPool]
}

