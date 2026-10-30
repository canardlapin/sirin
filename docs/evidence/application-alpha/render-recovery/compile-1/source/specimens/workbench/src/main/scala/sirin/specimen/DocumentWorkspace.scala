package sirin.specimen

import sirin.*
import scala.concurrent.duration.*

final case class DocumentImportPlan(source: String,entries: Vector[DocumentSummary]):
  require(entries.size<=128 && entries.map(_.id).distinct.size==entries.size,"Import plan exceeds its budget or repeats an ID")
enum DocumentImportOutcome:
  case Created(summary: DocumentSummary)
  case Failed(message: String)
final case class DocumentImportRecord(id: DocumentId,outcome: DocumentImportOutcome)
final case class DocumentImportReport(total: Int,processed: Int,created: Int,failed: Int):
  require(total>=0 && total<=128 && processed>=0 && processed<=total && created>=0 && failed>=0 && created+failed==processed)
trait DocumentImporter:
  def prepare(source: String): Work[DocumentImportPlan]
  def run(plan: DocumentImportPlan,output: Progress[DocumentImportRecord]): Work[DocumentImportReport]

final class WorkspaceDocument(val owner: Scope,val editor: DocumentEditor,val generation: Long):
  val id=editor.id
  val diskNotice=owner.cell(Option.empty[String])
  val panel=owner.reparent(GlobalKey(s"workspace.document.${id.value}.$generation"),editor.view)
enum WorkspaceViewMode:
  case Workbench, Import
final case class WorkspaceView(id: Long,document: Option[WorkspaceDocument],mode: WorkspaceViewMode=WorkspaceViewMode.Workbench)
final case class DocumentAuthority(id: DocumentId,generation: Long,title: Revision,body: Revision)
enum WorkspaceDecision:
  case CloseView(id: Long)
  case CloseDocument(id: DocumentId)
  case Reload(id: DocumentId)
  case Quit
final case class WorkspacePrompt(decision: WorkspaceDecision,authority: Vector[DocumentAuthority],message: String,changedSinceReview: Boolean=false)
private[specimen] enum WorkspaceFileRequest:
  case Open(id: DocumentId,epoch: Long)
  case Create(data: DocumentData,epoch: Long)
  case Reload(authority: DocumentAuthority,epoch: Long)

