package repositories

import models.OrderEvent
import play.api.libs.json.Json
import play.api.{Configuration, Logging}

import java.io.IOException
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.concurrent.Executors
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Simple Single-Threaded File Event Store Repository.
 *
 * Concurrency Model:
 * - Uses a dedicated single-threaded ExecutorService instead of `synchronized` blocks.
 * - All append operations are queued and executed strictly sequentially one-by-one on this single thread.
 * - Zero lock contention and zero risk of thread pool starvation.
 */
@Singleton
class FileEventStoreRepository @Inject()(
  config: Configuration
) extends EventStoreRepository with Logging {

  // Let's create a single thread execution context
  // -> Why? - appending to a file is single threaded and synchronous anyways...
  // if we did this async, and used a threadpool, we could easily starve the Multiple-Event-Loops and new requests would be blocked
  private val executor = Executors.newSingleThreadExecutor()
  private implicit val singleThreadEc: ExecutionContext = ExecutionContext.fromExecutor(executor)

  private val logFile: Path = Paths.get(
    config.get[String]("app.event-store.file-path")
  )

  // Note: If the parent directory or file already exists, it's a no-op

  // if config's file location has a parent directory, we should create it
  if (logFile.getParent != null) {
    Files.createDirectories(logFile.getParent)
  }

  // if the file doesn't exist at the given path, let's create it
  if (!Files.exists(logFile)) {
    Files.createFile(logFile)
  }

  logger.info(s"[FileEventStore] Initialized single-threaded file event store at: ${logFile.toAbsolutePath}")

  // simple append implementation, every write is pushed onto a single thread
  override def append(event: OrderEvent): Future[Unit] = Future {
    logger.info(s"[FileEventStore] Writing event '${event.eventType}' for order ${event.order.id} to ${logFile.getFileName}")
    Using(Files.newBufferedWriter(logFile, StandardOpenOption.APPEND)) { w =>
      w.write(Json.toJson(event).toString)
      w.newLine()
      w.flush()
    }.recover {
      case e: IOException =>
        logger.error(s"[FileEventStore] Failed to append event '${event.eventType}' for order ${event.order.id}", e)
        throw new RuntimeException("Failed to append event to event store", e)
    }.get
  }(singleThreadEc)

  override def readAll(): Future[List[OrderEvent]] = Future {
    if (!Files.exists(logFile)) {
      logger.warn(s"[FileEventStore] Event store file does not exist: ${logFile.toAbsolutePath}")
      List.empty
    } else {
      val lines = Files.readAllLines(logFile).asScala.toList.filter(_.nonEmpty)
      logger.info(s"[FileEventStore] Reading ${lines.size} event(s) from ${logFile.getFileName}")
      lines.map { line =>
        Json.parse(line).as[OrderEvent]
      }
    }
  }(singleThreadEc)

  def close(): Unit = {
    executor.shutdown()
  }
}
