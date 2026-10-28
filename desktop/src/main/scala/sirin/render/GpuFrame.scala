package sirin.render

import sirin.desktop.ffm.Memory.*
import sirin.desktop.ffm.Calls
import sirin.graphics.ImageSnapshot

/** Worker-owned cache reference; native immutable textures have atomic references. */
final class GpuTile private[render](private[render] var handle: Ptr[Byte],val width: Int,val height: Int) extends AutoCloseable:
  private val owner=Thread.currentThread()
  def close(): Unit =
    require(Thread.currentThread() eq owner,"tile owner mismatch")
    if handle!=null then {Calls.unit("sirin_desktop_tile_close",handle);handle=null}

/** Transferable complete immutable frame. Synchronized access serializes capture,
  * presentation and retirement; it never exposes a cache or compiled drawing. */
final class GpuFrame private(private var handle: Ptr[Byte],val width: Int,val height: Int) extends AutoCloseable:
  def present(window: sirin.desktop.NativeWindow): Boolean = synchronized {
    require(handle!=null,"GPU frame closed")
    window.presentGpu(handle.raw)
  }
  def readback(): ImageSnapshot=read("sirin_desktop_frame_read")
  private[sirin] def presentationReadback(): ImageSnapshot=read("sirin_desktop_frame_blit_read")
  private def read(operation: String): ImageSnapshot = synchronized {
    require(handle!=null,"GPU frame closed")
    Zone.acquire { implicit zone =>
      val out=alloc[Byte](width*height*4)
      require(Calls.int(operation,handle,out)==0,"GPU frame readback failed")
      ImageSnapshot.copyRgba8(width,height,out.raw.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))
    }
  }
  def close(): Unit = synchronized {
    if handle!=null then {Calls.unit("sirin_desktop_frame_close",handle);handle=null}
  }

object GpuFrame:
  def liveFrames: Int=Calls.int("sirin_desktop_gpu_frames")
  def liveTiles: Int=Calls.int("sirin_desktop_gpu_tiles")
  def outputBytes: Long=Calls.long("sirin_desktop_gpu_output_bytes")
  def apply(tiles: Vector[(Int,Int,GpuTile)],width: Int,height: Int): GpuFrame = Zone.acquire { implicit zone =>
    require(tiles.nonEmpty && tiles.size<=256,"GPU frame tile count")
    val pointers=alloc[Ptr[Byte]](tiles.size);val xy=alloc[Int](tiles.size*2)
    tiles.zipWithIndex.foreach { case ((x,y,t),i) =>
      require(t.handle!=null,"GPU tile closed")
      pointers(i)=t.handle;xy(i*2)=x;xy(i*2+1)=y
    }
    val out=alloc[Ptr[Byte]]()
    require(Calls.int("sirin_desktop_frame_open",pointers,xy,tiles.size,width,height,out)==0,"GPU frame coverage, generation or inventory rejection")
    new GpuFrame(!out,width,height)
  }
