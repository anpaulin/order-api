package repositories

import models.OrderEvent
import play.api.{Configuration, Logging}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json

import java.io.{BufferedWriter, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using

@Singleton
class FileAuditLogRepository @Inject()(
  config: Configuration,
  lifecycle: ApplicationLifecycle,
  ec: BlockingIoExecutionContext
) extends AuditLogRepository with Logging {

  // Test-friendly auxiliary constructor
  def this(config: Configuration)(implicit ec: ExecutionContext) = {
    this(config, new play.api.inject.DefaultApplicationLifecycle(), null)
  }

  private implicit val executionContext: ExecutionContext =
    if (ec != null) ec else scala.concurrent.ExecutionContext.global

  private val logFile: Path = Paths.get(
    config.get[String]("app.audit-log.file-path")
  )

  // Ensure parent directories and file exist
  if (logFile.getParent != null) {
    Files.createDirectories(logFile.getParent)
  }
  if (!Files.exists(logFile)) Files.createFile(logFile)

  logger.info(s"[FileAuditLog] Initialized file audit log at: ${logFile.toAbsolutePath}")

  // Persistent open BufferedWriter (eliminates OS open/close descriptor churn per request)
  private val writer: BufferedWriter = Files.newBufferedWriter(
    logFile,
    StandardCharsets.UTF_8,
    StandardOpenOption.CREATE,
    StandardOpenOption.APPEND
  )

  // Clean shutdown hook to flush and close file descriptor
  lifecycle.addStopHook { () =>
    Future {
      close()
    }(executionContext)
  }

  override def append(event: OrderEvent): Future[Unit] = Future {
    val line = Json.stringify(Json.toJson(event))
    synchronized {
      try {
        logger.info(s"[FileAuditLog] Writing event '${event.eventType}' for order ${event.order.id} to ${logFile.getFileName}")
        writer.write(line)
        writer.newLine()
        writer.flush()
      } catch {
        case e: IOException =>
          logger.error(s"[FileAuditLog] Failed to append event '${event.eventType}' for order ${event.order.id}", e)
          throw new RuntimeException("Failed to append event to audit log", e)
      }
    }
  }(executionContext)

  override def readAll(): Future[List[OrderEvent]] = Future {
    if (!Files.exists(logFile)) {
      logger.warn(s"[FileAuditLog] Log file does not exist: ${logFile.toAbsolutePath}")
      List.empty
    } else {
      // Stream lines incrementally without buffering entire raw file into memory
      Using(Files.lines(logFile, StandardCharsets.UTF_8)) { stream =>
        val builder = List.newBuilder[OrderEvent]
        val it = stream.iterator()
        while (it.hasNext) {
          val line = it.next().trim
          if (line.nonEmpty) {
            builder += Json.parse(line).as[OrderEvent]
          }
        }
        val result = builder.result()
        logger.info(s"[FileAuditLog] Streamed and parsed ${result.size} audit event(s) from ${logFile.getFileName}")
        result
      }.recover {
        case e: Exception =>
          logger.error(s"[FileAuditLog] Error streaming audit log from: ${logFile.toAbsolutePath}", e)
          throw new RuntimeException("Failed to read audit log", e)
      }.get
    }
  }(executionContext)

  /** Closes open file descriptor. */
  def close(): Unit = synchronized {
    try {
      writer.flush()
      writer.close()
      logger.info("[FileAuditLog] Flushed and closed audit log writer")
    } catch {
      case _: Exception => ()
    }
  }
}




