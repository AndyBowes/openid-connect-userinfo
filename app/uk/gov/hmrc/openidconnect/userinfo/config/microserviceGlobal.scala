/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.openidconnect.userinfo.config

import com.typesafe.config.Config
import uk.gov.hmrc.api.connector.ServiceLocatorConnector
import uk.gov.hmrc.openidconnect.userinfo.controllers._
import net.ceedubs.ficus.Ficus._
import play.api._
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc.{RequestHeader, Result}
import uk.gov.hmrc.openidconnect.userinfo.filters.MicroserviceAuthFilter
import uk.gov.hmrc.play.audit.filters.AuditFilter
import uk.gov.hmrc.play.auth.controllers.AuthParamsControllerConfig
import uk.gov.hmrc.play.config.{AppName, ControllerConfig, RunMode}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.logging.filters.LoggingFilter
import uk.gov.hmrc.play.microservice.bootstrap.DefaultMicroserviceGlobal

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait ServiceLocatorRegistration extends GlobalSettings with RunMode {

  val registrationEnabled: Boolean
  val slConnector: ServiceLocatorConnector
  implicit val hc: HeaderCarrier

  override def onStart(app: Application): Unit = {
    super.onStart(app)
    registrationEnabled match {
      case true => slConnector.register
      case false => Logger.warn("Registration in Service Locator is disabled")
    }
  }
}

object ControllerConfiguration extends ControllerConfig {
  lazy val controllerConfigs = Play.current.configuration.underlying.as[Config]("controllers")
}

object AuthParamsControllerConfiguration extends AuthParamsControllerConfig {
  lazy val controllerConfigs = ControllerConfiguration.controllerConfigs
}

object MicroserviceAuditFilter extends AuditFilter with AppName {
  override val auditConnector = MicroserviceAuditConnector
  override def controllerNeedsAuditing(controllerName: String) = ControllerConfiguration.paramsForController(controllerName).needsAuditing
}

object MicroserviceLoggingFilter extends LoggingFilter {
  override def controllerNeedsLogging(controllerName: String) = ControllerConfiguration.paramsForController(controllerName).needsLogging
}

object MicroserviceGlobal extends DefaultMicroserviceGlobal with RunMode with ServiceLocatorRegistration {
  override val auditConnector = MicroserviceAuditConnector

  override def microserviceMetricsConfig(implicit app: Application): Option[Configuration] =
    app.configuration.getConfig(s"$env.microservice.metrics")

  override val loggingFilter = MicroserviceLoggingFilter

  override val microserviceAuditFilter = MicroserviceAuditFilter

  override val authFilter = Some(MicroserviceAuthFilter)

  override implicit val hc: HeaderCarrier = HeaderCarrier()

  override lazy val registrationEnabled = AppContext.registrationEnabled

  override lazy val slConnector = ServiceLocatorConnector(WSHttp)

  override def onError(request: RequestHeader, ex: Throwable): Future[Result] = {
    super.onError(request, ex) map (res => {
      res.header.status
      match {
        case 401 => Status(ErrorUnauthorized.httpStatusCode)(Json.toJson(ErrorUnauthorized))
        case _ => Status(ErrorInternalServerError.httpStatusCode)(Json.toJson(ErrorInternalServerError))
      }
    })
  }

  override def onHandlerNotFound(request: RequestHeader): Future[Result] = Future.successful(NotFound(Json.toJson(ErrorNotFound)))
}

