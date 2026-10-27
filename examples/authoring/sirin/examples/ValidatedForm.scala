// A-0 authoring corpus, program 1: a validated form.
//
// Exercises: scope-owned cells, derived validation, errors shown only after a
// field is left, an async submit command whose enablement comes from the
// command, explicit rejection with `UiTxn.require`, and form semantics.
package sirin.examples.form

import sirin.*

final case class Account(name: String, email: String, age: Int, newsletter: Boolean)
final case class AccountId(value: String)

trait AccountService:
  def create(account: Account): Work[AccountId]

object Rules:
  def name(raw: String): Either[String, String] =
    val t = raw.trim
    if t.isEmpty then Left("Enter your name.") else Right(t)

  def email(raw: String): Either[String, String] =
    val t = raw.trim
    if t.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") then Right(t) else Left("Enter an email address like name@example.org.")

  def age(raw: String): Either[String, Int] =
    raw.trim.toIntOption.filter(a => a >= 13 && a < 150).toRight("Enter an age between 13 and 149.")

/** One text field's state: raw text, whether it was left once, the parse.
  * Owned by the scope passed in; nothing here needs cleanup.
  */
final class Field[A](scope: Scope, parse: String => Either[String, A]):
  val text: Cell[String]                = scope.cell("")
  val touched: Cell[Boolean]            = scope.cell(false)
  val parsed: Signal[Either[String, A]] = text.map(parse)

  /** The error to display: only once the user has left the field. */
  val shown: Signal[Option[String]] =
    (parsed, touched).mapN((p, t) => if t then p.left.toOption else None)

  def view(label: String): View =
    TextField.bind(text).label(label).error(shown).onBlur(touched.set(true))

val SignUpForm = Component.withDeps[AccountService, Unit]("SignUpForm") { (service, scope, _) =>
  val name       = Field(scope, Rules.name)
  val email      = Field(scope, Rules.email)
  val age        = Field(scope, Rules.age)
  val newsletter = scope.cell(false)

  val account: Signal[Either[String, Account]] =
    (name.parsed, email.parsed, age.parsed, newsletter).mapN { (n, e, a, nl) =>
      for n <- n; e <- e; a <- a yield Account(n, e, a, newsletter = nl)
    }

  val created     = scope.cell(Option.empty[AccountId])
  val submitError = scope.cell(Option.empty[String])

  val submit: Command[Unit, AccountId] =
    scope.command[Unit, Account, AccountId](
      id = "signup.submit",
      label = "Create account",
      policy = WorkPolicy(Concurrency.RefuseWhileRunning, Acceptance.EveryInvocation),
      enabled = status => status.running.map(_ == 0)
    ) { _ =>
      // The dispatch boundary has already checked `enabled`; this guard
      // refuses only this command attempt if validation fails.
      val acc = account.get.getOrElse(UiTxn.reject("submit with an invalid form"))
      submitError.set(None)
      Job(acc, service.create(acc))
    } { (_, result) =>
      result.outcome match
        case Outcome.Succeeded(id) => created.set(Some(id))
        case Outcome.Failed(_)     => submitError.set(Some("Could not create the account. Please retry."))
        case Outcome.Cancelled     => ()
    }

  val trySubmit = scope.action[Unit]("signup.validateSubmit", "Create account", enabled = submit.enabled) { _ =>
    // These writes are outside dispatch's implicit attempt, so refusal keeps them.
    name.touched.set(true)
    email.touched.set(true)
    age.touched.set(true)
    submit.dispatch(()) match
      case Dispatched.Started(_) => ()
      case Dispatched.Refused(reason) => submitError.set(Some(reason))
  }

  Column(
    Text("Create an account").style(TextStyle.Title),
    name.view("Name"),
    email.view("Email"),
    age.view("Age"),
    Checkbox.bind(newsletter, "Send me the occasional newsletter"),
    Switch(submitError) {
      case Some(message) =>
        Text(message).color(ColorRole.Danger).semantics(Semantics(Role.Alert, live = Live.Assertive))
      case None => View.empty
    },
    Switch(created) {
      case Some(id) => Text(s"Account ${id.value} created.").semantics(Semantics(Role.Status, live = Live.Polite))
      case None     => View.empty
    },
    Row(Spacer().grow, Button(trySubmit).primary).spacing(Spacing.small)
  ).spacing(Spacing.medium)
    .padding(Spacing.large)
    .semantics(Semantics(Role.Form, name = Some("Create an account")))
    .shortcut(Chord(Key.Enter, Mods.Primary), trySubmit)
}

object InMemoryAccounts extends AccountService:
  def create(account: Account): Work[AccountId] = Work {
    if summon[Cancellation].cancelled then throw new InterruptedException("cancelled")
    AccountId(account.email.takeWhile(_ != '@'))
  }

@main def validatedForm(): Unit =
  Application.run { _ =>
    Windows.single("signup")(_ => Window("Sign up", MenuBar.empty)(SignUpForm.bind(InMemoryAccounts).view))
  }