/** Public application code: document ownership is independent of collection/window realization. */
final class DocumentWorkspace(scope: Scope,repository: DocumentRepository,importer: DocumentImporter,
    services: ApplicationServices,freshId: () => DocumentId):
  val query=scope.cell("")
  private val quiet=scope.debounce(query,150.millis)
  private val refreshRevision=scope.cell(0L)
  val rows=scope.keyed(Seq.empty[DocumentSummary])(_.id)
  val documents=scope.keyed(Seq.empty[WorkspaceDocument])(_.id)
  val selected=scope.cell(Option.empty[DocumentId])
  private val selectionEpoch=scope.cell(0L)
  private val generation=scope.cell(0L)
  private val viewSerial=scope.cell(1L)
  val views=scope.keyed(Seq(WorkspaceView(1,None)))(_.id)
  val right=scope.cell(false)
  val prompt=scope.cell(Option.empty[WorkspacePrompt])
  val notice=scope.cell(Option.empty[String])
  private val quitAuthority=scope.cell(Option.empty[Vector[DocumentAuthority]])
  private val searchRef=scope.ref()
  private val confirmRef=scope.ref()
  private val importRef=scope.ref()
  private val leftOutlet=scope.ref()
  private val rightOutlet=scope.ref()
  private val parking=scope.ref()
  val selectedDocument: Signal[Option[WorkspaceDocument]]=selected.flatMap {
    case Some(id) => documents.at(id)
    case None => Signal.const(None)
  }
  val search=scope.latest(quiet.zip(refreshRevision))((text,_) => repository.list(text))
  scope.effect(search) {
    case TaskState.Done(Completed((text,revision),Outcome.Succeeded(found),true))
        if text==query.get && text==quiet.get && revision==refreshRevision.get =>
      val order=rows.keyedSnapshot.get.zipWithIndex.map((entry,index) => entry._1 -> index).toMap
      val sorted=found.zipWithIndex.sortBy((entry,index) => (order.getOrElse(entry.id,Int.MaxValue),index)).map(_._1)
      rows.setAll(sorted)
      documents.snapshot.get.foreach { document =>
        found.find(_.id==document.id) match
          case Some(summary) => document.diskNotice.set(Option.when(summary.stamp!=document.editor.lastSaved.get.stamp)("Changed on disk; your draft is retained."))
          case None if text.isEmpty => document.diskNotice.set(Some("Not found on disk; your draft is retained."))
          case _ => ()
      }
    case _ => ()
  }
  val searchStatus=query.zip(quiet).zip(search).map { case ((typed,settled),state) =>
    if typed!=settled || state.pending then "Searching… Previous results remain visible."
    else state.error.fold("Search ready")(e => s"Search failed: ${message(e)}")
  }
  private def message(error: Throwable): String=DocumentData.message(error)
  private def nextEpoch()(using UiTxn): Long =
    selectionEpoch.update(Math.addExact(_,1L));selectionEpoch.get
  private def authority(document: WorkspaceDocument)(using UiTxn): DocumentAuthority =
    DocumentAuthority(document.id,document.generation,document.editor.title.snapshot.revision,document.editor.body.snapshot.revision)
  private def authorities()(using UiTxn): Vector[DocumentAuthority]=documents.snapshot.get.toVector.map(authority)
  private def currentAuthority(id: DocumentId)(using UiTxn): Option[DocumentAuthority]=documents.get(id).map(authority)
  private def dirtyDocuments(using UiTxn): Vector[WorkspaceDocument]=documents.snapshot.get.toVector.filter(_.editor.dirty.get)
  private def permitQuit(using UiTxn): Boolean=quitAuthority.get.contains(authorities())
  private def refreshNow()(using UiTxn): Unit=refreshRevision.update(Math.addExact(_,1L))
  private def mainView()(using UiTxn): Unit =
    if !views.snapshot.get.exists(v => v.document.isEmpty && v.mode==WorkspaceViewMode.Workbench) then
      UiTxn.require(views.size<5,"Close another view first")
      viewSerial.update(Math.addExact(_,1L));views.append(WorkspaceView(viewSerial.get,None))
    searchRef.focus()
  private def showPrompt(decision: WorkspaceDecision,tokens: Vector[DocumentAuthority],text: String)(using UiTxn): Unit =
    mainView();prompt.set(Some(WorkspacePrompt(decision,tokens,text)));confirmRef.focus()
  private def install(session: DocumentSession)(using UiTxn): WorkspaceDocument =
    val id=session.initial.data.id
    UiTxn.require(documents.get(id).nonEmpty || documents.size<32,"Close an open document before opening another")
    documents.get(id).foreach(_.owner.dispose())
    generation.update(Math.addExact(_,1L))
    val owner=scope.child(s"document.${id.value}.${generation.get}")
    val document=new WorkspaceDocument(owner,new DocumentEditor(owner,session),generation.get)
    if documents.get(id).nonEmpty then documents.replace(document) else documents.append(document)
    views.snapshot.get.filter(_.document.exists(_.id==id)).foreach(view => views.replace(view.copy(document=Some(document))))
    owner.effect(document.editor.lastSaved) { _ => refreshNow() }
    document
  private val files=scope.command[WorkspaceFileRequest,WorkspaceFileRequest,DocumentSession]("workspace.file","Open document",
    WorkPolicy(Concurrency.RefuseWhileRunning,Acceptance.EveryInvocation)) { request =>
    notice.set(None)
    request match
      case WorkspaceFileRequest.Open(id,_) =>
        UiTxn.require(documents.get(id).nonEmpty || documents.size<32,"Open-document limit reached")
        Job(request,repository.open(id))
      case WorkspaceFileRequest.Create(data,_) =>
        UiTxn.require(documents.size<32,"Open-document limit reached")
        Job(request,repository.create(data))
      case WorkspaceFileRequest.Reload(token,_) =>
        UiTxn.require(currentAuthority(token.id).contains(token),"The draft changed before reload")
        UiTxn.require(documents.get(token.id).get.editor.save.running.get==0,"Wait for the current save before reloading")
        Job(request,repository.open(token.id))
  } { (request,result) =>
    result.outcome match
      case Outcome.Succeeded(session) =>
        request match
          case WorkspaceFileRequest.Open(id,epoch) =>
            if selectionEpoch.get==epoch && selected.get.contains(id) then install(session)
          case WorkspaceFileRequest.Create(_,epoch) =>
            install(session)
            if selectionEpoch.get==epoch then {selected.set(Some(session.initial.data.id));mainView()}
          case WorkspaceFileRequest.Reload(token,epoch) =>
            if currentAuthority(token.id).contains(token) && documents.get(token.id).exists(_.editor.save.running.get==0) then
              install(session)
              if selectionEpoch.get==epoch then selected.set(Some(token.id))
            else notice.set(Some("Reload finished after a newer edit or save. Your draft is retained; review reload again."))
        refreshNow()
      case Outcome.Failed(error) => notice.set(Some(message(error)))
      case Outcome.Cancelled => notice.set(Some("File operation cancelled; existing drafts remain."))
  }
  private def start(request: WorkspaceFileRequest)(using UiTxn): Unit=files.dispatch(request) match
    case Dispatched.Started(_) => ()
    case Dispatched.Refused(reason) => UiTxn.refuse(reason)
  val open=scope.action[DocumentId]("workspace.open","Open",enabledFor=rows.snapshot.zip(documents.snapshot).zip(files.running).map { case ((visible,cached),running) =>
    id => cached.exists(_.id==id) || (running==0 && cached.size<32 && visible.exists(_.id==id))
  }) { id =>
    val epoch=nextEpoch()
    if documents.get(id).isEmpty then start(WorkspaceFileRequest.Open(id,epoch))
    selected.set(Some(id))
  }
  val newDocument=scope.action[Unit]("workspace.new","New document",documents.snapshot.zip(files.running).map((cached,running) => cached.size<32 && running==0)) { _ =>
    val data=DocumentData(freshId(),"Untitled","")
    start(WorkspaceFileRequest.Create(data,nextEpoch()))
  }
  val refresh=scope.action[Unit]("workspace.refresh","Refresh")(_ => refreshNow())
  val openWorkbench=scope.action[Unit]("workspace.show","Open workbench")(_ => mainView())
  val showImport=scope.action[Unit]("workspace.import.show","Import documents") { _ =>
    if !views.snapshot.get.exists(_.mode==WorkspaceViewMode.Import) then
      UiTxn.require(views.size<5,"Close another view first")
      viewSerial.update(Math.addExact(_,1L));views.append(WorkspaceView(viewSerial.get,None,WorkspaceViewMode.Import))
    importRef.focus()
  }
  val moveEditor=scope.action[Unit]("workspace.move","Move editor left/right")(_ => right.update(!_))
  val moveRow=scope.action[(DocumentId,Int)]("workspace.reorder","Move document") { (id,delta) =>
    val from=rows.indexOf(id).getOrElse(UiTxn.refuse("This document is no longer in the results"))
    val to=math.max(0,math.min(rows.size-1,from+delta));rows.move(id,to)
  }
  private def requireSelected()(using UiTxn): WorkspaceDocument=
    selected.get.flatMap(documents.get).getOrElse(UiTxn.refuse("Select an open document first"))
  private def save(document: WorkspaceDocument)(using UiTxn): Unit=document.editor.save.dispatch(()) match
    case Dispatched.Started(_) => ()
    case Dispatched.Refused(reason) => UiTxn.refuse(reason)
  val saveSelected=scope.action[Unit]("workspace.save","Save",selectedDocument.flatMap(_.fold(Signal.const(false))(_.editor.save.enabled)))(_ => save(requireSelected()))
  val saveCopy=scope.action[Unit]("workspace.copy","Save a copy",selectedDocument.zip(files.running).zip(documents.snapshot).map { case ((document,running),cached) => document.nonEmpty && running==0 && cached.size<32 }) { _ =>
    val editor=requireSelected().editor
    UiTxn.require(editor.validation.get.isEmpty,"Correct document validation before saving a copy")
    val data=DocumentData(freshId(),editor.title.snapshot.value,editor.body.snapshot.value)
    start(WorkspaceFileRequest.Create(data,nextEpoch()))
    notice.set(Some("Saving a copy. The original draft will remain available."))
  }
  private def reload(id: DocumentId)(using UiTxn): Unit =
    val document=documents.get(id).getOrElse(UiTxn.refuse("Document is closed"))
    UiTxn.require(document.editor.save.running.get==0,"Wait for the current save before reloading")
    val token=authority(document)
    showPrompt(WorkspaceDecision.Reload(id),Vector(token),"Reload this document from disk? This replaces its current draft.")
  val reloadSelected=scope.action[Unit]("workspace.reload","Reload from disk",selectedDocument.map(_.nonEmpty))(_ => reload(requireSelected().id))
  val closeDocument=scope.action[Unit]("workspace.closeDocument","Close document",selectedDocument.map(_.nonEmpty)) { _ =>
    val document=requireSelected()
    UiTxn.require(document.editor.save.running.get==0,"Wait for the current save before closing this document")
    if document.editor.dirty.get then showPrompt(WorkspaceDecision.CloseDocument(document.id),Vector(authority(document)),"Discard this draft and close the document?")
    else removeDocument(document)
  }
  private def removeDocument(document: WorkspaceDocument)(using UiTxn): Unit =
    document.owner.dispose();documents.remove(document.id)
    views.snapshot.get.filter(_.document.exists(_.id==document.id)).foreach(view => views.remove(view.id))
    if selected.get.contains(document.id) then {selected.set(None);nextEpoch();()}
  def requestWindowClose(id: Long)(using UiTxn): CloseDecision =
    val view=views.get(id).getOrElse(UiTxn.refuse("View is closed"))
    val relevant=if view.mode==WorkspaceViewMode.Import then Vector.empty else view.document.fold(dirtyDocuments)(d => Vector(d).filter(_.editor.dirty.get))
    if relevant.nonEmpty && !permitQuit then
      showPrompt(WorkspaceDecision.CloseView(id),relevant.map(authority),"Close this view? Its unsaved drafts will stay in the running workbench.")
      CloseDecision.KeepOpen
    else {views.remove(id);CloseDecision.Allow}
  def requestApplicationQuit()(using UiTxn): CloseDecision =
    if dirtyDocuments.isEmpty || permitQuit then
      currentImport.get.filter(_.run.running.get>0).foreach(_.cancel.dispatch(()))
      CloseDecision.Allow
    else
      val names=dirtyDocuments.map(d => s"${DocumentData.preview(d.editor.title.get,80)} (${d.id.value.take(8)})").mkString(", ")
      showPrompt(WorkspaceDecision.Quit,authorities(),s"Discard ${dirtyDocuments.size} unsaved drafts and quit? $names. Completed files and saves already running are retained.")
      CloseDecision.KeepOpen
  val cancelDecision=scope.action[Unit]("workspace.keep","Keep editing") { _ => prompt.set(None);searchRef.focus() }
  val confirmDecision=scope.action[Unit]("workspace.confirm","Confirm") { _ =>
    val decision=prompt.get.getOrElse(UiTxn.refuse("No decision is pending"))
    val current=decision.decision match
      case WorkspaceDecision.Quit => authorities()
      case _ => decision.authority.flatMap(token => currentAuthority(token.id))
    if current!=decision.authority then
      val names=current.flatMap(token => documents.get(token.id)).map(document => DocumentData.preview(document.editor.title.get,80)).mkString(", ")
      prompt.set(Some(decision.copy(authority=current,message=s"Drafts changed while this decision was open: $names. Review the edits, then confirm again.",changedSinceReview=true)))
    else decision.decision match
      case WorkspaceDecision.CloseView(id) => views.remove(id);prompt.set(None)
      case WorkspaceDecision.CloseDocument(id) =>
        val document=documents.get(id).getOrElse(UiTxn.refuse("Document is closed"))
        UiTxn.require(document.editor.save.running.get==0,"Wait for the current save before closing this document")
        removeDocument(document);prompt.set(None)
      case WorkspaceDecision.Reload(id) =>
        start(WorkspaceFileRequest.Reload(current.find(_.id==id).getOrElse(UiTxn.refuse("Document is closed")),nextEpoch()));prompt.set(None)
      case WorkspaceDecision.Quit =>
        quitAuthority.set(Some(current));prompt.set(None);services.quit.dispatch(()) match
          case Dispatched.Started(_) => ()
          case Dispatched.Refused(reason) => UiTxn.refuse(reason)
  }
  val openView=scope.action[Unit]("workspace.view","Open another view",selectedDocument.map(_.nonEmpty)) { _ =>
    UiTxn.require(views.size<5 && views.snapshot.get.count(_.document.nonEmpty)<3,"Close another document view first (three additional views maximum)")
    val document=requireSelected();viewSerial.update(Math.addExact(_,1L));views.append(WorkspaceView(viewSerial.get,Some(document)))
  }
  val importSource=scope.cell("")
  val imports=scope.cell(Vector.empty[WorkspaceImport])
  val currentImport: Signal[Option[WorkspaceImport]]=imports.map(_.lastOption)
  val prepareImport=scope.command[Unit,Versioned[String],DocumentImportPlan]("workspace.import.prepare","Review import",
    WorkPolicy(Concurrency.RefuseWhileRunning,Acceptance.EveryInvocation),_ => currentImport.flatMap(_.fold(Signal.const(true))(_.run.running.map(_==0)))) { _ =>
    val source=importSource.snapshot
    UiTxn.require(source.value.nonEmpty && source.value.length<=2048,"Enter an import directory (at most 2048 characters)")
    Job(source,importer.prepare(source.value))
  } { (source,result) => result.outcome match
    case Outcome.Succeeded(plan) if importSource.versioned.get.revision==source.revision && currentImport.get.forall(_.run.running.get==0) =>
      val owner=scope.child("document-import")
      val session=new WorkspaceImport(owner,plan,importer,() => refreshNow())
      val previous=imports.get
      if previous.size>=8 then {previous.head.owner.dispose();imports.set(previous.tail:+session)} else imports.set(previous:+session)
    case Outcome.Succeeded(_) => notice.set(Some("The import source or active operation changed. Review the source again."))
    case Outcome.Failed(error) => notice.set(Some(message(error)))
    case Outcome.Cancelled => notice.set(Some("Import review cancelled."))
  }
  val refusalNotice: Signal[String]=Vector(open,newDocument,saveSelected,saveCopy,reloadSelected,closeDocument,confirmDecision,openView,prepareImport,showImport)
    .foldLeft(Signal.const(Vector.empty[String])) { (messages,command) =>
      messages.zip(command.lastRefusal).map((prior,reason) => prior++reason.map(text => s"${command.label}: ${DocumentData.preview(text)}"))
    }.map(_.mkString("\n"))
  private val row=Component[DocumentSummary]("document-summary") { (_,input) =>
    val id=input.map(_.id)
    val label=input.flatMap(summary => documents.at(summary.id).flatMap {
      case Some(document) => document.editor.title.zip(document.editor.dirty).map((title,dirty) => (if title.trim.isEmpty then "Untitled" else title)+(if dirty then " *" else ""))
      case None => Signal.const(summary.title)
    })
    Column(
      Button(label).command(open,id),
      Row(Button("Up").command(moveRow,id.map(_ -> -1)),Button("Down").command(moveRow,id.map(_ -> 1))).spacing(6.lp)
    ).spacing(4.lp)
  }
  private val decisionPanel: View=Switch(prompt) {
    case None => View.empty
    case Some(pending) => Column(Text(pending.message).color(ColorRole.Danger),Row(Button(confirmDecision).ref(confirmRef),Button(cancelDecision)).spacing(8.lp)).spacing(8.lp)
  }
  private val safeDecisionPanel: View=Switch(prompt) {
    case None => Text("Application menus remain available. Retry after resolving the document or diagnostic.")
    case Some(pending) =>
      val operation=pending.decision match
        case WorkspaceDecision.CloseView(_) => "Close this view? Unsaved drafts will stay in the running workbench."
        case WorkspaceDecision.CloseDocument(id) => s"Discard the draft and close document ${id.value.take(8)}?"
        case WorkspaceDecision.Reload(id) => s"Reload document ${id.value.take(8)} from disk and replace its draft?"
        case WorkspaceDecision.Quit => "Discard unsaved drafts and quit? Completed files and saves already running are retained."
      Column(Text(if pending.changedSinceReview then "Drafts changed during this decision. Review the current draft before confirming again." else "Review the pending application decision."),
        Text(operation),Row(Button(confirmDecision),Button(cancelDecision)).spacing(8.lp)).spacing(8.lp)
  }
  private val recoveryContent: View=Column(Button(openWorkbench),safeDecisionPanel).spacing(10.lp)
  private val importPanel: View=Column(Text("Import documents").style(TextStyle.Heading),
    TextField.bind(importSource).maxLength(2048).label("Source directory").ref(importRef),Button(prepareImport),Switch(currentImport)(_.fold[View](Text("Choose a directory to review its document count."))(_.view))).spacing(8.lp)
  val mainContent: View=Scroll(Column(
    Text("Sirin document workbench").style(TextStyle.Title),
    Row(Button(newDocument),Button(refresh),Button(openView)).spacing(8.lp),
    Row(Button(moveEditor),Button(showImport)).spacing(8.lp),
    TextField.bind(query).maxLength(256).label("Search titles and content").ref(searchRef),Text(searchStatus).color(ColorRole.Muted),
    Row(Scroll(VirtualRows(rows,64.lp,2)(input => row(input))).width(200.lp).height(260.lp),
      Row(DockOutlet(leftOutlet).grow,DockOutlet(rightOutlet).grow).grow).spacing(12.lp),
    ForEach(documents)(input => Switch(input) { document =>
      document.panel.at(selected.zip(right).map((id,moved) => if id.contains(document.id) then (if moved then rightOutlet else leftOutlet) else parking))
    }),
    Row(Button(saveSelected),Button(reloadSelected),Button(saveCopy),Button(closeDocument)).spacing(8.lp),
    Text(selectedDocument.flatMap(_.fold(Signal.const("Select a document to edit."))(d => d.diskNotice.map(_.getOrElse(""))))).color(ColorRole.Danger),
    Text(notice.map(_.getOrElse(""))).color(ColorRole.Danger),Text(refusalNotice).color(ColorRole.Danger),decisionPanel
  ).spacing(10.lp).padding(16.lp))
  val windows: Windows=Windows(views) { (windowScope,input) =>
    val current=input.flatMap(view => if view.mode==WorkspaceViewMode.Import then Signal.const(None) else view.document.fold(selectedDocument)(d => Signal.const(Some(d))))
    val saveView=windowScope.action[Unit]("view.save","Save",current.flatMap(_.fold(Signal.const(false))(_.editor.save.enabled))) { _ =>
      save(current.get.getOrElse(UiTxn.refuse("Select a document first")))
    }
    val closeView=windowScope.action[Unit]("view.close","Close view")(_ => {requestWindowClose(input.get.id);()})
    val title=input.flatMap(view => if view.mode==WorkspaceViewMode.Import then Signal.const("Import documents") else
      view.document.fold(selectedDocument)(d => Signal.const(Some(d))).flatMap(_.fold(Signal.const("Sirin workbench"))(_.editor.title.map(t => if t.trim.isEmpty then "Untitled" else t))))
    Window(title,MenuBar(Menu("File",MenuItem(saveView,Chord.primary('s')),MenuItem(closeView,Chord.primary('w')))))(
      Switch(input) { view => if view.mode==WorkspaceViewMode.Import then Scroll(Column(importPanel,Button(openWorkbench)).spacing(12.lp).padding(16.lp)) else view.document.fold(mainContent)(document => Scroll(Column(Text(document.editor.title).style(TextStyle.Heading),document.editor.view,
        Text(document.diskNotice.map(_.getOrElse(""))).color(ColorRole.Danger),Button(openWorkbench)).spacing(10.lp).padding(16.lp))) }
    ).onCloseRequest {requestWindowClose(input.get.id)}.renderFailureContent(recoveryContent)
  }.menuBar(MenuBar(Menu("File",MenuItem(newDocument,Chord.primary('n')),MenuItem(openWorkbench,Chord.primary('0')),MenuItem(refresh,Chord.primary('r')),MenuItem(showImport,Chord.primary('i')),MenuItem(services.quit,Chord.primary('q')))))
    .onQuitRequest {requestApplicationQuit()}

