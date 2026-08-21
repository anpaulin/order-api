package tools

import java.io.BufferedWriter
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.time.Duration
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.{ConcurrentHashMap, Executors, Semaphore, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.util.Random

/**
 * High-performance, concise traffic simulator for the Order Service.
 *
 * Workload distribution:
 *   - 60% Create Order (POST /orders)
 *   - 25% Update Order (PATCH /orders/:id)
 *   - 10% Delete Order (DELETE /orders/:id)
 *   - 5%  Search Orders (GET /orders/search)
 *
 * Features:
 *   - Bounded in-flight concurrency (prevents OS TCP socket exhaustion)
 *   - Race-free ID lifecycle (atomically claims IDs before deletion to eliminate 404 races)
 *   - Persistent structured error logging to ./data/simulator-errors.log
 *   - Interactive graceful shutdown via [Enter] or Ctrl+C
 */
object OrderSimulator {

  private val currencies = Array("USD", "CAD", "EUR", "GBP")
  private val txTypes    = Array("Sale", "Refund")

  private val activeIds    = ConcurrentHashMap.newKeySet[String]()
  private val totalCreated = new AtomicLong(0)
  private val totalUpdated = new AtomicLong(0)
  private val totalDeleted = new AtomicLong(0)
  private val totalSearched= new AtomicLong(0)
  private val totalSuccess = new AtomicLong(0)
  private val totalErrors  = new AtomicLong(0)

  def main(args: Array[String]): Unit = {
    val rate     = parseArg(args, "--rate", 10)
    val target   = parseArg(args, "--target", "http://localhost:9000").stripSuffix("/")
    val duration = parseArg(args, "--duration", 0L)
    val errorLog = Paths.get(parseArg(args, "--error-log", "./data/simulator-errors.log"))

    // Initialize error log writer
    if (errorLog.getParent != null) Files.createDirectories(errorLog.getParent)
    val errorWriter = Files.newBufferedWriter(
      errorLog, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND
    )

    println("=" * 60)
    println(s"🚀 Order Service Traffic Simulator")
    println(s"   Target URL : $target")
    println(s"   Rate       : $rate reqs/sec")
    println(s"   Duration   : ${if (duration > 0) s"${duration}s" else "Unlimited (Press [Enter] or Ctrl+C to stop)"}")
    println(s"   Error Log  : ${errorLog.toAbsolutePath}")
    println("=" * 60)

    val client = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build()

    val running = new AtomicBoolean(true)
    val maxInFlight = new Semaphore(1000) // Bound in-flight sockets to protect OS TCP limits

    val daemonFactory: java.util.concurrent.ThreadFactory = (r: Runnable) => {
      val t = new Thread(r)
      t.setDaemon(true)
      t
    }
    val executor = Executors.newScheduledThreadPool(4, daemonFactory)
    val metricsScheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory)


    def logError(method: String, url: String, status: Int, reason: String, payload: String = ""): Unit = {
      totalErrors.incrementAndGet()
      val ts = java.time.Instant.now().toString
      val cleanReason = if (reason == null || reason.isBlank) "Unknown" else reason.replace("\n", " ").trim
      val bodyInfo = if (payload.nonEmpty) s" | Payload: $payload" else ""
      synchronized {
        try {
          errorWriter.write(s"[$ts] $method $url | Status: $status | Reason: $cleanReason$bodyInfo\n")
          errorWriter.flush()
        } catch { case _: Exception => () }
      }
    }

    def sendAsync(req: HttpRequest, method: String, url: String, payload: String = "")(onSuccess: HttpResponse[String] => Unit): Unit = {
      if (!maxInFlight.tryAcquire()) {
        logError(method, url, 0, "Client concurrency saturated (max 1000 in-flight)")
        return
      }
      client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept { resp =>
        maxInFlight.release()
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
          totalSuccess.incrementAndGet()
          onSuccess(resp)
        } else {
          logError(method, url, resp.statusCode(), resp.body(), payload)
        }
      }.exceptionally { ex =>
        maxInFlight.release()
        logError(method, url, 0, s"Network exception: ${ex.getMessage}", payload)
        null
      }
    }

    def sendCreate(): Unit = {
      val (amt, cur, tx) = (f"${Random.nextDouble() * 500 + 10}%.2f", randomChoice(currencies), randomChoice(txTypes))
      val body = s"""{"amount": $amt, "currencyCode": "$cur", "transactionType": "$tx"}"""
      val req = HttpRequest.newBuilder(URI.create(s"$target/orders"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(Duration.ofSeconds(5)).build()

      sendAsync(req, "POST", s"$target/orders", body) { resp =>
        totalCreated.incrementAndGet()
        val loc = resp.headers().firstValue("Location").orElse("")
        val id = if (loc.startsWith("/orders/")) loc.stripPrefix("/orders/") else extractId(resp.body())
        if (id.nonEmpty) activeIds.add(id)
      }
    }

    def sendUpdate(): Unit = {
      pickId() match {
        case Some(id) =>
          val (amt, tx) = (f"${Random.nextDouble() * 300 + 5}%.2f", randomChoice(txTypes))
          val body = s"""{"amount": $amt, "transactionType": "$tx"}"""
          val req = HttpRequest.newBuilder(URI.create(s"$target/orders/$id"))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(5)).build()

          sendAsync(req, "PATCH", s"$target/orders/$id", body) { _ => totalUpdated.incrementAndGet() }
        case None => sendCreate()
      }
    }

    def sendDelete(): Unit = {
      claimIdForDelete() match {
        case Some(id) =>
          val req = HttpRequest.newBuilder(URI.create(s"$target/orders/$id"))
            .DELETE().timeout(Duration.ofSeconds(5)).build()

          sendAsync(req, "DELETE", s"$target/orders/$id") { _ => totalDeleted.incrementAndGet() }
        case None => sendCreate()
      }
    }

    def sendSearch(): Unit = {
      val (cur, tx) = (randomChoice(currencies), randomChoice(txTypes))
      val url = s"$target/orders/search?currencyCode=$cur&transactionType=$tx"
      val req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(5)).build()

      sendAsync(req, "GET", url) { _ => totalSearched.incrementAndGet() }
    }

    def claimIdForDelete(): Option[String] = {
      val ids = activeIds.asScala.toIndexedSeq
      if (ids.size > 20) {
        val candidate = ids(Random.nextInt(ids.size))
        if (activeIds.remove(candidate)) Some(candidate) else None
      } else None
    }

    def pickId(): Option[String] = {
      val ids = activeIds.asScala.toIndexedSeq
      if (ids.nonEmpty) Some(ids(Random.nextInt(ids.size))) else None
    }

    // Warm-up seeding
    println("🌱 Seeding initial order pool...")
    (1 to 30).foreach { _ => sendCreate(); Thread.sleep(15) }
    Thread.sleep(200)
    println(s"✅ Initial pool seeded (${activeIds.size} orders). Starting traffic simulation...\n")

    // Metrics reporter
    var lastTime = System.currentTimeMillis()
    var (lastSuccess, lastErrors) = (0L, 0L)
    metricsScheduler.scheduleAtFixedRate(() => {
      val now = System.currentTimeMillis()
      val delta = (now - lastTime) / 1000.0
      val (succ, errs) = (totalSuccess.get(), totalErrors.get())
      val rps = if (delta > 0) (succ - lastSuccess + errs - lastErrors) / delta else 0.0
      println(f"[STATS] Throughput: $rps%6.1f req/s | Active IDs: ${activeIds.size}%4d | Created: ${totalCreated.get}%5d | Updated: ${totalUpdated.get}%5d | Deleted: ${totalDeleted.get}%5d | Errors: ${errs}%d")
      lastTime = now; lastSuccess = succ; lastErrors = errs
    }, 2, 2, TimeUnit.SECONDS)

    // Traffic dispatch loop
    val intervalMicros = (1000000.0 / rate).toLong
    executor.scheduleAtFixedRate(() => {
      if (running.get()) {
        Random.nextInt(100) match {
          case r if r < 60 || activeIds.size < 15 => sendCreate()
          case r if r < 85                        => sendUpdate()
          case r if r < 95                        => sendDelete()
          case _                                  => sendSearch()
        }
      }
    }, 0, intervalMicros, TimeUnit.MICROSECONDS)

    def shutdown(): Unit = {
      if (running.compareAndSet(true, false)) {
        executor.shutdownNow()
        metricsScheduler.shutdownNow()
        try { errorWriter.flush(); errorWriter.close() } catch { case _: Exception => () }
        printSummary(errorLog)
      }
    }

    Runtime.getRuntime.addShutdownHook(new Thread(() => shutdown()))

    if (duration > 0) {
      Thread.sleep(duration * 1000)
      println(s"\n⏱️ Duration of ${duration}s reached. Stopping simulator...")
      shutdown()
    } else {
      println("💡 Tip: Press [Enter] at any time to stop the simulator.\n")
      try { scala.io.StdIn.readLine() } catch { case _: Exception => while (running.get()) Thread.sleep(1000) }
      println("\n🛑 Stopping simulator...")
      shutdown()
    }
  }

  private def parseArg[T](args: Array[String], name: String, default: T): T = {
    val idx = args.indexOf(name)
    if (idx >= 0 && idx + 1 < args.length) {
      default match {
        case _: Int    => args(idx + 1).toInt.asInstanceOf[T]
        case _: Long   => args(idx + 1).toLong.asInstanceOf[T]
        case _: String => args(idx + 1).asInstanceOf[T]
      }
    } else default
  }

  private def randomChoice[T](arr: Array[T]): T = arr(Random.nextInt(arr.length))

  private def extractId(body: String): String = {
    val regex = """"id"\s*:\s*"([a-f0-9\-]+)"""".r
    regex.findFirstMatchIn(body).map(_.group(1)).getOrElse("")
  }

  private def printSummary(errorLog: Path): Unit = {
    println("\n" + "=" * 60)
    println("📊 Simulation Summary")
    println(f"   Total Requests Success : ${totalSuccess.get}%,d")
    println(f"   Total Requests Failed  : ${totalErrors.get}%,d")
    println(f"   - Created Orders       : ${totalCreated.get}%,d")
    println(f"   - Updated Orders       : ${totalUpdated.get}%,d")
    println(f"   - Deleted Orders       : ${totalDeleted.get}%,d")
    println(f"   - Search Queries       : ${totalSearched.get}%,d")
    println(f"   - Active In-Memory     : ${activeIds.size}%,d")
    if (totalErrors.get() > 0) println(s"\n   ⚠️ Failed requests logged to: ${errorLog.toAbsolutePath}")
    println("=" * 60)
  }
}


