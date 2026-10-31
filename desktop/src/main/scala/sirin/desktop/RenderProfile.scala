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
        var viewports=Map.empty[Long,Viewport];var sequence=0L
        def render(focus: Option[NodeId]): RenderedFrame =
          sequence+=1
          val captured=runtime.withCommitted(reader => reader(mount.snapshot) match
            case Value.Ok(s) => CommittedTree.capture(s,reader)
            case Value.Failed(e) => throw e)
          val request=FrameRequest(sequence,FramePayload(captured,600,360,1200,720,focus,true,None,viewports))
          session.render(request,() => false) match
            case frame: RenderedFrame => frame
            case feedback: LayoutFeedback =>
              runtime.transact {feedback.layout.viewports.foreach(v => mount.reportViewport(v.instance,v.viewport))}
              viewports=feedback.layout.viewports.map(v => v.instance->v.viewport).toMap
              render(focus)
        val cold=render(None)
        val targets=InteractionSnapshot(1,cold.layout).targets
        for index <- 0 until 12 do
          val focus=Some(targets(index%targets.size).id)
          val value=render(focus)
          println(s"profile edge=$edge phase=focus ms=${value.workerNanos/1e6} ${value.profile}")
        // Scrolling is the public scroll element's own state, so the profile scrolls it with wheel input.
        val interaction=new Interaction;val elements=new ElementInput(runtime,interaction)
        val scroll=targets.find(_.element.exists(_.name=="scroll")).get
        var shown=cold
        for index <- 0 until 12 do
          if index>0 then
            interaction.install(InteractionSnapshot(index.toLong,shown.layout));elements.install(shown.layout)
            elements.update(runtime.withCommitted(reader => reader(mount.snapshot) match
              case Value.Ok(s) => CommittedTree.captureWithBindings(s,reader).elements
              case Value.Failed(e) => throw e))
            require(elements.wheel(scroll.clip.x+5,scroll.clip.y+5,-1,Mods.none).exists(_.isInstanceOf[CommitResult.Committed[?]]),"wheel did not scroll")
          val value=render(None);shown=value
          println(s"profile edge=$edge phase=scroll ms=${value.workerNanos/1e6} ${value.profile}")
      finally {session.close();runtime.close()}
