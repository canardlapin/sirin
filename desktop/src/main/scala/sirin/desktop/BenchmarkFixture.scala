package sirin.desktop

private[desktop] final case class BenchmarkGeometry(logicalWidth: Int,logicalHeight: Int,width: Int,height: Int):
  override def toString: String=s"${logicalWidth}x${logicalHeight}logical/${width}x${height}physical"

/** Sticky refusal: a later restoration cannot make a changed fixture qualified. */
private[desktop] final class BenchmarkFixture:
  val expected=BenchmarkGeometry(600,360,1200,720)
  private var last=Option.empty[BenchmarkGeometry]
  private var observations=0L
  def observed: Option[BenchmarkGeometry]=last
  def checks: Long=observations
  private var drifted=false
  def stable: Boolean=checks>0 && !drifted
  def observe(geometry: BenchmarkGeometry): Unit =
    last=Some(geometry);observations+=1
    if geometry!=expected then drifted=true
    require(!drifted,s"benchmark fixture changed: expected=$expected observed=$geometry")
