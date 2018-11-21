package com.prisma.api.server

import akka.http.scaladsl.model.StatusCodes
import com.prisma.api.ApiSpecBase
import com.prisma.api.project.ProjectFetcher
import com.prisma.api.schema.APIErrors.AuthFailure
import com.prisma.api.schema.{ApiUserContext, SchemaBuilder}
import com.prisma.shared.models.Project
import com.prisma.shared.schema_dsl.TestProject
import com.prisma.utils.await.AwaitUtils
import org.scalatest.{FlatSpec, Matchers}
import pdi.jwt.{Jwt, JwtAlgorithm}
import play.api.libs.json._
import sangria.schema.{ObjectType, Schema, SchemaValidationRule}

import scala.concurrent.Future

class RequestHandlerSpec extends FlatSpec with Matchers with ApiSpecBase with AwaitUtils {
  import system.dispatcher
  import testDependencies.reporter

  "a request without token" should "result in an InvalidToken error" in {
    val error = handler(projectWithSecret).handleRawRequestForPublicApi(projectWithSecret.id, request("header")).failed.await
    error shouldBe an[AuthFailure]
  }

  "request with a proper token" should "result in a successful query" in {
    val properHeader = Jwt.encode("{}", projectWithSecret.secrets.head, JwtAlgorithm.HS256)
    val (_, result)  = handler(projectWithSecret).handleRawRequestForPublicApi(projectWithSecret.id, request(properHeader)).await
    result.assertSuccessfulResponse("")
  }

  val projectWithSecret = TestProject().copy(secrets = Vector("secret"))

  def request(authHeader: String) =
    RawRequest(id = "req-id", json = Json.obj("query" -> "{users}"), ip = "0.0.0.0", authorizationHeader = Some(authHeader), testDependencies.graphQlQueryCache)

  def handler(project: Project) = {
    RequestHandler(
      projectFetcher = ProjectFetcherStub(project),
      schemaBuilder = EmptySchemaBuilder,
      graphQlRequestHandler = SucceedingGraphQlRequestHandler,
      auth = testDependencies.auth,
      log = println
    )
  }
}

object SucceedingGraphQlRequestHandler extends GraphQlRequestHandler {
  override def handle(graphQlRequest: GraphQlRequest) = Future.successful {
    StatusCodes.ImATeapot -> JsObject.empty
  }

  override def healthCheck = Future.unit
}

object EmptySchemaBuilder extends SchemaBuilder {
  override def apply(project: Project): Schema[ApiUserContext, Unit] = {
    Schema(
      query = ObjectType("Query", List.empty),
      validationRules = SchemaValidationRule.empty
    )
  }
}

case class ProjectFetcherStub(project: Project) extends ProjectFetcher {
  override def fetch(projectIdOrAlias: String) = Future.successful(Some(project))
}
