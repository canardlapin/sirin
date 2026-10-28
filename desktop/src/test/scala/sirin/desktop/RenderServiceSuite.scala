package sirin.desktop

import java.nio.file.Path
import java.util.concurrent.{CountDownLatch,TimeUnit}
import sirin.host.*

class RenderServiceSuite extends munit.FunSuite:
  private val root=Node(NodeId(Vector("test")),Content.Space(0))
  private def request(id: Long)=FrameRequest(id,FramePayload(root,10,10,10,10,Map.empty))
  private def layout=sirin.host.Layout(root,10,10)(_ => fail("no text"))
  test("failed session initialization is sticky and cannot be overwritten by first submission") {
    val error=new IllegalStateException("injected initialization failure")
    val service=new RenderService(Path.of("unused"),Path.of("unused"),() => (),Some(() => throw error))
    try
      assert(service.awaitStopped(2000))
      assertEquals(intercept[IllegalStateException](service.submit(request(1))),error)
      assert(service.poll().exists(_._2.left.toOption.contains(error)))
    finally service.close()
  }
  test("in-flight obsolete failure cannot replace the next generation result") {
    val entered=new CountDownLatch(1);val release=new CountDownLatch(1);val done=new CountDownLatch(1)
    val executed=new java.util.concurrent.ConcurrentLinkedQueue[Long]()
    val released=new java.util.concurrent.atomic.AtomicBoolean(false)
    val backend=new RenderBackend:
      def stats="test"
      def close(): Unit=()
      def render(r: FrameRequest,cancelled: () => Boolean): FrameOutput =
        executed.add(r.generation)
        if r.generation==1 then
          entered.countDown()
          val allowed=release.await(2,TimeUnit.SECONDS)
          released.set(allowed)
          if !allowed then throw new IllegalStateException("test release timed out")
          throw new IllegalStateException("obsolete failure")
        LayoutFeedback(r,layout)
    val service=new RenderService(Path.of("unused"),Path.of("unused"),() => done.countDown(),Some(() => backend))
    try
      service.submit(request(1));assert(entered.await(2,TimeUnit.SECONDS))
      service.submit(request(2));release.countDown()
      val completed=done.await(2,TimeUnit.SECONDS)
      if !completed then
        Thread.getAllStackTraces.forEach((t,stack) => if t.getName=="sirin-render-owner" then println(s"owner state=${t.getState} stack=${stack.toVector.mkString("\n")}"))
      assert(completed,s"executed=$executed result=${service.poll()}")
      assert(released.get(),"the first generation did not receive its release before the unchanged timeout")
      val result=service.poll().get
      assertEquals(result._1,2L);assertEquals(result._2.toOption.map(_.request.generation),Some(2L))
    finally {release.countDown();service.close()}
  }

  test("diagnostic failure still retires backend and is reported on close") {
    val retired=new java.util.concurrent.atomic.AtomicBoolean(false)
    val error=new IllegalStateException("injected stats failure")
    val backend=new RenderBackend:
      def stats: String=throw error
      def close(): Unit=retired.set(true)
      def render(r: FrameRequest,cancelled: () => Boolean): FrameOutput=LayoutFeedback(r,layout)
    val service=new RenderService(Path.of("unused"),Path.of("unused"),() => (),Some(() => backend))
    assertEquals(intercept[IllegalStateException](service.close()),error)
    assert(retired.get())
  }
  test("invalid tile size fails before opening native resources") {
    val before=sirin.render.OffscreenGpu.liveOwners
    intercept[IllegalArgumentException](new RenderSession(Path.of("unused"),Path.of("unused"),0))
    assertEquals(sirin.render.OffscreenGpu.liveOwners,before)
  }
