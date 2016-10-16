package controllers

import javax.inject._

import akka.actor.{ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import net.earthson.task._
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

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
class TaskController @Inject()(actorSystem: ActorSystem)(implicit exec: ExecutionContext, databaseConfigProvider: DatabaseConfigProvider)
  extends Controller
    with LazyLogging
{

  implicit val askTimeout: Timeout = 10 seconds

  val taskManager = actorSystem.actorOf(Props(new Manager()), "manager")

  def try2future[T](t: Try[T]): Future[T] = {
    t match {
      case Success(res) => Future.successful(res)
      case Failure(err) => Future.failed(err)
    }
  }

  def fetch = Action.async{ request =>
    val pool = request.getQueryString("pool").get
    val limit = request.getQueryString("limit").map(_.toInt).getOrElse(1)
    (taskManager ? FetchTask(pool, limit)).mapTo[Try[Seq[Task]]].flatMap(try2future).map(res => Ok(Json.toJson(res)))
  }

  def create = Action.async(parse.json.map(_.as[AddTask])) { request =>
    logger.debug("request create")
    (taskManager ? request.body).mapTo[Try[Boolean]].flatMap(try2future).map(res => Ok(Json.toJson(res)))
  }

  def success = Action.async { request =>
    val id = request.getQueryString("id").map(_.toLong).get
    (taskManager ? SucceedTask(id)).mapTo[Try[Boolean]].flatMap(try2future).map(res => Ok(Json.toJson(res)))
  }

  def fail = Action.async { request =>
      val id = request.getQueryString("id").map(_.toLong).get
    (taskManager ? FailTask(id)).mapTo[Try[Boolean]].flatMap(try2future).map(res => Ok(Json.toJson(res)))
  }

  def delete = Action.async { request =>
    val id = request.getQueryString("id").map(_.toLong).get
    (taskManager ? DeleteTask(id)).mapTo[Try[Boolean]].flatMap(try2future).map(res => Ok(Json.toJson(res)))
  }

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
