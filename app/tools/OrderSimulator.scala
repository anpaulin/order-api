package tools

import java.io.{BufferedWriter, IOException}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.time.Duration
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, ScheduledExecutorService, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.jdk.CollectionConverters.*
import scala.util.Random

/**
 * High-performance, configurable traffic simulator for the Order Service.
 *
 * Simulates a realistic workload with a configurable request rate:
 * - 60% Create Order (POST /orders)
 * - 25% Update Order (PATCH /orders/:id)
 * - 10% Delete Order (DELETE /orders/:id)
 * - 5%  Search Orders (GET /orders/search)
 *
 * All failed requests (non-2xx responses or network exceptions) are written
 * with timestamp, method, URL, status code, error reason, and payload to an error log.
 *
 * Usage via SBT:
 *   sbt "runMain tools.OrderSimulator --rate 20 --target http://localhost:9000"
 *
 * CLI Options:
 *   --rate <int>        Target requests per second (default: 10)
 *   --target <url>      Base URL of the Order Service (default: http://localhost:9000)
 *   --duration <sec>    Run duration in seconds (default: unlimited, press [Enter] to stop)
 *   --error-log <path>  Path to write error log (default: ./data/simulator-errors.log)
 */
object OrderSimulator {

  private val currencies = Array("USD", "CAD", "EUR", "GBP")
  private val txTypes    = Array("Sale", "Refund")

  private val activeOrderIds = new ConcurrentLinkedQueue[String]()
  private val totalCreated   = new AtomicLong(0)
  private val totalUpdated   = new AtomicLong(0)
  private val totalDeleted   = new AtomicLong(0)
  private val totalSearched  = new AtomicLong(0)
  private val totalSuccess   = new AtomicLong(0)
  private val totalErrors    = new AtomicLong(0)

  private var errorLogPath: Path = Paths.get("./data/simulator-errors.log")
  private var errorWriter: BufferedWriter = _

