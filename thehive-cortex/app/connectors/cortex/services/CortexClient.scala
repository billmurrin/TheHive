package connectors.cortex.services

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

import play.api.{ Configuration, Logger }
import play.api.http.HeaderNames
import play.api.libs.json.{ JsObject, JsValue, Json }
import play.api.libs.ws.{ WSAuthScheme, WSRequest, WSResponse }
import play.api.mvc.MultipartFormData.{ DataPart, FilePart }

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import connectors.cortex.models.JsonFormat._
import connectors.cortex.models._
import javax.inject.{ Inject, Singleton }
import models.HealthStatus
import services.CustomWSAPI

import org.elastic4play.NotFoundError
import org.elastic4play.utils.RichFuture

object CortexConfig {
  def getCortexClient(name: String, configuration: Configuration, ws: CustomWSAPI): Option[CortexClient] = {
    val url = configuration.getOptional[String]("url").getOrElse(sys.error("url is missing")).replaceFirst("/*$", "")
    val authentication =
      configuration.getOptional[String]("key").map(CortexAuthentication.Key)
        .orElse {
          for {
            basicEnabled ← configuration.getOptional[Boolean]("basicAuth")
            if basicEnabled
            username ← configuration.getOptional[String]("username")
            password ← configuration.getOptional[String]("password")
          } yield CortexAuthentication.Basic(username, password)
        }
    Some(new CortexClient(name, url, authentication, ws))
  }

  def getInstances(configuration: Configuration, globalWS: CustomWSAPI): Seq[CortexClient] = {
    for {
      cfg ← configuration.getOptional[Configuration]("cortex").toSeq
      cortexWS = globalWS.withConfig(cfg)
      key ← cfg.subKeys
      if key != "ws"
      c ← cfg.getOptional[Configuration](key)
      instanceWS = cortexWS.withConfig(c)
      cic ← getCortexClient(key, c, instanceWS)
    } yield cic
  }
}

@Singleton
case class CortexConfig(instances: Seq[CortexClient]) {

  @Inject
  def this(configuration: Configuration, globalWS: CustomWSAPI) = this(
    CortexConfig.getInstances(configuration, globalWS))
}

object CortexAuthentication {

  abstract class Type {
    val name: String
    def apply(request: WSRequest): WSRequest
  }

  case class Basic(username: String, password: String) extends Type {
    val name = "basic"
    def apply(request: WSRequest): WSRequest = {
      request.withAuth(username, password, WSAuthScheme.BASIC)
    }
  }

  case class Key(key: String) extends Type {
    val name = "key"
    def apply(request: WSRequest): WSRequest = {
      request.withHttpHeaders(HeaderNames.AUTHORIZATION → s"Bearer $key")
    }
  }
}

case class CortexError(status: Int, requestUrl: String, message: String) extends Exception(s"Cortex error on $requestUrl ($status) \n$message")

class CortexClient(val name: String, baseUrl: String, authentication: Option[CortexAuthentication.Type], ws: CustomWSAPI) {

  private[CortexClient] lazy val logger = Logger(getClass)

  logger.info(s"new Cortex($name, $baseUrl) authentication: ${authentication.fold("no")(_.getClass.getName)}")
  private def request[A](uri: String, f: WSRequest ⇒ Future[WSResponse], t: WSResponse ⇒ A)(implicit ec: ExecutionContext): Future[A] = {
    val request = ws.url(s"$baseUrl/$uri")
    val authenticatedRequest = authentication.fold(request)(_.apply(request))
    f(authenticatedRequest).map {
      case response if response.status / 100 == 2 ⇒ t(response)
      case error                                  ⇒ throw CortexError(error.status, s"$baseUrl/$uri", error.body)
    }
  }

  def getAnalyzer(analyzerId: String)(implicit ec: ExecutionContext): Future[Analyzer] = {
    request(s"api/analyzer/$analyzerId", _.get, _.json.as[Analyzer]).map(_.copy(cortexIds = List(name)))
      .recoverWith { case _ ⇒ getAnalyzerByName(analyzerId) } // if get analyzer using cortex2 API fails, try using legacy API
  }

  def getWorkerById(workerId: String)(implicit ec: ExecutionContext): Future[Worker] = {
    request(s"api/analyzer/$workerId", _.get, _.json.as[Worker]).map(_.addCortexId(name))
  }

  def getWorkerByName(workerName: String)(implicit ec: ExecutionContext): Future[Worker] = {
    val searchRequest = Json.obj(
      "query" -> Json.obj(
        "_field" -> "name",
        "_value" -> workerName),
      "range" -> "0-1")
    request(s"api/analyzer/_search", _.post(searchRequest),
      _.json.as[Seq[Worker]])
      .flatMap { analyzers ⇒
        analyzers.headOption
          .fold[Future[Worker]](Future.failed(NotFoundError(s"worker $workerName not found"))) { worker ⇒
            Future.successful(worker.addCortexId(name))
          }
      }
  }

