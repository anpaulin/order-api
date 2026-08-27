package repositories

import models.OrderEvent
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Status}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import play.api.{Configuration, Logging}

import java.io.{BufferedWriter, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using

// Actor Protocol Messages
sealed trait FileEventStoreMessage
case class AppendEvent(event: OrderEvent) extends FileEventStoreMessage
case object ReadAllEvents extends FileEventStoreMessage
case object FlushAndClose extends FileEventStoreMessage

/**
 * Dedicated Pekko Actor that owns the file descriptor and writes.
 *
 * Concurrency Model:
 * - Actors are single-threaded by design.
 * - All messages in the mailbox are processed strictly sequentially one-by-one.
 * - Zero locks, zero thread blocking, and clean reactive message passing.
 */
class FileEventStoreActor(logFile: Path) extends Actor with ActorLogging {

  private var writer: BufferedWriter = _

  override def preStart(): Unit = {
    writer = Files.newBufferedWriter(
      logFile,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
    log.info(s"[FileEventStoreActor] Opened event store file: ${logFile.toAbsolutePath}")
  }

  def receive: Receive = {
    case AppendEvent(event) =>
      try {
        val line = Json.stringify(Json.toJson(event))
        writer.write(line)
        writer.newLine()
        writer.flush()
        sender() ! ()
      } catch {
        case e: IOException =>
          log.error(e, s"[FileEventStoreActor] Failed to write event '${event.eventType}' for order ${event.order.id}")
          sender() ! Status.Failure(new RuntimeException("Failed to append event to event store", e))
      }

    case ReadAllEvents =>
      try {
        if (!Files.exists(logFile)) {
          sender() ! List.empty[OrderEvent]
        } else {
          Using(Files.lines(logFile, StandardCharsets.UTF_8)) { stream =>
            val builder = List.newBuilder[OrderEvent]
            val it = stream.iterator()
            while (it.hasNext) {
              val line = it.next().trim
              if (line.nonEmpty) {
                builder += Json.parse(line).as[OrderEvent]
              }
            }
            builder.result()
          } match {
            case scala.util.Success(events) =>
              sender() ! events
            case scala.util.Failure(e) =>
              sender() ! Status.Failure(new RuntimeException("Failed to read event store", e))
          }
        }
      } catch {
        case e: Exception =>
          sender() ! Status.Failure(new RuntimeException("Failed to read event store", e))
      }

    case FlushAndClose =>
      closeWriter()
      sender() ! ()
  }

  override def postStop(): Unit = {
    closeWriter()
  }

  private def closeWriter(): Unit = {
    if (writer != null) {
      try {
        writer.flush()
        writer.close()
        writer = null
        log.info("[FileEventStoreActor] Closed event store writer")
      } catch {
        case _: Exception => ()
      }
    }
  }
}

object FileEventStoreActor {
  def props(logFile: Path): Props = Props(new FileEventStoreActor(logFile))
}

/**
 * Idiomatic Pekko Actor-based File Event Store Repository.
 */
@Singleton
class FileEventStoreRepository @Inject()(
  config: Configuration,
  lifecycle: ApplicationLifecycle,
  actorSystem: ActorSystem
)(implicit ec: ExecutionContext) extends EventStoreRepository with Logging {

  // Test-friendly auxiliary constructor
  def this(config: Configuration)(implicit actorSystem: ActorSystem, ec: ExecutionContext) = {
    this(config, new play.api.inject.DefaultApplicationLifecycle(), actorSystem)
  }

  private implicit val timeout: Timeout = Timeout(10.seconds)

  private val logFile: Path = Paths.get(
    config.get[String]("app.event-store.file-path")
  )

  // Ensure parent directories and file exist synchronously on startup
  if (logFile.getParent != null) {
    Files.createDirectories(logFile.getParent)
  }
  if (!Files.exists(logFile)) Files.createFile(logFile)

  private val auditActor: ActorRef =
    actorSystem.actorOf(FileEventStoreActor.props(logFile), s"file-event-store-actor-${java.util.UUID.randomUUID().toString.take(8)}")

  logger.info(s"[FileEventStore] Initialized actor-based event store repository targeting: ${logFile.toAbsolutePath}")

  lifecycle.addStopHook { () =>
    (auditActor ? FlushAndClose).mapTo[Unit]
  }

  override def append(event: OrderEvent): Future[Unit] = {
    (auditActor ? AppendEvent(event)).mapTo[Unit]
  }

  override def readAll(): Future[List[OrderEvent]] = {
    (auditActor ? ReadAllEvents).mapTo[List[OrderEvent]]
  }

  def close(): Unit = {
    import org.apache.pekko.pattern.gracefulStop
    import scala.concurrent.Await
    try {
      Await.result(gracefulStop(auditActor, 3.seconds, FlushAndClose), 4.seconds)
    } catch {
      case _: Exception => ()
    }
  }
}