  def main(args: Array[String]): Unit = {
    var rate     = 10
    var target   = "http://localhost:9000"
    var duration = 0L // 0 = indefinite

    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--rate" if i + 1 < args.length =>
          rate = args(i + 1).toInt
          i += 1
        case "--target" if i + 1 < args.length =>
          target = args(i + 1).stripSuffix("/")
          i += 1
        case "--duration" if i + 1 < args.length =>
          duration = args(i + 1).toLong
          i += 1
        case "--error-log" if i + 1 < args.length =>
          errorLogPath = Paths.get(args(i + 1))
          i += 1
        case other =>
          println(s"Unknown argument: $other")
      }
      i += 1
    }

    // Initialize error log writer
    if (errorLogPath.getParent != null) {
      Files.createDirectories(errorLogPath.getParent)
    }
    errorWriter = Files.newBufferedWriter(
      errorLogPath,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )

    println("=" * 60)
    println(s"🚀 Order Service Traffic Simulator")
    println(s"   Target URL : $target")
    println(s"   Rate       : $rate reqs/sec")
    println(s"   Duration   : ${if (duration > 0) s"${duration}s" else "Unlimited (Press [Enter] or Ctrl+C to stop)"}")
    println(s"   Error Log  : ${errorLogPath.toAbsolutePath}")
    println("=" * 60)

    val client = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build()

    val running = new AtomicBoolean(true)
    val daemonFactory: java.util.concurrent.ThreadFactory = (r: Runnable) => {
      val t = new Thread(r)
      t.setDaemon(true)
      t
    }

    val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(4, daemonFactory)
    val metricsScheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory)

    var lastTime = System.currentTimeMillis()
    var lastSuccess = 0L
    var lastErrors = 0L

    metricsScheduler.scheduleAtFixedRate(() => {
      val now = System.currentTimeMillis()
      val deltaSec = (now - lastTime) / 1000.0
      val currentSuccess = totalSuccess.get()
      val currentErrors = totalErrors.get()

      val rps = if (deltaSec > 0) (currentSuccess - lastSuccess + currentErrors - lastErrors) / deltaSec else 0.0
      println(f"[STATS] Throughput: $rps%6.1f req/s | Active IDs: ${activeOrderIds.size}%4d | Created: ${totalCreated.get}%5d | Updated: ${totalUpdated.get}%5d | Deleted: ${totalDeleted.get}%5d | Errors: ${totalErrors.get}%d")

      lastTime = now
      lastSuccess = currentSuccess
      lastErrors = currentErrors
    }, 2, 2, TimeUnit.SECONDS)

    def shutdown(): Unit = {
      if (running.compareAndSet(true, false)) {
        executor.shutdownNow()
        metricsScheduler.shutdownNow()
        closeErrorWriter()
        printSummary()
      }
    }

    // Shutdown hook for Ctrl+C
    Runtime.getRuntime.addShutdownHook(new Thread(() => shutdown()))

    // Calculate delay between requests in microseconds
    val intervalMicros = (1000000.0 / rate).toLong

    // Task generator
    val task = new Runnable {
      override def run(): Unit = {
        if (!running.get()) return

        val rand = Random.nextInt(100)
        try {
          if (rand < 60 || activeOrderIds.isEmpty) {
            // 60% Create
            sendCreate(client, target)
          } else if (rand < 85) {
            // 25% Update
            sendUpdate(client, target)
          } else if (rand < 95) {
            // 10% Delete
            sendDelete(client, target)
          } else {
            // 5% Search
            sendSearch(client, target)
          }
        } catch {
          case ex: Exception =>
            logError("CLIENT_TASK", target, 0, ex.getMessage)
        }
      }
    }

    executor.scheduleAtFixedRate(task, 0, intervalMicros, TimeUnit.MICROSECONDS)

    if (duration > 0) {
      Thread.sleep(duration * 1000)
      println(s"\n⏱️ Duration of ${duration}s reached. Stopping simulator...")
      shutdown()
    } else {
      // Allow stopping by pressing Enter in sbt shell or console
      println("💡 Tip: Press [Enter] at any time in the sbt shell to stop the simulator.\n")
      try {
        scala.io.StdIn.readLine()
      } catch {
        case _: Exception => // non-interactive environment fallback
          while (running.get()) Thread.sleep(1000)
      }
      println("\n🛑 Stopping simulator...")
      shutdown()
    }
  }

  private def sendCreate(client: HttpClient, target: String): Unit = {
    val amount = f"${Random.nextDouble() * 500 + 10}%.2f"
    val currency = currencies(Random.nextInt(currencies.length))
    val txType = txTypes(Random.nextInt(txTypes.length))

    val body = s"""{"amount": $amount, "currencyCode": "$currency", "transactionType": "$txType"}"""
    val url = s"$target/orders"

    val req = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .timeout(Duration.ofSeconds(5))
      .build()

    client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept { resp =>
      if (resp.statusCode() == 201) {
        totalCreated.incrementAndGet()
        totalSuccess.incrementAndGet()

        val location = resp.headers().firstValue("Location").orElse("")
        val id = if (location.startsWith("/orders/")) location.stripPrefix("/orders/")
                 else extractId(resp.body())

        if (id.nonEmpty) {
          activeOrderIds.add(id)
          if (activeOrderIds.size() > 5000) activeOrderIds.poll()
        }
      } else {
        logError("POST", url, resp.statusCode(), resp.body(), body)
      }
    }.exceptionally { ex =>
      logError("POST", url, 0, s"Network exception: ${ex.getMessage}", body)
      null
    }
  }

  private def sendUpdate(client: HttpClient, target: String): Unit = {
    val id = pickRandomId()
    if (id.isEmpty) return

    val newAmount = f"${Random.nextDouble() * 300 + 5}%.2f"
    val newTxType = txTypes(Random.nextInt(txTypes.length))
    val body = s"""{"amount": $newAmount, "transactionType": "$newTxType"}"""
    val url = s"$target/orders/${id.get}"

    val req = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .header("Content-Type", "application/json")
      .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
      .timeout(Duration.ofSeconds(5))
      .build()

    client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept { resp =>
      if (resp.statusCode() == 200) {
        totalUpdated.incrementAndGet()
        totalSuccess.incrementAndGet()
      } else if (resp.statusCode() == 404) {
        activeOrderIds.remove(id.get)
        logError("PATCH", url, 404, "Order not found in server state", body)
      } else {
        logError("PATCH", url, resp.statusCode(), resp.body(), body)
      }
    }.exceptionally { ex =>
      logError("PATCH", url, 0, s"Network exception: ${ex.getMessage}", body)
      null
    }
  }

  private def sendDelete(client: HttpClient, target: String): Unit = {
    val id = pickRandomId()
    if (id.isEmpty) return

    val url = s"$target/orders/${id.get}"
    val req = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .DELETE()
      .timeout(Duration.ofSeconds(5))
      .build()

    client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept { resp =>
      if (resp.statusCode() == 204) {
        totalDeleted.incrementAndGet()
        totalSuccess.incrementAndGet()
        activeOrderIds.remove(id.get)
      } else if (resp.statusCode() == 404) {
        activeOrderIds.remove(id.get)
        logError("DELETE", url, 404, "Order not found in server state")
      } else {
        logError("DELETE", url, resp.statusCode(), resp.body())
      }
    }.exceptionally { ex =>
      logError("DELETE", url, 0, s"Network exception: ${ex.getMessage}")
      null
    }
  }

  private def sendSearch(client: HttpClient, target: String): Unit = {
    val currency = currencies(Random.nextInt(currencies.length))
    val txType = txTypes(Random.nextInt(txTypes.length))
    val url = s"$target/orders/search?currencyCode=$currency&transactionType=$txType"

    val req = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .GET()
      .timeout(Duration.ofSeconds(5))
      .build()

    client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept { resp =>
      if (resp.statusCode() == 200) {
        totalSearched.incrementAndGet()
        totalSuccess.incrementAndGet()
      } else {
        logError("GET", url, resp.statusCode(), resp.body())
      }
    }.exceptionally { ex =>
      logError("GET", url, 0, s"Network exception: ${ex.getMessage}")
      null
    }
  }

  private def logError(method: String, url: String, statusCode: Int, reason: String, payload: String = ""): Unit = {
    totalErrors.incrementAndGet()
    val ts = java.time.Instant.now().toString
    val cleanReason = if (reason == null || reason.isEmpty) "Unknown error" else reason.replace("\n", " ").trim
    val payloadInfo = if (payload.nonEmpty) s" | Payload: $payload" else ""
    val line = s"[$ts] $method $url | Status: $statusCode | Reason: $cleanReason$payloadInfo"

    if (errorWriter != null) {
      synchronized {
        try {
          errorWriter.write(line)
          errorWriter.newLine()
          errorWriter.flush()
        } catch {
          case _: Exception => ()
        }
      }
    }
  }

  private def closeErrorWriter(): Unit = {
    if (errorWriter != null) {
      synchronized {
        try {
          errorWriter.flush()
          errorWriter.close()
        } catch {
          case _: Exception => ()
        }
      }
    }
  }

  private def pickRandomId(): Option[String] = {
    val ids = activeOrderIds.asScala.toIndexedSeq
    if (ids.isEmpty) None
    else Some(ids(Random.nextInt(ids.length)))
  }

  private def extractId(body: String): String = {
    val regex = """"id"\s*:\s*"([a-f0-9\-]+)"""".r
    regex.findFirstMatchIn(body).map(_.group(1)).getOrElse("")
  }

  private def printSummary(): Unit = {
    println("\n" + "=" * 60)
    println("📊 Simulation Summary")
    println(f"   Total Requests Success : ${totalSuccess.get}%,d")
    println(f"   Total Requests Failed  : ${totalErrors.get}%,d")
    println(f"   - Created Orders       : ${totalCreated.get}%,d")
    println(f"   - Updated Orders       : ${totalUpdated.get}%,d")
    println(f"   - Deleted Orders       : ${totalDeleted.get}%,d")
    println(f"   - Search Queries       : ${totalSearched.get}%,d")
    println(f"   - Remaining In-Memory  : ${activeOrderIds.size}%,d")
    if (totalErrors.get() > 0) {
      println(s"\n   ⚠️ Failed requests logged to: ${errorLogPath.toAbsolutePath}")
    }
    println("=" * 60)
  }
}

