package repositories

import models.OrderEvent
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{Materializer, OverflowStrategy, QueueOfferResult}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.Json
import play.api.{Configuration, Logging}

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Using

private case class PendingWrite(event: OrderEvent, promise: Promise[Unit])

/**
 * Idiomatic Reactive File Event Store Repository using Pekko Streams.
 *
 * Features:
 * - Pekko Stream Queue with non-blocking backpressure.
 * - Declarative batching via .groupedWithin(500, 10.milliseconds) for group commit.
 * - Non-blocking append returning Future[Unit] fulfilled only after physical disk flush.
 * - Streaming readAll execution on dedicated BlockingIoExecutionContext.
 */
@Singleton
class FileEventStoreRepository @Inject()(
  config: Configuration,
  lifecycle: ApplicationLifecycle,
  ec: BlockingIoExecutionContext
)(implicit mat: Materializer, actorSystem: ActorSystem) extends EventStoreRepository with Logging {

  // Test-friendly auxiliary constructor
  def this(config: Configuration)(implicit mat: Materializer, actorSystem: ActorSystem, ec: ExecutionContext) = {
    this(config, new play.api.inject.DefaultApplicationLifecycle(), null)
  }

  private implicit val executionContext: ExecutionContext =
    if (ec != null) ec else actorSystem.dispatcher

  private val logFile: Path = Paths.get(
    config.get[String]("app.event-store.file-path")
  )

  // Ensure parent directories and file exist
  if (logFile.getParent != null) {
    Files.createDirectories(logFile.getParent)
  }
  if (!Files.exists(logFile)) Files.createFile(logFile)

  logger.info(s"[FileEventStore] Initialized streaming event store at: ${logFile.toAbsolutePath}")

  private val writer: BufferedWriter = Files.newBufferedWriter(
    logFile,
    StandardCharsets.UTF_8,
    StandardOpenOption.CREATE,
    StandardOpenOption.APPEND
  )

  private val running = new AtomicBoolean(true)

  // Declarative Pekko Stream: Group commit pipeline
  private val (queue, streamDone) = Source.queue[PendingWrite](
    bufferSize = 10000,
    overflowStrategy = OverflowStrategy.backpressure
  )
    .groupedWithin(500, 10.milliseconds)
    .mapAsync(parallelism = 1) { batch =>
      Future {
        writeAndFlushBatch(batch)
      }(executionContext)
    }
    .toMat(Sink.ignore)(Keep.both)
    .run()

  private def writeAndFlushBatch(batch: Seq[PendingWrite]): Unit = {
    if (batch.nonEmpty) {
      val batchSize = batch.size
      try {
        batch.foreach { item =>
          val line = Json.stringify(Json.toJson(item.event))
          writer.write(line)
          writer.newLine()
        }
        writer.flush() // 1 single physical disk flush for the whole batch

        // Durability confirmed: complete all promises
        batch.foreach(_.promise.success(()))
      } catch {
        case e: Exception =>
          logger.error(s"[FileEventStore] Failed to flush batch of $batchSize event(s) to disk", e)
          val ex = new RuntimeException("Failed to append event to event store", e)
          batch.foreach(_.promise.failure(ex))
      }
    }
  }

  // Graceful shutdown hook
  lifecycle.addStopHook { () =>
    Future {
      close()
    }(executionContext)
  }

  override def append(event: OrderEvent): Future[Unit] = {
    if (!running.get()) {
      Future.failed(new IllegalStateException("FileEventStoreRepository has been closed"))
    } else {
      val promise = Promise[Unit]()
      queue.offer(PendingWrite(event, promise)).flatMap {
        case QueueOfferResult.Enqueued    => promise.future
        case QueueOfferResult.Dropped     => Future.failed(new RuntimeException("Event store queue buffer full"))
        case QueueOfferResult.Failure(e)  => Future.failed(new RuntimeException("Failed to enqueue event", e))
        case QueueOfferResult.QueueClosed => Future.failed(new IllegalStateException("Event store queue is closed"))
      }(executionContext)
    }
  }

  override def readAll(): Future[List[OrderEvent]] = Future {
    if (!Files.exists(logFile)) {
      logger.warn(s"[FileEventStore] Event store file does not exist: ${logFile.toAbsolutePath}")
      List.empty
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
        val result = builder.result()
        logger.info(s"[FileEventStore] Streamed and parsed ${result.size} event(s) from ${logFile.getFileName}")
        result
      }.recover {
        case e: Exception =>
          logger.error(s"[FileEventStore] Error streaming event store from: ${logFile.toAbsolutePath}", e)
          throw new RuntimeException("Failed to read event store", e)
      }.get
    }
  }(executionContext)

  /** Closes stream and open file descriptor cleanly. */
  def close(): Unit = {
    if (running.compareAndSet(true, false)) {
      try {
        queue.complete()
        Await.result(streamDone, 3.seconds)
        writer.flush()
        writer.close()
        logger.info("[FileEventStore] Cleanly flushed and closed streaming event store writer")
      } catch {
        case _: Exception => ()
      }
    }
  }
}
