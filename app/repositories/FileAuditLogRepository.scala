package repositories

import models.OrderEvent
import play.api.libs.json.Json
import play.api.{Configuration, Logging}

import java.io.IOException
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Simple, naive append-only File Audit Log Repository.
 * Opens and flushes the file on every individual append operation.
 */
@Singleton
class FileAuditLogRepository @Inject()(
  config: Configuration
)(implicit ec: ExecutionContext) extends AuditLogRepository with Logging {

  private val logFile: Path = Paths.get(
    config.get[String]("app.audit-log.file-path")
  )

  // Ensure parent directories and file exist
  if (logFile.getParent != null) {
    Files.createDirectories(logFile.getParent)
  }
  if (!Files.exists(logFile)) Files.createFile(logFile)

  logger.info(s"[FileAuditLog] Initialized file audit log at: ${logFile.toAbsolutePath}")

  override def append(event: OrderEvent): Future[Unit] = Future {
    synchronized {
      logger.info(s"[FileAuditLog] Writing event '${event.eventType}' for order ${event.order.id} to ${logFile.getFileName}")
      Using(Files.newBufferedWriter(logFile, StandardOpenOption.APPEND)) { w =>
        w.write(Json.toJson(event).toString)
        w.newLine()
        w.flush()
      }.recover {
        case e: IOException =>
          logger.error(s"[FileAuditLog] Failed to append event '${event.eventType}' for order ${event.order.id}", e)
          throw new RuntimeException("Failed to append event to audit log", e)
      }.get
    }
  }

  override def readAll(): Future[List[OrderEvent]] = Future {
    if (!Files.exists(logFile)) {
      logger.warn(s"[FileAuditLog] Log file does not exist: ${logFile.toAbsolutePath}")
      List.empty
    } else {
      val lines = Files.readAllLines(logFile).asScala.toList.filter(_.nonEmpty)
      logger.info(s"[FileAuditLog] Reading ${lines.size} audit event(s) from ${logFile.getFileName}")
      lines.map { line =>
        Json.parse(line).as[OrderEvent]
      }
    }
  }

  def close(): Unit = ()
}
