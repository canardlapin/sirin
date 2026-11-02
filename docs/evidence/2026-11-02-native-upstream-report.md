# Draft for upstream: Darwin ARM64 Immix safepoint timeout and signal-mask mismatch

Status: prepared for maintainer review; not submitted. The sections below are
self-contained public report text. Local receipts are listed in the companion
qualification evidence, rather than presenting inaccessible local paths as
reproduction instructions.

---

On Darwin ARM64, a standalone allocation/sleep program aborts waiting for a
managed thread to reach a GC safepoint. It uses only Scala Native/JDK APIs and
two runtime counter externs; no SDL, application framework or custom C code.
I am seeking review of a signal-mask mismatch observed at the redirected
safepoint trampoline, and of an experimental saved-mask restoration control.

Related reports are [#5051](https://github.com/scala-native/scala-native/issues/5051)
(macOS trap-path synchronization timeout) and
[#5046](https://github.com/scala-native/scala-native/issues/5046)
(trap-path live-array corruption). This report adds a smaller Immix reproduction
and signal-mask observations; I have not established that their causes are the same.

## Tested configuration

- macOS 15.1.1, build 24B91, ARM64.
- Scala 3.7.4; release-fast, Immix, multithreading enabled.
- Released Scala Native 0.5.12 and the complete toolchain at
  `fa4a9e6ac9dab73ab4d476d6cc8c0cdc422c25f7` were tested. The latter includes
  merged allocator-growth fix [#5128](https://github.com/scala-native/scala-native/pull/5128).
- Runs use `GC_MAXIMUM_HEAP_SIZE=128M`,
  `SCALANATIVE_GC_SYNC_TIMEOUT_MS=15000` and an independent 75-second deadline
  for a requested 60-second run. Defaults and dependency caches are untouched.

In a frozen stock/pinned × trap/conditional matrix, all six trap runs aborted
on GC synchronization; all six conditional runs completed. Conditional mode
was selected during linking, using `SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0`.
The prior allocation-growth bug is a separate mechanism.

## Signal-mask evidence and causal control

A query-only diagnostic compares the saved interrupted-context mask with the
mask at normal-context trampoline entry and after yield. One failing snapshot
has saved mask `0x40040000` and entry/post-yield mask `0x40040200`: SIGBUS is
additionally blocked. An unrelated inherited blocked SIGUSR2 bit is preserved.
In that record, 965 activations completed without observed overlap or resume-PC
ownership mismatch. Another mutator remains inside yield. These are per-thread
observations, not an atomic global snapshot or a direct sigreturn observation.

A private control copies the full interrupted mask to mutator storage and
restores it in normal trampoline context before `Synchronizer_yield`. It changes
neither signal-handler flags nor assembly. Three uninstrumented short controls
and a four-allocator survivor check completed; surrounding originals aborted.
A SA_NODEFER-only experiment had failed and was rejected earlier.

A later diagnostic covers redirect through the assembly restore tail, including
the final branch. It detects synthetic injected overlap and resume-PC overwrite.
Three short restoration runs and one 120-second/four-allocator run observed
33,126 completed activations in retiring mutators, with zero observed overlap,
owner/PC mismatch or loss of SIGUSR2. Six entry-mask mismatches in the short
runs were corrected before yield. The main mutator has no final disposal
snapshot. Instrumentation changes code layout and timing; absence of observed
overlap does not prove nesting impossible. No production patch is adopted.

## Questions for runtime review

1. Is restoring the interrupted context's full mask at normal trampoline entry
   the correct Darwin contract, or should the redirect/return path restore it
   differently?
2. Can another redirect overlap this trampoline's restore tail or overwrite the
   single mutator resume-PC/mask slot? What invariant excludes it, or what
   storage/ordering change is needed?
3. What coverage should be added for the main mutator, other asynchronous
   signals and register preservation before treating the control as a patch?

The bounded standalone controls do not qualify application latency. The separate
600-second event workload retains its original 50 ms event/ticker bounds.
Conditional mode is our qualification candidate while this trap-path review is open.

## Reproduction

Create an independent sbt project with the source and build files below. Run
`sbt clean nativeLink` without a yieldpoint override for the trap control, save
the binary, then clean/relink with `SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0`
for the conditional control. Merely setting that variable at execution time
does not change an existing binary. Run each binary with arguments
`60 1 2048 true`, the environment above and an external watchdog. Preserve
every trial, including failures; the source checks surviving array endpoints.

The following source/build excerpts and experimental patch are included so
review does not depend on access to our application repository.

### src/main/scala/GcRepro.scala

```scala
import java.util.concurrent.atomic.AtomicBoolean
import scala.scalanative.unsafe.*

@extern
object Counters:
  def scalanative_GC_get_used_heapsize(): CSize = extern
  def scalanative_GC_stats_collection_total(): CSize = extern

/** Only Scala Native/JDK APIs: no Sirin, SDL, mailbox, or custom C code. */
object GcRepro:
  final class Worker(id: Int, stopped: AtomicBoolean, slots: Int) extends Thread(s"alloc-$id"):
    // Keep the bounded live set observable after join, including in optimized builds.
    val ring = new Array[Array[Byte]](slots)
    @volatile var iterations = 0L
    var checksum = 0L
    override def run(): Unit =
      var x = id * 7919 + 1
      var it = 0L
      var acc = 0L
      while !stopped.get() do
        x = x * 1103515245 + 12345
        val n = 16 + ((x >>> 8) & 1023)
        val a = new Array[Byte](n)
        a(0) = x.toByte
        a(n - 1) = x.toByte
        if slots > 0 then ring((it % slots).toInt) = a
        val l = List(it, it + 1, it + 2)
        val s = java.lang.Long.toString(it)
        acc += l.sum + s.length + a(0)
        it += 1
        if (it & 255) == 0 then iterations = it
      iterations = it
      checksum = acc

  def main(args: Array[String]): Unit =
    require(args.length >= 2 && args.length <= 4,
      "usage: gc-repro seconds workers [survivor-slots=2048] [ticker=true]")
    val seconds = args(0).toInt
    val count = args(1).toInt
    val slots = args.lift(2).map(_.toInt).getOrElse(2048)
    val tickerEnabled = args.lift(3).map(_.toBoolean).getOrElse(true)
    require(seconds > 0 && count > 0 && slots >= 0)
    val stopped = new AtomicBoolean(false)
    val workers = Array.tabulate(count)(new Worker(_, stopped, slots))
    val ticker = new Thread("ticker"):
      override def run(): Unit =
        while !stopped.get() do Thread.sleep(1)
    val start = System.nanoTime()
    def report(label: String): Unit =
      println(s"$label elapsed_ms=${(System.nanoTime() - start) / 1000000} workers=$count slots=$slots ticker=$tickerEnabled heap_bytes=${Counters.scalanative_GC_get_used_heapsize().toLong} collections=${Counters.scalanative_GC_stats_collection_total().toLong} iterations=${workers.map(_.iterations).sum}")
      Console.flush()
    report("START")
    workers.foreach(_.start())
    if tickerEnabled then ticker.start()
    val deadline = start + seconds * 1000000000L
    var next = start + 5000000000L
    while System.nanoTime() < deadline do
      Thread.sleep(100)
      if System.nanoTime() >= next then
        report("SAMPLE")
        next += 5000000000L
    stopped.set(true)
    workers.foreach(_.join())
    if tickerEnabled then ticker.join()
    var liveBytes = 0L
    workers.foreach { w =>
      w.ring.foreach { a =>
        if a != null then
          require(a.nonEmpty && a(0) == a(a.length - 1), "survivor corrupted")
          liveBytes += a.length
      }
    }
    report("DONE")
    println(s"RESULT live_payload_bytes=$liveBytes checksum=${workers.map(_.checksum).sum}")
```

### build.sbt

```scala
import scala.scalanative.build.{GC, Mode}

enablePlugins(ScalaNativePlugin)
name := "gc-repro"
scalaVersion := "3.7.4"
scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror")
publish / skip := true
nativeConfig := {
  val mode = sys.props.getOrElse("repro.mode", "release-fast") match {
    case "debug" => Mode.debug
    case "release-fast" => Mode.releaseFast
    case other => sys.error(s"Unknown repro.mode: $other")
  }
  val gc = sys.props.getOrElse("repro.gc", "immix") match {
    case "immix" => GC.immix
    case "commix" => GC.commix
    case other => sys.error(s"Unknown repro.gc: $other")
  }
  nativeConfig.value.withMode(mode).withGC(gc).withMultithreading(true)
}

// Opt-in, isolated runtime experiments; the normal dependency remains pinned.
Compile / dependencyClasspath ~= { entries =>
  sys.props.get("repro.runtimeJar") match {
    case None => entries
    case Some(path) =>
      val replacement = file(path).getCanonicalFile
      require(replacement.isFile, s"Missing runtime JAR: $replacement")
      val matches = entries.count(_.data.getName.startsWith("nativelib_native"))
      require(matches == 1, s"Expected one nativelib dependency, found $matches")
      entries.map { entry =>
        if (entry.data.getName.startsWith("nativelib_native")) Attributed.blank(replacement)
        else entry
      }
  }
}
```

### project/plugins.sbt

```scala
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")
```

### project/build.properties

```properties
sbt.version=1.12.14
```

### Experimental saved-mask patch against pinned fa4a9e6

```diff
--- a/scala-native/gc/immix/MutatorThread.h
+++ b/scala-native/gc/immix/MutatorThread.h
@@ -8,6 +8,9 @@
 #include <stdbool.h>
 #include "nativeThreadTLS.h"
 #include <stdint.h>
+#ifdef __APPLE__
+#include <signal.h>
+#endif
 
 typedef struct {
     _Atomic(GC_MutatorThreadState) state;
@@ -35,6 +38,10 @@
     void **yieldpointTrap;
     /* Faulting PC when using deferred safepoint trampoline (POSIX). */
     uintptr_t safepointResumePc;
+#ifdef __APPLE__
+    /* Private causal control: preserve the pre-trap context mask. */
+    sigset_t safepointSignalMask;
+#endif
 #endif
 } MutatorThread;
 
--- a/scala-native/gc/shared/SafepointPollTrampoline.c
+++ b/scala-native/gc/shared/SafepointPollTrampoline.c
@@ -33,6 +33,9 @@
 #include <stddef.h>
 #include <stdint.h>
 #include <ucontext.h>
+#include <signal.h>
+#include <errno.h>
+#include <stdlib.h>
 
 #if defined(SCALANATIVE_GC_IMMIX)
 #include "immix/State.h"
@@ -44,7 +47,16 @@
 #error "SafepointPollTrampoline requires immix or commix"
 #endif
 
-void scalanative_gc_safepoint_poll_run_yield(void) { Synchronizer_yield(); }
+void scalanative_gc_safepoint_poll_run_yield(void) {
+#if defined(__APPLE__) && defined(SCALANATIVE_GC_IMMIX)
+    /* Experimental Darwin control, not adopted runtime policy. */
+    int saved_errno = errno;
+    if (sigprocmask(SIG_SETMASK, &currentMutatorThread->safepointSignalMask, NULL) != 0)
+        abort();
+    errno = saved_errno;
+#endif
+    Synchronizer_yield();
+}
 
 uintptr_t scalanative_gc_safepoint_load_resume_pc(void) {
     MutatorThread *self = currentMutatorThread;
@@ -99,6 +111,9 @@
     if (uap == NULL || self == NULL)
         return false;
     ucontext_t *uc = (ucontext_t *)uap;
+#if defined(__APPLE__) && defined(SCALANATIVE_GC_IMMIX)
+    self->safepointSignalMask = uc->uc_sigmask;
+#endif
     uintptr_t pc = SN_UC_GET_PC(uc);
     self->safepointResumePc = pc;
 #if defined(__x86_64__)
```
