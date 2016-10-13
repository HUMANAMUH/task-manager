package controllers

import javax.inject._

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import net.earthson.task.db.TaskDB
import net.earthson.task.{FetchTask, TaskCtrl}
import play.api.db.slick.DatabaseConfigProvider
import play.api.mvc._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util._

/**
  * This controller creates an `Action` that demonstrates how to write
  * simple asynchronous code in a controller. It uses a timer to
  * asynchronously delay sending a response for 1 second.
  *
  * @param actorSystem We need the `ActorSystem`'s `Scheduler` to
  *                    run code after a delay.
  * @param exec        We need an `ExecutionContext` to execute our
  *                    asynchronous code.
  */
@Singleton
class TaskController @Inject()(actorSystem: ActorSystem)(implicit exec: ExecutionContext)
  extends Controller
    with LazyLogging
{

  /**
    * Create an Action that returns a plain text message after a delay
    * of 1 second.
    *
    * The configuration in the `routes` file means that this method
    * will be called when the application receives a `GET` request with
    * a path of `/message`.
    */
  def message = Action.async {
    getFutureMessage(1.second).map { msg => Ok(msg) }
  }

  private def getFutureMessage(delayTime: FiniteDuration): Future[String] = {
    val promise: Promise[String] = Promise[String]()
    actorSystem.scheduler.scheduleOnce(delayTime) {
      promise.success("Hi!")
    }
    promise.future
  }

}
