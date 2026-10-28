package sirin.desktop

import java.nio.file.Path
import sirin.*
import sirin.engine.*
import sirin.host.*

/** Headless diagnostic only; this does not measure input-to-present latency. */
object RenderProfile:
  def main(args: Array[String]): Unit =
    val root=Path.of(System.getProperty("sirin.repo"))
    for edge <- Vector(128) do
      val runtime=new HeadlessRuntime
      val session=new RenderSession(root.resolve("graphics/src/test/resources/fonts"),root.resolve("desktop/target/frames"),edge)
      try
        val scope=runtime.scope("profile");val app=new VisibleWorkbench(scope)
        runtime.transact {app.model.rows.setAll((1 to 100).map(id => sirin.specimen.Record(id,s"Record $id")))}
        val mount=runtime.mount(scope,app.view);runtime.observe(mount.snapshot)(_ => ())
        var viewports=Map.empty[Long,RowViewport];var sequence=0L
        var scrolls=Map.empty[Long,Double]
        def render(focus: Option[NodeId]): RenderedFrame =
          sequence+=1
          val captured=runtime.withCommitted(reader => reader(mount.snapshot) match
            case Value.Ok(s) => CommittedTree.capture(s,reader)
            case Value.Failed(e) => throw e)
          val request=FrameRequest(sequence,FramePayload(captured,600,360,1200,720,scrolls,focus,true,None,viewports))
          session.render(request,() => false) match
            case frame: RenderedFrame => frame
            case feedback: LayoutFeedback =>
              runtime.transact {feedback.layout.viewports.foreach(v => mount.setViewport(v.instance,v.viewport))}
              viewports=feedback.layout.viewports.map(v => v.instance->v.viewport).toMap
              render(focus)
        val cold=render(None)
        val targets=InteractionSnapshot(1,cold.layout).targets
        for index <- 0 until 12 do
          val focus=Some(targets(index%targets.size).id)
          val value=render(focus)
          println(s"profile edge=$edge phase=focus ms=${value.workerNanos/1e6} ${value.profile}")
        val scroll=targets.collectFirst {case Target(_,TargetKind.Scroll(id,_),_,_,_,_,_,_) => id}.get
        for index <- 0 until 12 do
          scrolls=Map(scroll->(index*40.0))
          val value=render(None)
          println(s"profile edge=$edge phase=scroll ms=${value.workerNanos/1e6} ${value.profile}")
      finally {session.close();runtime.close()}
