package sirin.desktop

import sirin.*
import sirin.engine.*
import sirin.host.*
import sirin.graphics.UnicodeAnalysis

private[desktop] enum RenderFailureDisposition:
  case Ignored, Recovery, Unavailable

/** Owner-side routing; stale/retired outcomes cannot reject input or change a replacement view. */
private[desktop] final class WindowRenderRecovery(runtime: HeadlessRuntime,mounted: MountedView,
    instance: Long,coordinator: FrameCoordinator):
  private var blocked=false
  def unavailable: Boolean=blocked
  def update(status: WindowRenderStatus): Unit=if status.current.isEmpty then blocked=false
  def request(payload: FramePayload): Option[FrameRequest]=if blocked then None else coordinator.request(payload)
  def report(generation: Long,error: Throwable,status: WindowRenderStatus,queued: Int,composing: Boolean): RenderFailureDisposition =
    if !coordinator.acceptsGeneration(generation) then RenderFailureDisposition.Ignored
    else
      val minimal=status.current.exists(_.recoveryFailed)
      val failure=WindowRenderFailure.from(generation,error,queued,composing,status.current.nonEmpty)
      runtime.transact {mounted.reportWindowRenderFailure(instance,failure)} match
        case CommitResult.Committed(_) =>
          coordinator.invalidate()
          blocked=minimal
          if blocked then RenderFailureDisposition.Unavailable else RenderFailureDisposition.Recovery
        case CommitResult.Refused(_) => RenderFailureDisposition.Ignored
        case CommitResult.Failed(failure) => throw failure
        case _ => throw new IllegalStateException("nested window render-failure publication")

/** A recovery view cannot retire the normal document's editor history. */
private[desktop] final class WindowInputControllers(runtime: HeadlessRuntime,analyze: String => UnicodeAnalysis,
    readClipboard: () => String,writeClipboard: String => Unit,clearComposition: () => Unit):
  private val normal=new EditorController(runtime,analyze,readClipboard,writeClipboard,clearComposition)
  private val recovery=new EditorController(runtime,analyze,readClipboard,writeClipboard,clearComposition)
  private var recovering=false
  def current: EditorController=if recovering then recovery else normal
  def update(window: CapturedWindow): Unit =
    val next=window.renderStatus.current.nonEmpty
    if next!=recovering then
      current.invalidatePresentation();recovering=next;current.invalidatePresentation()
    current.update(window.content)
