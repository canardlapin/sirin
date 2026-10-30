package sirin.desktop

import java.io.{ByteArrayInputStream,ByteArrayOutputStream,DataInputStream,DataOutputStream,IOException}
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel,OverlappingFileLockException}
import java.nio.charset.{CodingErrorAction,StandardCharsets}
import java.nio.file.{Files,LinkOption,Path,StandardCopyOption,StandardOpenOption,NoSuchFileException}
import java.security.MessageDigest
import java.util.Locale
import scala.util.control.NonFatal
import sirin.*
import sirin.specimen.*

/** JVM service adapter; constructor and Work descriptions perform no filesystem I/O. */
final class FileDocumentRepository(root: Path) extends DocumentRepository:
  private val configured=root.toAbsolutePath.normalize()
  private val extension=".sirin-doc"
  private val limit=131072
  private def cancelled()(using c: Cancellation): Unit =
    if c.cancelled then throw new InterruptedException("Document operation cancelled")
  private def directory(): Path =
    Files.createDirectories(configured);configured.toRealPath()
  private def path(dir: Path,id: DocumentId): Path=dir.resolve(id.value+extension)
  private def stamp(bytes: Array[Byte]): DocumentStamp =
    DocumentStamp(MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b&255}%02x").mkString)
  private def encode(data: DocumentData): Array[Byte] =
    val bytes=new ByteArrayOutputStream;val out=new DataOutputStream(bytes)
    out.writeLong(0x534952494e444f43L);out.writeInt(1)
    Vector(data.id.value,data.title,data.body).foreach { text =>
      val value=text.getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value)
    }
    out.flush();val result=bytes.toByteArray
    require(result.length<=limit,"Document file budget exceeded");result
  private def decode(bytes: Array[Byte],expected: DocumentId): DocumentData =
    try
      val in=new DataInputStream(new ByteArrayInputStream(bytes))
      require(in.readLong()==0x534952494e444f43L && in.readInt()==1,"Unknown document format")
      def string(maximum: Int): String =
        val length=in.readInt();require(length>=0 && length<=maximum && length<=in.available(),"Invalid document string length")
        val data=new Array[Byte](length);in.readFully(data)
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString
      val id=DocumentId(string(64));val title=string(1024);val body=string(65536)
      require(id==expected && in.read()== -1,"Document identity or trailing data mismatch")
      DocumentData(id,title,body)
    catch case NonFatal(error) => throw new IOException(s"Document ${expected.value} has an invalid file format.",error)
  private def read(dir: Path,id: DocumentId)(using Cancellation): LoadedDocument =
    cancelled()
    val file=path(dir,id)
    if !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) then
      if Files.notExists(file,LinkOption.NOFOLLOW_LINKS) then throw new NoSuchFileException(id.value)
      else throw new IOException("Document files must be regular files, not links.")
    val channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)
    val buffer=ByteBuffer.allocate(limit+1)
    try
      var finished=false
      while !finished do
        cancelled()
        if !buffer.hasRemaining then throw new IOException("Document file budget exceeded.")
        finished=channel.read(buffer)<0
      if buffer.position()>limit then throw new IOException("Document file budget exceeded.")
      val bytes=new Array[Byte](buffer.position());buffer.flip();buffer.get(bytes)
      LoadedDocument(decode(bytes,id),stamp(bytes))
    finally channel.close()
  private def current(dir: Path,id: DocumentId)(using Cancellation): Option[LoadedDocument] =
    try Some(read(dir,id)) catch case _: NoSuchFileException => None
  private def lock[A](file: Path)(body: => A): A =
    val channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)
    try
      val held=try channel.tryLock() catch case _: OverlappingFileLockException => throw new DocumentBusy
      if held==null then throw new DocumentBusy
      try body finally held.release()
    finally channel.close()
  private def withDocument[A](dir: Path,id: DocumentId)(body: => A): A=lock(dir.resolve(id.value+".sirin-lock"))(body)
  private def scan(dir: Path,query: String)(using Cancellation): Vector[DocumentSummary] =
    require(query.length<=256,"Search query exceeds 256 characters")
    val normalized=query.toLowerCase(Locale.ROOT)
    val stream=Files.newDirectoryStream(dir)
    try
      val results=Vector.newBuilder[DocumentSummary];val files=stream.iterator();var visited=0;var documents=0
      while files.hasNext do
        cancelled();val file=files.next();visited+=1
        if visited>4096 then throw new IOException("Document directory entry budget exceeded.")
        val name=file.getFileName.toString
        if name.endsWith(extension) then
          documents+=1
          if documents>512 then throw new IOException("Document collection exceeds 512 documents.")
          val loaded=read(dir,DocumentId(name.stripSuffix(extension)))
          if normalized.isEmpty || loaded.data.title.toLowerCase(Locale.ROOT).contains(normalized) || loaded.data.body.toLowerCase(Locale.ROOT).contains(normalized) then
            results+=DocumentSummary(loaded.data.id,loaded.data.title,loaded.stamp)
      results.result().sortBy(_.id.value)
    finally stream.close()
  private def ensureRoom(dir: Path)(using Cancellation): Unit =
    val stream=Files.newDirectoryStream(dir)
    try
      val entries=stream.iterator();var visited=0;var documents=0
      while entries.hasNext do
        cancelled();val file=entries.next();visited+=1
        if visited>4096 then throw new IOException("Document directory entry budget exceeded.")
        if file.getFileName.toString.endsWith(extension) then documents+=1
        if documents>=512 then throw new IOException("Document collection is full.")
    finally stream.close()
  private def publish(dir: Path,data: DocumentData,create: Boolean,beforeCommit: () => Unit = () => ())(using Cancellation): LoadedDocument =
    val bytes=encode(data);val temp=Files.createTempFile(dir,".sirin-", ".pending")
    try
      val channel=FileChannel.open(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)
      try
        val buffer=ByteBuffer.wrap(bytes)
        while buffer.hasRemaining do {cancelled();channel.write(buffer)}
        channel.force(true)
      finally channel.close()
      cancelled();beforeCommit()
      if create then Files.createLink(path(dir,data.id),temp) // Atomic no-replacement publication.
      else Files.move(temp,path(dir,data.id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
      LoadedDocument(data,stamp(bytes))
    finally Files.deleteIfExists(temp)
  def list(query: String): Work[Vector[DocumentSummary]]=Work {cancelled();scan(directory(),query)}
  def open(id: DocumentId): Work[DocumentSession]=Work {
    cancelled();val dir=directory();new Session(dir,read(dir,id))
  }
  def create(data: DocumentData): Work[DocumentSession]=Work {
    cancelled();val dir=directory()
    lock(dir.resolve(".repository.lock")) {
      withDocument(dir,data.id) {
        current(dir,data.id).foreach(existing => throw new DocumentConflict(data.id,None,Some(existing.stamp)))
        ensureRoom(dir)
        new Session(dir,publish(dir,data,create=true))
      }
    }
  }
  private final class Session(dir: Path,val initial: LoadedDocument) extends DocumentSession:
    private var expected=initial.stamp
    def save(data: DocumentData): Work[LoadedDocument]=Work {
      require(data.id==initial.data.id,"A document session cannot save another document")
      this.synchronized {
        cancelled()
        withDocument(dir,data.id) {
          val actual=current(dir,data.id).map(_.stamp)
          if !actual.contains(expected) then throw new DocumentConflict(data.id,Some(expected),actual)
          val written=publish(dir,data,create=false,beforeCommit=() => {
            val latest=current(dir,data.id).map(_.stamp)
            if !latest.contains(expected) then throw new DocumentConflict(data.id,Some(expected),latest)
          })
          expected=written.stamp;written
        }
      }
    }
