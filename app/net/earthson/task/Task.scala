package net.earthson.task

import play.api.libs.json.Json

/**
  *
  * @param pool task pool use, you can put spec task type to a spec pool. pool is mirror to worker type
  * @param `type` task type for worker to process
  * @param key task key to find this task
  * @param createTime timestamp for creation
  * @param startTime timestamp start. will be rewrite for each process
  * @param endTime timestamp end
  * @param options task parameters and so on
  * @param status task status
  * @param tryCount try count, task will retry when tryCount < tryLimit when failed
  * @param tryLimit limit to try
  * @param timeout timeout in second
  * @param log last fail log for task
  */
case class Task(
                 id: Long,
                 pool: String,
                 `type`: String,
                 key: String,
                 createTime: Long,
                 options: String,
                 status: String,
                 pendingTime: Long,
                 startTime: Option[Long] = None,
                 endTime: Option[Long] = None,
                 tryCount: Int = 0,
                 tryLimit: Int = 5,
                 timeout: Long = 600,
                 log: String = ""
               ) {

}

object Task {

  object Status {
    val Pending = "pending"
    val Active = "active"
    val Success = "success"
    val Failed = "fail"
    val block = "block"
  }


  implicit val fmt = Json.format[Task]
}

/**
  *
  * @param id
  * @param time time that task enqueue
  */
case class TaskIn(id: Long, time: Long)
