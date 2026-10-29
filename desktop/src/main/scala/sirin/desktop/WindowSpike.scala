package sirin.desktop

/** Visible smoke/resize/idle receipt. A clear is not a rendered UI. */
object WindowSpike:
  def main(args: Array[String]): Unit =
    NativeWindow.verifyAbi()
    require(NativeWindow.firstThread(), "macOS main-thread launch required")
    if args.headOption.contains("failures") then
      try
        for stage <- 1 to 8 do
          NativeWindow.failAt(stage)
          var failed=false
          try
            val unexpected=new NativeWindow("Sirin fault probe",80,60)
            unexpected.close()
          catch case _: IllegalStateException => failed=true
          require(failed,s"fault stage $stage did not fail")
          require(NativeWindow.liveWindows()==0 && NativeWindow.liveRequests()==0,s"fault stage $stage leaked")
          println(s"partialInitStage=$stage liveWindows=0 liveRequests=0")
      finally NativeWindow.failAt(0)
      return
    val window = new NativeWindow("Sirin JVM development host", 800, 520)
    var presents = 0
    var wakeups = 0
    def draw(): Unit = if window.clear(0.055, 0.075, 0.11) then presents += 1
    try
      val mode = args.headOption.getOrElse("smoke")
      var event = window.waitEvent(100)
      draw()
      if mode == "resize" then
        for (w, h) <- Vector((640, 400), (1024, 700), (800, 520)) do
          window.resize(w, h)
          val until = System.nanoTime() + 300000000L
          while System.nanoTime() < until do
            event = window.waitEvent(50)
            if event.redraw() then draw()
          require(event.width()<=1280 && event.height()<=800,"physical resize budget exceeded")
          draw()
      // Drain startup events until one quiet interval, then prove a worker wakeup.
      while window.waitEvent(100).found() do ()
      val worker = new Thread(() => { Thread.sleep(250); require(window.wake(), "wake rejected") }, "sirin-spike-wakeup")
      worker.start()
      var delivered = false
      val wakeDeadline = System.nanoTime() + 3000000000L
      while !delivered && System.nanoTime() < wakeDeadline do
        event = window.waitEvent(500); wakeups += 1
        delivered = event.wakeup()
        if event.redraw() then draw()
      worker.join()
      require(delivered, "worker did not wake native event wait")
      if mode == "interactive" then
        while !event.quit() do
          event = window.waitEvent(-1)
          if event.redraw() then draw()
      else
        val before = presents
        val deadline = System.nanoTime() + 2000000000L
        while System.nanoTime() < deadline do
          val remaining = math.max(1L, (deadline-System.nanoTime()+999999L)/1000000L).min(Int.MaxValue).toInt
          event = window.waitEvent(remaining); wakeups += 1
          if event.redraw() then draw()
        println(s"idlePresents=${presents-before}")
      println(s"ffmAbi=passed firstThread=${NativeWindow.firstThread()} pixels=${event.width()}x${event.height()} presents=$presents wakeups=$wakeups workerWake=$delivered")
    finally window.close()
    require(NativeWindow.liveWindows()==0, "native window leaked")
    require(NativeWindow.liveRequests()==0,"native request leaked")
    require(!window.wake(), "closed wake accepted")
    println("cleanShutdown=true liveWindows=0")