final class WorkspaceImport(val owner: Scope,val plan: DocumentImportPlan,importer: DocumentImporter,refresh: () => Action):
  val records=owner.cell(Vector.empty[DocumentImportRecord])
  val report=owner.cell(Option.empty[DocumentImportReport])
  val error=owner.cell(Option.empty[String])
  private val started=owner.cell(false)
  private val observedTerminal=owner.cell(false)
  private val handle=owner.cell(Option.empty[Invocation[DocumentImportReport]])
  val channel=owner.progress[DocumentImportRecord](Delivery.CompleteStream,Capacity(2)) { record =>
    val valid=plan.entries.exists(_.id==record.id) && (record.outcome match
      case DocumentImportOutcome.Created(summary) => summary.id==record.id
      case _ => true)
    UiTxn.require(valid && records.get.size<plan.entries.size && !records.get.exists(_.id==record.id),"Import receipt budget or identity exceeded")
    records.update(_:+record)
  }
  val run=owner.command[Unit,Unit,DocumentImportReport]("import.run","Start import",WorkPolicy(Concurrency.RefuseWhileRunning,Acceptance.EveryInvocation),
    status => started.zip(status.running).map((once,running) => !once && running==0 && plan.entries.nonEmpty)) { _ =>
    started.set(true);Job((),importer.run(plan,channel.producer))
  } { (_,result) =>
    result.outcome match
      case Outcome.Succeeded(receipt) => report.set(Some(receipt))
      case Outcome.Failed(failure) => error.set(Some(DocumentData.message(failure)))
      case Outcome.Cancelled => ()
    channel.close()
  }
  val start=owner.action[Unit]("import.start","Start import",run.enabled) { _ => run.dispatch(()) match
    case Dispatched.Started(invocation) => handle.set(Some(invocation))
    case Dispatched.Refused(reason) => UiTxn.refuse(reason)
  }
  val cancel=owner.action[Unit]("import.cancel","Cancel import",run.running.map(_>0)) { _ =>
    channel.cancelDelivery();handle.get.foreach(_.cancel())
  }
  owner.effect(run.running) { count =>
    if started.get && count==0 && run.running.get==0 && !observedTerminal.get then
      observedTerminal.set(true);refresh()
  }
  val status=records.zip(run.running).zip(channel.state).map { case ((seen,running),state) =>
    val failed=seen.count(_.outcome.isInstanceOf[DocumentImportOutcome.Failed])
    s"Received ${seen.size}/${plan.entries.size} receipts; $failed failed. ${if running>0 then "Import running." else state.toString} Files already written are retained; Refresh shows the current files."
  }
  val view: View=Column(Text(s"Reviewed ${plan.entries.size} documents."),Row(Button(start),Button(cancel)).spacing(8.lp),Text(status),
    Text(error.map(_.getOrElse(""))).color(ColorRole.Danger),Text(records.map(_.takeRight(8).map(r => s"${r.id.value}: ${r.outcome}").mkString("\n")))).spacing(8.lp)
