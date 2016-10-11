package net.earthson.task

import play.api.libs.json.Json

/**
  *
  * @param pool task pool use, you can put spec task type to a spec pool. pool is mirror to worker type
  * @param `type` task type for worker to process
  * @param key task key to find this task
  * @param rangeL task range left border. task range could be a time range or just raw Long for sequence index. task has range overlap could not process at the same time
  * @param rangeR task range right border
  * @param createTime timestamp for creation
  * @param startTime timestamp start. will be rewrite for each process
  * @param endTime timestamp end
  * @param options task parameters and so on
  * @param status task status
  * @param tryCount try count, task will retry when tryCount < tryLimit when failed
  * @param tryLimit limit to try
  * @param log last fail log for task
  */
case class Task(
                 pool: String,
                 `type`: String,
                 key: String,
                 rangeL: Long,
                 rangeR: Long,
                 createTime: Long,
                 options: String,
                 status: String,
                 startTime: Option[Long] = None,
                 endTime: Option[Long] = None,
                 tryCount: Int = 0,
                 tryLimit: Int = 5,
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
