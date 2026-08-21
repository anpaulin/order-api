package repositories

import models.OrderEvent
import play.api.libs.json.Json

import java.io.IOException
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Using

@Singleton
class FileAuditLogRepository @Inject()(
  config: play.api.Configuration
)(implicit ec: ExecutionContext) extends AuditLogRepository {

  private val logFile: Path = Paths.get(
    config.get[String]("app.audit-log.file-path")
  )

  // Ensure parent directories and file exist
  if (logFile.getParent != null) {
    Files.createDirectories(logFile.getParent)
  }
  if (!Files.exists(logFile)) Files.createFile(logFile)

  override def append(event: OrderEvent): Future[Unit] = Future {
    synchronized {
      Using(Files.newBufferedWriter(logFile, StandardOpenOption.APPEND)) { w =>
        w.write(Json.toJson(event).toString)
        w.newLine()
        w.flush()
      }.recover {
        case e: IOException => throw new RuntimeException("Failed to append event to audit log", e)
      }.get
    }
  }

  override def readAll(): Future[List[OrderEvent]] = Future {
    if (!Files.exists(logFile)) List.empty
    else
      Files.readAllLines(logFile).asScala.toList
        .filter(_.nonEmpty)
        .map { line =>
          Json.parse(line).as[OrderEvent]
        }
  }
}


