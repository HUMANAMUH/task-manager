package controllers

import javax.inject._

import akka.actor.{ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import net.earthson.task._
import play.api.{Application, Play}
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
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
class TaskController @Inject()(actorSystem: ActorSystem)(implicit exec: ExecutionContext, appProvider: Provider[Application])
  extends Controller
    with LazyLogging
{

  implicit val askTimeout: Timeout = 10 seconds

  val taskManager = actorSystem.actorOf(Props(new Manager(appProvider.get())), "manager")

  def try2future[T](t: Try[T]): Future[T] = {
    t match {
      case Success(res) => Future.successful(res)
      case Failure(err) => Future.failed(err)
    }
  }

  def commonAction[T : Reads, R : Writes : ClassTag] = {
    Action.async(parse.json) { request =>
      request.body.validate[T] match {
        case JsSuccess(obj, _) => {
          (taskManager ? obj).mapTo[Try[R]].flatMap(try2future)
            .map{
              res =>
                val j = Json.toJson(res)
                logger.debug(Json.prettyPrint(j))
                Ok(j)
            }
            .recover {
              case e: Exception => {
                logger.error("Server Error", e)
                InternalServerError
              }
              case t: Throwable => {
                logger.error("Critical Error, Terminate now!", t)
                sys.exit(1)
              }
            }
        }
        case JsError(_) => {
          Future.successful(BadRequest)
        }
      }
    }
  }

  def start = commonAction[FetchTask, Seq[Task]]

  def create = commonAction[AddTask, Boolean]

  def success = commonAction[SucceedTask, Boolean]

  def fail = commonAction[FailTask, Boolean]

  def delete = commonAction[DeleteTask, Boolean]

  def block = commonAction[BlockTask, Boolean]

  def unblock = commonAction[UnblockTask, Boolean]

  def recoverTask = commonAction[RecoverTask, Boolean]

  def lastTask = commonAction[GetLastTask, Option[Task]]

  def recoverPool = commonAction[RecoverPool, Boolean]

  def lastGroupTask = commonAction[GetLastGroupTask, Option[Task]]
}
