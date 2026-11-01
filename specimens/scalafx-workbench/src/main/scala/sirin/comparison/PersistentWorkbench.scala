package sirin.comparison

import scalafx.application.{JFXApp3, Platform}
import scalafx.Includes.*
import scalafx.geometry.Insets
import scalafx.scene.Scene
import scalafx.scene.control.*
import scalafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination}
import scalafx.scene.layout.{BorderPane, HBox, Priority, VBox}
import scalafx.stage.Stage
import java.awt.Desktop
import java.nio.file.Path
import scala.collection.mutable
import scala.util.control.NonFatal

object PersistentWorkbench extends JFXApp3:
  private var workspace = Option.empty[PersistentWorkspace]
  override def start(): Unit =
    val args = parameters.raw.toVector
    require(args.isEmpty || args.size == 2 && args.head == "--documents", "Use --documents DIRECTORY")
    val root = args.lift(1).fold(Path.of(System.getProperty("user.home"), ".sirin-scalafx", "documents"))(Path.of(_))
    val model = new PersistentWorkspace(new DocumentStore(root))
    workspace = Some(model)
    Platform.implicitExit = false
    stage = new JFXApp3.PrimaryStage
    val ui = new WorkbenchWindow(model, stage)
    stage.show()
    if Desktop.isDesktopSupported then
      val desktop = Desktop.getDesktop
      if desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER) then
        desktop.setQuitHandler((_, response) => {
          response.cancelQuit()
          Platform.runLater(ui.requestQuit())
        })
      if desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED) then
        desktop.addAppEventListener(new java.awt.desktop.AppReopenedListener:
          def appReopened(event: java.awt.desktop.AppReopenedEvent): Unit = Platform.runLater(ui.reopen())
        )
  override def stopApp(): Unit = workspace.foreach(_.close())

/** Native controls, ordinary bindings and explicit lifecycle cleanup. */
private[comparison] final class EditorPane(model: PersistentWorkspace, val draft: Draft) extends AutoCloseable:
  private val inputRefusal = scalafx.beans.property.StringProperty("")
  private def bounded(control: javafx.scene.control.TextInputControl, maximum: Int): Unit =
    control.setTextFormatter(new javafx.scene.control.TextFormatter[String](
      (change: javafx.scene.control.TextFormatter.Change) =>
        if change.getControlNewText.length > maximum then
          inputRefusal.value = "Edit refused: the field limit is " + maximum + " characters."
          null
        else
          inputRefusal.value = ""
          change
    ))
  val title = new TextField:
    text <==> draft.title
    accessibleText = "Title"
    minWidth = 0
  val body = new TextArea:
    text <==> draft.body
    wrapText = true
    accessibleText = "Document"
    vgrow = Priority.Always
    minWidth = 0
    minHeight = 80
  bounded(title.delegate, 256)
  bounded(body.delegate, 16384)
  private val titleLabel = new Label("Title")
  titleLabel.delegate.setLabelFor(title.delegate)
  private val bodyLabel = new Label("Document")
  bodyLabel.delegate.setLabelFor(body.delegate)
  private val validation = new Label:
    wrapText = true
    style = "-fx-text-fill: #a32121;"
    managed <== text.isNotEmpty
    visible <== managed
  private val save = new Button("Save"):
    defaultButton = true
    onAction = _ => model.save(draft)
  private def validate(): Unit =
    validation.text = draft.problem.value.getOrElse("")
    save.disable = !draft.dirty.value || draft.problem.value.nonEmpty || draft.pending.value >= 3
  private val problemSubscription = draft.problem.onChange((_, _, _) => validate())
  private val dirtySubscription = draft.dirty.onChange((_, _, _) => validate())
  private val pendingSubscription = draft.pending.onChange((_, _, _) => validate())
  private val status = new Label:
    text <== draft.status
    wrapText = true
  private val notice = new Label:
    text <== draft.notice
    wrapText = true
    style = "-fx-text-fill: #a32121;"
    managed <== text.isNotEmpty
    visible <== managed
  private val inputNotice = new Label:
    text <== inputRefusal
    wrapText = true
    style = "-fx-text-fill: #a32121;"
    managed <== text.isNotEmpty
    visible <== managed
  private val diskNotice = new Label:
    text <== draft.diskNotice
    wrapText = true
    managed <== text.isNotEmpty
    visible <== managed
  val node = new VBox:
    spacing = 8
    padding = Insets(16)
    minWidth = 240
    minHeight = 0
    children = Seq(titleLabel, title, bodyLabel, body, validation,
      new HBox(12, save, status), notice, inputNotice, diskNotice)
  validate()
  def close(): Unit =
    title.delegate.textProperty.unbindBidirectional(draft.title.delegate)
    body.delegate.textProperty.unbindBidirectional(draft.body.delegate)
    status.text.unbind()
    notice.text.unbind()
    inputNotice.text.unbind()
    diskNotice.text.unbind()
    problemSubscription.cancel()
    dirtySubscription.cancel()
    pendingSubscription.cancel()

