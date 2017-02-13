package app

import io.swagger.parser.SwaggerParser
import io.swagger.models.Swagger
import utils.Implicits._

import scala.collection.JavaConversions._
import better.files._
import java.io.{File => JFile}
import java.time.OffsetDateTime

import utils.{CaseClassMetaHelper, ScalaFmtHelper}

import scala.meta._
import scala.util.Try

object SwaggerCodegen extends App {
  val swaggerDir = file"server/conf/swagger"
  val swaggers   = swaggerDir.listRecursively.filter(_.extension.contains(".yaml")).map(e => (e, new SwaggerParser().read(e.pathAsString)))

  implicit class ExtParamater(e: io.swagger.models.parameters.Parameter) {
    def getType: Option[String] = Try(e.asInstanceOf[io.swagger.models.parameters.PathParameter].getType).toOption
  }

  swaggers.foreach(e => createForConfig(e._1, e._2))

  def createForConfig(f: File, swagger: Swagger): Unit = {
    val apiVersion = "v" + swagger.getInfo.getVersion.replace(".", "_")
    println(s"# Codegeneration for Swaggerdoc at [${f.pathAsString}] $apiVersion")

    /*
    Create Models
     */
    val modelsFolder = file"shared/src/main/scala/shared/models/swagger/${f.nameWithoutExtension}/$apiVersion"

    swagger.getDefinitions.toVector.foreach {
      case (name, model) =>
        val modelName = name.toUpperCamelCase

        val propertiesAsScala: Vector[String] = model.getProperties.toVector.map { e =>
          val scalaType = (e._2.getType, Option(e._2.getFormat)) match {
            case ("integer", Some("int64"))    => "Long"
            case ("integer", _)                => "Int"
            case ("number", _)                 => "Double"
            case ("string", None)              => "String"
            case ("string", Some("byte"))      => "String"
            case ("string", Some("binary"))    => "String"
            case ("boolean", _)                => "Boolean"
            case ("string", Some("date"))      => "LocalDate"
            case ("string", Some("date-time")) => "OffsetDateTime"
            case _                             => "String"
          }

          if (e._2.getRequired) {
            s"${e._1.toCamelCase}: $scalaType"
          } else {
            s"${e._1.toCamelCase}: Option[$scalaType]"
          }
        }

        val modelAsCaseClass = s"case class $modelName(${propertiesAsScala.mkString(", ")})"

        val targetFile = modelsFolder./(s"$modelName.scala")
        if (targetFile.notExists) {
          // Create Template
          val template =
            s"""
            |package shared.models.swagger.${f.nameWithoutExtension}.$apiVersion
            |
            |import java.time._
            |
            |$modelAsCaseClass
          """.trim.stripMargin

          targetFile.createIfNotExists(createParents = true).overwrite(template)
        } else {
          // Update existing Source
          val source = targetFile.toJava.parse[Source].get
          val caseClassStat =
            modelAsCaseClass.parse[Stat].get
          val tree = CaseClassMetaHelper.updateOrInsert(source, caseClassStat)
          targetFile.write(ScalaFmtHelper.formatCode(tree.syntax))
        }
    }

    /*
      Create Api
     */
    val basePath = swagger.getBasePath
    val target   = file"server/app/controllers/swagger/$apiVersion/${f.nameWithoutExtension.toUpperCamelCase.takeWhile(_ != '_')}.scala"

    case class RouterCase(routerCase: String, abstractfunc: String)

    val routerCases: Vector[RouterCase] = swagger.getPaths.toVector.flatMap {
      case (strPath, path) =>
        println(s"- Creating Router for $strPath")

        val playPath = strPath
          .split('/')
          .map { e =>
            if (e.startsWith("{")) {
              val name = e.drop(1).dropRight(1)
              if (path.getOperations
                    .flatMap(e => Option(e.getParameters))
                    .flatten
                    .filter(_.getName == name)
                    .find(e => {
                      Option(e.getIn).map(_.toLowerCase).contains("path")
                    })
                    .flatMap(_.getType)
                    .contains("integer")) {
                s"$${int($name)}"
              } else {
                "$" + e
              }
            } else e
          }
          .mkString("", "/", "")

        path.getOperationMap.toVector.map {
          case (method, op) =>
            val methodName = Option(op.getOperationId)
              .getOrElse(method.toString.toLowerCase + strPath.split('/').filterNot(_.startsWith("{")).map(_.toUpperCamelCase).mkString)

            val queryParameter = op.getParameters.toVector
              .filter(_.getIn.toLowerCase == "query")
              .map { e =>
                if (e.getRequired) {
                  s"""q"${e.getName}=$$${e.getName}""""
                } else {
                  s"""q_o"${e.getName}=$$${e.getName}""""
                }
              }

            val queryParameterStr =
              if (queryParameter.isEmpty) ""
              else {
                s" ? ${queryParameter.mkString(" ? ")}"
              }

            sealed trait RequestBodyType
            object NoBody extends RequestBodyType
            object JsonBody extends RequestBodyType
            object MultipartBody extends RequestBodyType
            object FileBody extends RequestBodyType

            val body2parser = Map[RequestBodyType, String](
              NoBody        -> "",
              JsonBody      -> "(circe.json)",
              MultipartBody -> "(parse.multipartFormData)",
              FileBody      -> "(parse.temporaryFile)"
            ).withDefault(_ => "")

            val body2content = Map[RequestBodyType, String](
              NoBody        -> "AnyContent",
              JsonBody      -> "Json",
              MultipartBody -> "MultipartFormData[Files.TemporaryFile]",
              FileBody      -> "Files.TemporaryFile"
            ).withDefault(_ => "AnyContent")

            // atm only support either json or multipart/form-data
            val bodyType: RequestBodyType = {
              if (Seq("POST", "PUT").contains(method.toString)) {
                Option(op.getConsumes).flatMap(_.headOption).map(_.toLowerCase) match {
                  case Some("multipart/form-data") =>
                    MultipartBody
                  case Some("application/json") =>
                    JsonBody
                  case _ =>
                    FileBody
                }
              } else {
                NoBody
              }
            }

            val routerCase = s"""
               |case ${method.toString}(p"$playPath"$queryParameterStr) => AsyncStack${body2parser(bodyType)} { implicit request =>
               |  constructResult($methodName(${op.getParameters.toVector
                                  .filter(e => Seq("query", "path").contains(e.getIn.toLowerCase))
                                  .map(e => s"${e.getName}")
                                  .mkString(", ")}))
               |}
             """.stripMargin

            val params = op.getParameters.toVector
              .filter(e => Seq("query", "path").contains(e.getIn.toLowerCase))
              .map { e =>
                val tpe = if (e.getType.contains("integer")) "Int" else "String"

                if (e.getRequired) {
                  s"${e.getName}: $tpe"
                } else {
                  s"${e.getName}: Option[$tpe]"
                }
              }

            val abstractFunc =
              s"""def $methodName(${params.mkString(", ")})(implicit request: RequestWithAttributes[${body2content(bodyType)}]): HttpResult[Result] """
            RouterCase(routerCase.mkString, abstractFunc)
        }
    }

    val template =
      s"""
         |package controllers.swagger.$apiVersion
         |
         |import play.api.mvc._
         |import com.google.inject.Inject
         |import play.api.routing._
         |import play.api.routing.sird._
         |import play.api.libs.circe._
         |import scala.concurrent.ExecutionContext
         |import controllers.{AuthConfigImpl, ExtendedController}
         |import jp.t2v.lab.play2.stackc.RequestWithAttributes
         |import jp.t2v.lab.play2.auth.OptionalAuthElement
         |import services.dao.UserDao
         |import io.circe.Json
         |import play.api.libs.Files
         |import io.circe.generic.auto._
         |import io.circe.syntax._
         |import shared.models.swagger.${f.nameWithoutExtension}.$apiVersion._
         |
         |class ${f.nameWithoutExtension.toUpperCamelCase} @Inject()(val userDao: UserDao)(implicit val ec: ExecutionContext) extends ${f.nameWithoutExtension.toUpperCamelCase}Trait {
         |
         |  ${routerCases.map(_.abstractfunc + " = NotImplemented.pureResult").mkString("\n")}
         |
         |}
         |
         |trait ${f.nameWithoutExtension.toUpperCamelCase}Trait extends ExtendedController with SimpleRouter with OptionalAuthElement with AuthConfigImpl with Circe {
         |  def routes: Router.Routes = {
         |   ${routerCases.map(_.routerCase).mkString}
         |  }
         |
         |  ${routerCases.map(_.abstractfunc).mkString("\n")}
         |}
         |
         """.trim.stripMargin

    if (target.notExists) {
      target.createIfNotExists(createParents = true).overwrite(ScalaFmtHelper.formatCode(template))
    } else {
      val templTrait = template
        .parse[Source]
        .get
        .collect {
          case c @ q"..$mods trait $tname[..$tparams] extends $template" =>
            c
        }
        .head

      val source = target.toJava.parse[Source].get.transform {
        case q"trait $tname extends $template { ..$body }" if tname.value == f.nameWithoutExtension.toUpperCamelCase + "Trait" =>
          templTrait
      }

      target.overwrite(ScalaFmtHelper.formatCode(source.syntax))
    }
  }
}