  def getAnalyzerByName(analyzerName: String)(implicit ec: ExecutionContext): Future[Analyzer] = {
    val searchRequest = Json.obj(
      "query" -> Json.obj(
        "_field" -> "name",
        "_value" -> analyzerName),
      "range" -> "0-1")
    request(s"api/analyzer/_search", _.post(searchRequest),
      _.json.as[Seq[Analyzer]])
      .flatMap { analyzers ⇒
        analyzers.headOption
          .fold[Future[Analyzer]](Future.failed(NotFoundError(s"analyzer $analyzerName not found"))) { analyzer ⇒
            Future.successful(analyzer.copy(cortexIds = List(name)))
          }
      }
  }

  def listAnalyzer(implicit ec: ExecutionContext): Future[Seq[Analyzer]] = {
    request(s"api/analyzer?range=all", _.get, _.json.as[Seq[Analyzer]]).map(_.map(_.copy(cortexIds = List(name))))
  }

  def findWorkers(query: JsObject)(implicit ec: ExecutionContext): Future[Seq[Worker]] = {
    request(s"api/analyzer/_search?range=all", _.post(Json.obj("query" -> query)), _.json.as[Seq[Worker]]).map(_.map(_.addCortexId(name)))
  }

  def analyze(analyzerId: String, artifact: CortexArtifact)(implicit ec: ExecutionContext): Future[JsValue] = {
    artifact match {
      case FileArtifact(data, attributes) ⇒
        val body = Source(List(
          FilePart("data", (attributes \ "attachment" \ "name").asOpt[String].getOrElse("noname"), None, data),
          DataPart("_json", attributes.toString)))
        request(s"api/analyzer/$analyzerId/run", _.post(body), _.json)
      case a: DataArtifact ⇒
        request(s"api/analyzer/$analyzerId/run", _.post(Json.toJson(a)), _.json.as[JsObject])
    }
  }

  def execute(
      workerId: String,
      dataType: String,
      data: JsValue,
      tlp: Long,
      message: String,
      parameters: JsObject)(implicit ec: ExecutionContext): Future[JsValue] = {
    val body = Json.obj(
      "data" -> data.toString,
      "dataType" -> dataType,
      "tlp" -> tlp,
      "message" -> message,
      "parameters" -> parameters)
    request(s"api/analyzer/$workerId/run", _.post(body), _.json.as[JsObject])
  }

  def listAnalyzerForType(dataType: String)(implicit ec: ExecutionContext): Future[Seq[Analyzer]] = {
    request(s"api/analyzer/type/$dataType", _.get, _.json.as[Seq[Analyzer]]).map(_.map(_.copy(cortexIds = List(name))))
  }

  //  def listJob(implicit ec: ExecutionContext): Future[Seq[JsObject]] = {
  //    request(s"api/job", _.get, _.json.as[Seq[JsObject]])
  //  }

  //  def getJob(jobId: String)(implicit ec: ExecutionContext): Future[JsObject] = {
  //    request(s"api/job/$jobId", _.get, _.json.as[JsObject])
  //  }

  //  def removeJob(jobId: String)(implicit ec: ExecutionContext): Future[Unit] = {
  //    request(s"api/job/$jobId", _.delete, _ ⇒ ())
  //  }

  //  def report(jobId: String)(implicit ec: ExecutionContext): Future[JsObject] = {
  //    request(s"api/job/$jobId/report", _.get, _.json.as[JsObject])
  //  }

  def waitReport(jobId: String, atMost: Duration)(implicit ec: ExecutionContext): Future[JsObject] = {
    request(s"api/job/$jobId/waitreport", _.withQueryStringParameters("atMost" → atMost.toString).get, _.json.as[JsObject])
  }

  def getVersion()(implicit system: ActorSystem, ec: ExecutionContext): Future[Option[String]] = {
    request("api/status", _.get, identity)
      .map {
        case resp if resp.status / 100 == 2 ⇒ (resp.json \ "versions" \ "Cortex").asOpt[String]
        case _                              ⇒ None
      }
      .recover { case _ ⇒ None }
      .withTimeout(1.seconds, None)
  }

  def status()(implicit system: ActorSystem, ec: ExecutionContext): Future[JsObject] =
    getVersion()
      .map {
        case Some(version) ⇒ Json.obj(
          "name" → name,
          "version" → version,
          "status" → "OK")
        case None ⇒ Json.obj(
          "name" → name,
          "version" → "",
          "status" → "ERROR")
      }

  def health()(implicit system: ActorSystem, ec: ExecutionContext): Future[HealthStatus.Type] = {
    getVersion()
      .map {
        case None ⇒ HealthStatus.Error
        case _    ⇒ HealthStatus.Ok
      }
  }
}