private[comparison] final class WorkbenchWindow(
    model: PersistentWorkspace,
    val primary: Stage
) extends AutoCloseable:
  primary.title = "ScalaFX workbench"
  primary.width = 1000
  primary.height = 720
  primary.minWidth = 640
  primary.minHeight = 400
  private val editors = mutable.Map.empty[Draft, EditorPane]
  private val detached = mutable.Map.empty[Draft, Stage]
  private var importWindow = Option.empty[Stage]
  private var right = false
  private val editorSlot = new BorderPane:
    minWidth = 240
    minHeight = 0
  private val split = new SplitPane
  private val search = new TextField:
    text <==> model.query
    promptText = "Search documents"
    accessibleText = "Search documents"
  private val list = new ListView[Summary](model.rows):
    vgrow = Priority.Always
    minHeight = 80
    accessibleText = "Documents"
    cellFactory = (_: ListView[Summary]) => new ListCell[Summary]:
      item.onChange { (_, _, value) =>
        text = Option(value).fold("")(model.caption)
      }
  private val searchLabel = new Label("Search documents")
  searchLabel.delegate.setLabelFor(search.delegate)
  private val searchStatus = new Label:
    text <== model.searchStatus
    wrapText = true
  private val notices = new Label:
    text <== model.notice
    wrapText = true
    managed <== text.isNotEmpty
    visible <== managed
  private def safe(action: => Unit): Unit =
    try action catch case NonFatal(error) => model.notice.value = Document.message(error)
  private def button(name: String)(action: => Unit): Button = new Button(name):
    onAction = _ => safe(action)
  private val collection = new VBox:
    spacing = 8
    padding = Insets(16)
    minWidth = 220
    minHeight = 0
    children = Seq(searchLabel, search, searchStatus, list, new HBox(8,
      button("Up")(model.selected.value.foreach(d => model.move(d.id, -1))),
      button("Down")(model.selected.value.foreach(d => model.move(d.id, 1)))))
  private val toolbar = new HBox:
    spacing = 8
    padding = Insets(12, 16, 4, 16)
    children = Seq(new Label("Documents"),
      button("New")(model.create()), button("Import")(showImport()), button("Refresh")(model.refresh()))
  private def menuItem(name: String, key: Option[KeyCode] = None)(action: => Unit): MenuItem =
    new MenuItem(name):
      key.foreach(code => accelerator = new KeyCodeCombination(code, KeyCombination.ShortcutDown))
      onAction = _ => safe(action)
  private val menu = new MenuBar:
    useSystemMenuBar = true
    menus = Seq(
      new Menu("File"):
        items = Seq(
          menuItem("New", Some(KeyCode.N))(model.create()),
          menuItem("Save", Some(KeyCode.S))(model.selected.value.foreach(model.save)),
          menuItem("Save a copy")(model.selected.value.foreach(model.saveCopy)),
          menuItem("Reload from disk")(reload()),
          menuItem("Close document")(closeDocument()),
          menuItem("Import", Some(KeyCode.I))(showImport()),
          menuItem("Refresh", Some(KeyCode.R))(model.refresh()),
          new SeparatorMenuItem,
          menuItem("Close window", Some(KeyCode.W))(primary.hide()),
          menuItem("Quit", Some(KeyCode.Q))(requestQuit())
        ),
      new Menu("View"):
        items = Seq(
          menuItem("Show workbench", Some(KeyCode.Digit0))(reopen()),
          menuItem("Move editor to other pane") {
            right = !right
            arrange()
          },
          menuItem("Open document in another window")(detach())
        )
    )
  private val root = new BorderPane:
    top = new VBox(menu, toolbar)
    center = split
    bottom = new VBox:
      padding = Insets(8, 16, 12, 16)
      children = Seq(notices)
  primary.scene = new Scene(root):
    stylesheets = Seq(getClass.getResource("/workbench.css").toExternalForm)
  primary.onCloseRequest = event =>
    event.consume()
    primary.hide()
  private val selectionSubscription = list.selectionModel().selectedItem.onChange { (_, _, value) =>
    Option(value).foreach(summary => model.select(summary.id))
  }
  private val documentSubscription = model.selected.onChange((_, _, _) => showEditor())
  private val changesSubscription = model.changed.onChange { (_, _, _) =>
    list.refresh()
    editors.keys.filterNot(_.alive).toVector.foreach { draft =>
      detached.remove(draft).foreach(_.hide())
      editors.remove(draft).foreach(_.close())
    }
    showEditor()
  }
  private def pane(draft: Draft): EditorPane = editors.getOrElseUpdate(draft, new EditorPane(model, draft))
  private def arrange(): Unit =
    if right then split.items.setAll(collection, editorSlot)
    else split.items.setAll(editorSlot, collection)
    split.dividerPositions = (if right then 0.35 else 0.65)
  private def showEditor(): Unit =
    primary.title = model.selected.value.fold("ScalaFX workbench")(_.title.value + " — ScalaFX")
    editorSlot.center = model.selected.value match
      case Some(draft) if detached.contains(draft) =>
        button("Show document window") {
          detached(draft).show()
          detached(draft).toFront()
        }
      case Some(draft) => pane(draft).node
      case None => new Label("Select a document or choose New to start writing.")
  def reopen(): Unit =
    primary.show()
    primary.toFront()
    search.requestFocus()

  private def confirm(heading: String, message: String, action: String): Boolean =
    reopen()
    val accept = new ButtonType(action, ButtonBar.ButtonData.OKDone)
    val alert = new Alert(Alert.AlertType.Confirmation):
      initOwner(primary)
      title = heading
      headerText = heading
      contentText = message
      buttonTypes = Seq(ButtonType.Cancel, accept)
    alert.showAndWait().contains(accept)

  private def reload(): Unit = model.selected.value.foreach { draft =>
    val revision = draft.revision
    if !draft.dirty.value || confirm("Reload document?", "Discard this draft and reload the file?", "Reload") then
      model.reload(draft, revision)
  }
  private def closeDocument(): Unit = model.selected.value.foreach { draft =>
    val revision = draft.revision
    if !draft.dirty.value || confirm("Close document?", "Discard this unsaved draft?", "Close document") then
      model.closeDocument(draft, revision)
  }
  private def detach(): Unit = model.selected.value.foreach { draft =>
    detached.get(draft) match
      case Some(window) =>
        window.show()
        window.toFront()
      case None if detached.size < 3 =>
        editorSlot.center = new Label("Document is in another window.")
        val content = new BorderPane:
          center = pane(draft).node
        val window = new Stage:
          title <== draft.title
          width = 720
          height = 600
          scene = new Scene(content):
            stylesheets = Seq(getClass.getResource("/workbench.css").toExternalForm)
        val focusSubscription = window.focused.onChange { (_, _, focused) =>
          if focused && draft.alive then model.select(draft.id)
        }
        window.scene().getAccelerators.put(
          new KeyCodeCombination(KeyCode.S, KeyCombination.ShortcutDown),
          () => model.save(draft))
        window.onCloseRequest = event =>
          event.consume()
          content.center = null
          window.hide()
          window.title.unbind()
          focusSubscription.cancel()
          detached.remove(draft)
          showEditor()
        detached(draft) = window
        window.show()
      case _ => model.notice.value = "Close another document window first."
  }

  private def showImport(): Unit =
    importWindow match
      case Some(window) =>
        window.show()
        window.toFront()
      case None =>
        val source = new TextField:
          promptText = "Directory containing .sirin-doc files"
          accessibleText = "Import directory"
          disable <== model.importing || model.importReviewPending
        val status = new Label:
          text <== model.importStatus
          wrapText = true
        val receipts = new ListView[ImportReceipt](model.receipts):
          vgrow = Priority.Always
          accessibleText = "Import results"
          cellFactory = (_: ListView[ImportReceipt]) => new ListCell[ImportReceipt]:
            item.onChange { (_, _, value) =>
              text = Option(value).fold("")(r => r.id + ": " + r.failure.getOrElse("Created"))
            }
        val review = button("Review") {
          require(source.text.value.nonEmpty && source.text.value.length <= 2048, "Enter an import directory.")
          model.reviewImport(Path.of(source.text.value))
        }
        review.disable <== model.importing
        val start = button("Start")(model.startImport())
        start.disable <== !model.importReady
        val cancel = button("Cancel")(model.cancelImport())
        cancel.disable <== !(model.importing || model.importReviewPending)
        val notice = new Label:
          text <== model.notice
          wrapText = true
        val history = new ListView[ImportResult](model.importHistory):
          prefHeight = 100
          accessibleText = "Recent import sessions"
          cellFactory = (_: ListView[ImportResult]) => new ListCell[ImportResult]:
            item.onChange { (_, _, value) =>
              text = Option(value).fold("") { result =>
                result.delivered.toString + "/" + result.total + " results: " +
                  result.created + " created, " + result.failed + " failed" +
                  (if result.cancelled then " (cancelled)" else "")
              }
            }
        val historyPane = new TitledPane:
          text = "Recent sessions"
          this.content = history
          expanded = false
        val content = new VBox:
          spacing = 12
          padding = Insets(16)
          children = Seq(new Label("Import documents"), source, new HBox(8, review, start, cancel),
            status, receipts, notice, historyPane)
        val window = new Stage:
          title = "Import documents"
          width = 720
          height = 520
          scene = new Scene(content):
            stylesheets = Seq(getClass.getResource("/workbench.css").toExternalForm)
        window.onCloseRequest = event =>
          event.consume()
          window.hide()
        importWindow = Some(window)
        window.show()

  def requestQuit(): Unit =
    val authority = model.documents.map(d => (d, d.revision))
    val dirty = model.dirtyDocuments.size
    if dirty == 0 || confirm("Quit with unsaved drafts?",
        "Discard " + dirty + " unsaved drafts and quit? Completed files remain.", "Discard and quit") then
      if authority != model.documents.map(d => (d, d.revision)) then
        model.notice.value = "The drafts changed. Review Quit again."
      else
        close()
        Platform.exit()
  def close(): Unit =
    selectionSubscription.cancel()
    documentSubscription.cancel()
    changesSubscription.cancel()
    editors.values.foreach(_.close())
    detached.values.foreach(_.hide())
    detached.clear()
    importWindow.foreach(_.hide())
    primary.hide()
    model.close()
  arrange()
  showEditor()
